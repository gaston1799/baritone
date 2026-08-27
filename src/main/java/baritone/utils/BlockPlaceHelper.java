/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class BlockPlaceHelper {
    // base ticks between places caused by tick logic
    private static final int BASE_PLACE_DELAY = 1;

    private final IPlayerContext ctx;
    private int rightClickTimer;

    BlockPlaceHelper(IPlayerContext playerContext) {
        this.ctx = playerContext;
    }

    public void tick(boolean rightClickRequested) {
        tick(rightClickRequested, null);
    }

    public void tick(boolean rightClickRequested, BlockHitResult validatedHit) {
        if (rightClickTimer > 0) {
            CorrectionLogger.logAlways("place-gates requested=" + rightClickRequested
                    + " timer=" + rightClickTimer + " result=WAIT_TIMER");
            rightClickTimer--;
            return;
        }
        if (!rightClickRequested || ctx.player().isHandsBusy()) {
            CorrectionLogger.logAlways("place-gates requested=" + rightClickRequested
                    + " timer=0 handsBusy=" + ctx.player().isHandsBusy() + " result=SKIP");
            return;
        }
        if (ctx.player().canEat(false) && ctx.player().getMainHandItem().get(net.minecraft.core.component.DataComponents.FOOD) != null) {
            rightClickTimer = Baritone.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
            CorrectionLogger.logAlways("place-gates result=FOOD_USE timer=" + rightClickTimer);
            ctx.playerController().syncHeldItem();
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
            return;
        }
        HitResult mouseOver = validatedHit != null ? validatedHit : ctx.objectMouseOver();
        if (mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
            CorrectionLogger.logAlways("place-gates requested=true timer=0 mouseOver="
                    + (mouseOver == null ? "null" : mouseOver.getType()) + " result=NO_BLOCK_HIT");
            return;
        }
        rightClickTimer = Baritone.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
        CorrectionLogger.logAlways("place-gates requested=true handsBusy=false canEat=false mouseOver=BLOCK hit="
                + ((BlockHitResult) mouseOver).getBlockPos() + "/" + ((BlockHitResult) mouseOver).getDirection()
                + " playerRot=" + ctx.player().getYRot() + "," + ctx.player().getXRot()
                + " timer=" + rightClickTimer + " result=TRY_HANDS");
        for (InteractionHand hand : InteractionHand.values()) {
            InteractionResult blockResult = ctx.playerController().processRightClickBlock(
                    ctx.player(), ctx.world(), hand, (BlockHitResult) mouseOver);
            CorrectionLogger.logAlways("place-gates hand=" + hand + " useItemOn=" + blockResult
                    + " consumes=" + blockResult.consumesAction()
                    + " identitySuccess=" + (blockResult == InteractionResult.SUCCESS));
            if (blockResult.consumesAction()) {
                ctx.player().swing(hand);
                return;
            }
            if (!ctx.player().getItemInHand(hand).isEmpty()) {
                InteractionResult itemResult = ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand);
                CorrectionLogger.logAlways("place-gates hand=" + hand + " useItem=" + itemResult
                        + " consumes=" + itemResult.consumesAction()
                        + " identitySuccess=" + (itemResult == InteractionResult.SUCCESS));
                if (itemResult.consumesAction()) {
                    return;
                }
            } else {
                CorrectionLogger.logAlways("place-gates hand=" + hand + " useItem=SKIP_EMPTY_HAND");
            }
        }
        CorrectionLogger.logAlways("place-gates result=NO_HAND_CONSUMED");
    }
}

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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public class TorchBehavior extends Behavior {

    private BetterBlockPos lastTorchPos;

    public TorchBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (!Baritone.settings().autoTorch.value) return;
        if (event.getType() != TickEvent.Type.IN) return;
        if (ctx.player() == null || ctx.world() == null) return;
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) return;
        if (!ctx.player().onGround()) return;

        BetterBlockPos feet = ctx.playerFeet();

        // Spacing check — must travel at least autoTorchSpacing blocks from last placement
        int spacing = Baritone.settings().autoTorchSpacing.value;
        if (lastTorchPos != null && feet.distSqr(lastTorchPos) < (double) (spacing * spacing)) return;

        // Light level check
        if (ctx.world().getBrightness(LightLayer.BLOCK, feet) > Baritone.settings().autoTorchLightThreshold.value) return;

        // Find a torch in the hotbar (slots 0–8 only — no inventory swap needed)
        int torchSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (ctx.player().getInventory().getItem(i).is(Items.TORCH)) {
                torchSlot = i;
                break;
            }
        }
        if (torchSlot == -1) return;

        // Find a valid placement surface
        PlacementTarget target = findSurface(feet);
        if (target == null) return;

        // Compute rotation toward the target face and hand it to LookBehavior
        Optional<Rotation> rot = RotationUtils.reachable(ctx, target.pos);
        if (rot.isEmpty()) return;
        baritone.getLookBehavior().updateTarget(rot.get(), true);

        // Only place once the camera is actually pointing at the block
        if (!ctx.isLookingAt(target.pos) && !ctx.playerRotations().isReallyCloseTo(rot.get())) return;

        // Switch to torch, place, restore previous slot
        int prevSlot = ctx.player().getInventory().selected;
        ctx.player().getInventory().selected = torchSlot;

        Vec3 hitVec = Vec3.atCenterOf(target.pos).add(
                target.face.getStepX() * 0.5,
                target.face.getStepY() * 0.5,
                target.face.getStepZ() * 0.5
        );
        BlockHitResult hit = new BlockHitResult(hitVec, target.face, target.pos, false);
        InteractionResult result = ctx.playerController().processRightClickBlock(
                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
        if (result == InteractionResult.SUCCESS) {
            ctx.player().swing(InteractionHand.MAIN_HAND);
        }

        ctx.player().getInventory().selected = prevSlot;

        // Mark position regardless of result so we don't spam the same spot
        lastTorchPos = feet;
    }

    /** Finds the best surface to place a torch on, starting from the floor then cardinal walls. */
    private PlacementTarget findSurface(BetterBlockPos feet) {
        // Floor
        BlockPos floor = feet.below();
        if (isSolid(floor)) return new PlacementTarget(floor, Direction.UP);

        // Cardinal walls at feet level
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos wall = feet.relative(dir);
            if (isSolid(wall)) return new PlacementTarget(wall, dir.getOpposite());
        }

        // Try walls at head level (one block up) as fallback for tight shafts
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos wall = feet.above().relative(dir);
            if (isSolid(wall)) return new PlacementTarget(wall, dir.getOpposite());
        }

        return null;
    }

    private boolean isSolid(BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        return !state.isAir() && state.isSolid();
    }

    private static final class PlacementTarget {
        final BlockPos pos;
        final Direction face;

        PlacementTarget(BlockPos pos, Direction face) {
            this.pos = pos;
            this.face = face;
        }
    }
}

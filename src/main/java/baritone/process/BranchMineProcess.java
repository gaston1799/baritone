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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BranchMineProcess extends BaritoneProcessHelper {

    private boolean active;
    private List<BlockPos> targets;

    public BranchMineProcess(Baritone baritone) {
        super(baritone);
    }

    public void start(BetterBlockPos origin, Direction mainDir, int mainLength, int sideLength, int spacing) {
        targets = buildTargets(origin, mainDir, mainLength, sideLength, spacing);
        active = true;
        logDirect(String.format("Branch mine started: %d positions, heading %s",
                targets.size(), mainDir.getName()));
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!active) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // Prune positions that are now passable (already mined or were already air)
        targets.removeIf(pos -> MovementHelper.canWalkThrough(ctx, new BetterBlockPos(pos)));

        if (targets.isEmpty()) {
            logDirect("Branch mine complete");
            active = false;
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // Handle shaft: break a block directly above player if it's on the target list
        baritone.getInputOverrideHandler().clearAllKeys();
        BetterBlockPos feet = ctx.playerFeet();
        Optional<BlockPos> shaft = targets.stream()
                .filter(pos -> pos.getX() == feet.getX() && pos.getZ() == feet.getZ()
                        && pos.getY() >= feet.getY()
                        && !MovementHelper.canWalkThrough(ctx, new BetterBlockPos(pos)))
                .min((a, b) -> Double.compare(feet.above().distSqr(a), feet.above().distSqr(b)));

        if (shaft.isPresent() && ctx.player().onGround() && isSafeToCancel) {
            BlockPos pos = shaft.get();
            BlockState state = baritone.bsi.get0(pos);
            if (!MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
                Optional<baritone.api.utils.Rotation> rot = RotationUtils.reachable(ctx, pos);
                if (rot.isPresent()) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                    if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        // Build GoalComposite from nearest targets (capped to avoid pathfinder overload)
        int cap = Math.min(targets.size(), Baritone.settings().mineMaxOreLocationsCount.value);
        List<BlockPos> nearest = new ArrayList<>(targets.subList(0, targets.size()));
        nearest.sort((a, b) -> Double.compare(a.distSqr(feet), b.distSqr(feet)));
        nearest = nearest.subList(0, cap);

        Goal[] goals = nearest.stream()
                .map(pos -> (Goal) new GoalTwoBlocks(pos))
                .toArray(Goal[]::new);

        return new PathingCommand(new GoalComposite(goals), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        active = false;
        targets = null;
    }

    @Override
    public String displayName0() {
        int remaining = targets == null ? 0 : targets.size();
        return "Branch Mine (" + remaining + " blocks remaining)";
    }

    private static List<BlockPos> buildTargets(BetterBlockPos origin, Direction mainDir,
                                               int mainLength, int sideLength, int spacing) {
        int height = Math.max(1, Baritone.settings().playerHeight.value);
        Direction perp = mainDir.getClockWise();
        List<BlockPos> out = new ArrayList<>();

        for (int d = 0; d < mainLength; d++) {
            int x = origin.getX() + mainDir.getStepX() * d;
            int z = origin.getZ() + mainDir.getStepZ() * d;
            for (int h = 0; h < height; h++) {
                out.add(new BlockPos(x, origin.getY() + h, z));
            }
            // Side branches at each spacing interval
            if (d > 0 && d % spacing == 0) {
                for (int s = 1; s <= sideLength; s++) {
                    for (int h = 0; h < height; h++) {
                        out.add(new BlockPos(
                                x + perp.getStepX() * s,
                                origin.getY() + h,
                                z + perp.getStepZ() * s));
                        out.add(new BlockPos(
                                x - perp.getStepX() * s,
                                origin.getY() + h,
                                z - perp.getStepZ() * s));
                    }
                }
            }
        }
        return out;
    }
}

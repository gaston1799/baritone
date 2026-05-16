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

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.SettingsUtil;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Shared mining-goal selection logic used by MineProcess and the craft analysis path probe.
 */
public final class MiningGoalHelper {

    private MiningGoalHelper() {
    }

    public static Goal goalFor(BlockPos pos, List<BlockPos> knownLocations, CalculationContext context, BlockOptionalMetaLookup filter) {
        boolean assumeVerticalShaftMine = !(context.bsi.get0(pos.above()).getBlock() instanceof FallingBlock);
        if (!baritone.Baritone.settings().forceInternalMining.value) {
            if (assumeVerticalShaftMine) {
                return new GoalThreeBlocks(pos);
            } else {
                return new GoalTwoBlocks(pos);
            }
        }
        boolean upwardGoal = internalMiningGoal(pos.above(), knownLocations, context, filter);
        boolean downwardGoal = internalMiningGoal(pos.below(), knownLocations, context, filter);
        boolean doubleDownwardGoal = internalMiningGoal(pos.below(2), knownLocations, context, filter);
        if (upwardGoal == downwardGoal) {
            if (doubleDownwardGoal && assumeVerticalShaftMine) {
                return new GoalThreeBlocks(pos);
            } else {
                return new GoalTwoBlocks(pos);
            }
        }
        if (upwardGoal) {
            return new GoalBlock(pos);
        }
        if (doubleDownwardGoal && assumeVerticalShaftMine) {
            return new GoalTwoBlocks(pos.below());
        }
        return new GoalBlock(pos.below());
    }

    private static boolean internalMiningGoal(BlockPos pos, List<BlockPos> knownLocations, CalculationContext context, BlockOptionalMetaLookup filter) {
        if (knownLocations.contains(pos)) {
            return true;
        }
        BlockState state = context.bsi.get0(pos);
        if (baritone.Baritone.settings().internalMiningAirException.value && state.getBlock() instanceof AirBlock) {
            return true;
        }
        return filter.has(state) && MineProcess.plausibleToBreak(context, pos);
    }

    private static final class GoalThreeBlocks extends GoalTwoBlocks {

        private GoalThreeBlocks(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && (y == this.y || y == this.y - 1 || y == this.y - 2) && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            return GoalBlock.calculate(xDiff, yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff, zDiff);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 393857768;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalThreeBlocks{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }
}

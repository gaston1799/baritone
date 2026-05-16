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

package baritone.utils.craft;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.process.MiningGoalHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.Favoring;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class CraftPathProbe {

    private CraftPathProbe() {
    }

    public static Optional<Result> probe(Baritone baritone, CraftingPlanner.DirectSource source) {
        if (source == null || !source.knownInWorld || source.knownPositions.isEmpty()) {
            return Optional.empty();
        }
        CalculationContext context = new CalculationContext(baritone);
        List<BlockPos> goals = source.knownPositions.subList(0, Math.min(source.knownPositions.size(), 16));
        Goal goal = createGoal(goals, context, source.blocks);
        BetterBlockPos start = baritone.getPlayerContext().playerFeet();
        Favoring favoring = new Favoring(baritone.getPlayerContext(), baritone.getPathingBehavior().getPath().orElse(null), context);
        AStarPathFinder pathFinder = new AStarPathFinder(start, start.getX(), start.getY(), start.getZ(), goal, favoring, context);
        PathCalculationResult calculation = pathFinder.calculate(Baritone.settings().primaryTimeoutMS.value, Baritone.settings().failureTimeoutMS.value);
        Optional<IPath> path = calculation.getPath();
        if (path.isEmpty()) {
            return Optional.empty();
        }
        boolean reachesGoal = calculation.getType() == PathCalculationResult.Type.SUCCESS_TO_GOAL;
        return Optional.of(Result.from(path.get(), reachesGoal, new BlockStateInterface(baritone.getPlayerContext())));
    }

    private static Goal createGoal(List<BlockPos> positions, CalculationContext context, List<Block> sourceBlocks) {
        List<Goal> goals = new ArrayList<>();
        for (BlockPos position : positions) {
            goals.add(MiningGoalHelper.goalFor(position, positions, context, new baritone.api.utils.BlockOptionalMetaLookup(sourceBlocks)));
        }
        if (goals.size() == 1) {
            return goals.get(0);
        }
        return new GoalComposite(goals.toArray(new Goal[0]));
    }

    public static final class Result {
        public final double ticks;
        public final int movementCount;
        public final BetterBlockPos destination;
        public final boolean reachesGoal;
        public final Map<Block, Integer> blocksToBreak;
        public final int placeCount;

        private Result(double ticks, int movementCount, BetterBlockPos destination, boolean reachesGoal, Map<Block, Integer> blocksToBreak, int placeCount) {
            this.ticks = ticks;
            this.movementCount = movementCount;
            this.destination = destination;
            this.reachesGoal = reachesGoal;
            this.blocksToBreak = blocksToBreak;
            this.placeCount = placeCount;
        }

        private static Result from(IPath path, boolean reachesGoal, BlockStateInterface bsi) {
            Map<Block, Integer> breakCounts = new LinkedHashMap<>();
            Set<BlockPos> places = new LinkedHashSet<>();
            for (baritone.api.pathing.movement.IMovement movement : path.movements()) {
                Movement concrete = (Movement) movement;
                for (BlockPos toBreak : concrete.toBreak(bsi)) {
                    BlockState state = bsi.get0(toBreak);
                    if (!state.isAir()) {
                        breakCounts.merge(state.getBlock(), 1, Integer::sum);
                    }
                }
                places.addAll(concrete.toPlace(bsi));
            }
            return new Result(path.ticksRemainingFrom(0), path.movements().size(), path.getDest(), reachesGoal, breakCounts, places.size());
        }
    }
}

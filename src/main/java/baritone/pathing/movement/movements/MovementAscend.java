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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.pathing.movement.JumpTrajectory;
import baritone.utils.CorrectionLogger;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

public class MovementAscend extends Movement {

    private int ticksWithoutPlacement = 0;
    private String lastTakeoffFailMsg = "";
    private long lastTakeoffFailMs = 0;
    private String lastTakeoffCommitMsg = "";
    private long lastTakeoffCommitMs = 0;

    public MovementAscend(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(
                baritone,
                src,
                dest,
                concat(
                        new BetterBlockPos[]{dest},
                        topDownClearance(src.above(MovementHelper.pathingPlayerHeight()), 1),
                        topDownClearance(dest.above(), MovementHelper.pathingPlayerHeight() - 1)
                ),
                dest.below()
        );
    }

    @Override
    public void reset() {
        super.reset();
        ticksWithoutPlacement = 0;
        lastTakeoffFailMsg = "";
        lastTakeoffCommitMsg = "";
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x, dest.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        BetterBlockPos prior = new BetterBlockPos(src.subtract(getDirection()).above()); // sometimes we back up to place the block, also sprint ascends, also skip descend to straight ascend
        return ImmutableSet.of(src,
                src.above(),
                dest,
                prior,
                prior.above()
        );
    }

    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        int playerHeight = context.playerHeight;
        BlockState toPlace = context.get(destX, y, destZ);
        double additionalPlacementCost = 0;
        if (!MovementHelper.canWalkOn(context, destX, y, destZ, toPlace)) {
            additionalPlacementCost = context.costOfPlacingAt(destX, y, destZ, toPlace);
            if (additionalPlacementCost >= COST_INF) {
                return COST_INF;
            }
            if (!MovementHelper.isReplaceable(destX, y, destZ, toPlace, context.bsi)) {
                return COST_INF;
            }
            boolean foundPlaceOption = false;
            for (int i = 0; i < 5; i++) {
                int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepX();
                int againstY = y + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepY();
                int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepZ();
                if (againstX == x && againstZ == z) { // we might be able to backplace now, but it doesn't matter because it will have been broken by the time we'd need to use it
                    continue;
                }
                if (MovementHelper.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) {
                    foundPlaceOption = true;
                    break;
                }
            }
            if (!foundPlaceOption) { // didn't find a valid place =(
                return COST_INF;
            }
        }
        BlockState srcHeadClearance = context.get(x, y + playerHeight, z); // used lower down anyway
        if (context.get(x, y + playerHeight + 1, z).getBlock() instanceof FallingBlock
                && (MovementHelper.canWalkThrough(context, x, y + playerHeight - 1, z) || !(srcHeadClearance.getBlock() instanceof FallingBlock))) {//it would fall on us and possibly suffocate us
            // HOWEVER, we assume that we're standing in the start position
            // that means that src and src.up(1) are both air
            // maybe they aren't now, but they will be by the time this starts
            // if the lower one is can't walk through and the upper one is falling, that means that by standing on src
            // (the presupposition of this Movement)
            // we have necessarily already cleared the entire FallingBlock stack
            // on top of our head

            // as in, if we have a block, then two FallingBlocks on top of it
            // and that block is x, y+1, z, and we'd have to clear it to even start this movement
            // we don't need to worry about those FallingBlocks because we've already cleared them
            return COST_INF;
            // you may think we only need to check srcUp2, not srcUp
            // however, in the scenario where glitchy world gen where unsupported sand / gravel generates
            // it's possible srcUp is AIR from the start, and srcUp2 is falling
            // and in that scenario, when we arrive and break srcUp2, that lets srcUp3 fall on us and suffocate us
        }
        BlockState srcDown = context.get(x, y - 1, z);
        if (srcDown.getBlock() == Blocks.LADDER || srcDown.getBlock() == Blocks.VINE) {
            return COST_INF;
        }
        // we can jump from soul sand, but not from a bottom slab
        boolean jumpingFromBottomSlab = MovementHelper.isBottomSlab(srcDown);
        boolean jumpingToBottomSlab = MovementHelper.isBottomSlab(toPlace);
        if (jumpingFromBottomSlab && !jumpingToBottomSlab) {
            return COST_INF;// the only thing we can ascend onto from a bottom slab is another bottom slab
        }
        double walk;
        if (jumpingToBottomSlab) {
            if (jumpingFromBottomSlab) {
                walk = Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST); // we hit space immediately on entering this action
                walk += context.jumpPenalty;
            } else {
                walk = WALK_ONE_BLOCK_COST; // we don't hit space we just walk into the slab
            }
        } else {
            // jumpingFromBottomSlab must be false
            if (toPlace.is(Blocks.SOUL_SAND)) {
                walk = WALK_ONE_OVER_SOUL_SAND_COST;
            } else if (toPlace.is(Blocks.MAGMA_BLOCK)) {
                walk = SNEAK_ONE_BLOCK_COST;
            } else {
                walk = Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST);
            }
            walk += context.jumpPenalty;
        }

        double totalCost = walk + additionalPlacementCost;
        // start with srcHeadClearance since we already have its state
        // includeFalling isn't needed because of the falling check above -- if the block above it is falling we will have already exited with COST_INF if we'd actually have to break it
        totalCost += MovementHelper.getMiningDurationTicks(context, x, y + playerHeight, z, srcHeadClearance, false);
        if (totalCost >= COST_INF) {
            return COST_INF;
        }
        for (int offset = 1; offset <= playerHeight; offset++) {
            totalCost += MovementHelper.getMiningDurationTicks(context, destX, y + offset, destZ, offset == playerHeight);
            if (totalCost >= COST_INF) {
                return COST_INF;
            }
        }
        return totalCost;
    }

    @Override
    public MovementState updateState(MovementState state) {
        if (ctx.playerFeet().y < src.y) {
            // this check should run even when in preparing state (breaking blocks)
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        super.updateState(state);
        // TODO incorporate some behavior from ActionClimb (specifically how it waited until it was at most 1.2 blocks away before starting to jump
        // for efficiency in ascending minimal height staircases, which is just repeated MovementAscend, so that it doesn't bonk its head on the ceiling repeatedly)
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ctx.playerFeet().equals(dest) || ctx.playerFeet().equals(dest.offset(getDirection().below()))) {
            return state.setStatus(MovementStatus.SUCCESS);
        }

        BlockState jumpingOnto = BlockStateInterface.get(ctx, positionToPlace);
        if (!MovementHelper.canWalkOn(ctx, positionToPlace, jumpingOnto)) {
            ticksWithoutPlacement++;
            PlaceResult placeResult = MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), false, true);
            CorrectionLogger.logAlways("ascend-place-gate movement=" + src + "->" + dest
                    + " placeAt=" + dest.below() + " result=" + placeResult
                    + " crouching=" + ctx.player().isCrouching()
                    + " onGround=" + ctx.player().onGround()
                    + " tick=" + ticksWithoutPlacement);
            if (placeResult == PlaceResult.READY_TO_PLACE) {
                state.setInput(Input.SNEAK, true);
                if (ctx.player().isCrouching()) {
                    state.setInput(Input.CLICK_RIGHT, true);
                } else {
                    CorrectionLogger.logAlways("ascend-place-gate clickRight=false reason=not-yet-crouching");
                }
            }
            if (ticksWithoutPlacement > 10) {
                // After 10 ticks without placement, we might be standing in the way, move back
                state.setInput(Input.MOVE_BACK, true);
            }

            return state;
        }
        MovementHelper.moveTowards(ctx, state, dest);

        state.setInput(Input.SNEAK, Baritone.settings().allowWalkOnMagmaBlocks.value && jumpingOnto.is(Blocks.MAGMA_BLOCK));

        if (MovementHelper.isBottomSlab(jumpingOnto) && !MovementHelper.isBottomSlab(BlockStateInterface.get(ctx, src.below()))) {
            return state; // don't jump while walking from a non double slab into a bottom slab
        }

        if (Baritone.settings().assumeStep.value || ctx.playerFeet().equals(src.above())) {
            // no need to hit space if we're already jumping
            return state;
        }

        int xAxis = Math.abs(src.getX() - dest.getX()); // either 0 or 1
        int zAxis = Math.abs(src.getZ() - dest.getZ()); // either 0 or 1
        double flatDistToNext = xAxis * Math.abs((dest.getX() + 0.5D) - ctx.player().position().x) + zAxis * Math.abs((dest.getZ() + 0.5D) - ctx.player().position().z);
        double sideDist = zAxis * Math.abs((dest.getX() + 0.5D) - ctx.player().position().x) + xAxis * Math.abs((dest.getZ() + 0.5D) - ctx.player().position().z);

        double lateralMotion = xAxis * ctx.player().getDeltaMovement().z + zAxis * ctx.player().getDeltaMovement().x;
        if (Math.abs(lateralMotion) > 0.1) {
            return state;
        }

        // Forward walk-ahead: simulate the jump using the player's actual
        // movement state. MovementAscend does not request sprint, so a normal
        // ascend must not be simulated as a sprint jump and committed early.
        if (sideDist > 0.2) {
            // not ready yet - keep walking; look up toward the step so the bot
            // appears to be scanning the ascent
            state.setTarget(new MovementState.MovementTarget(
                    RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()),
                    false
            ));
            return state;
        }

        JumpTrajectory takeoff = MovementHelper.planAscendAt(
                ctx,
                src,
                dest,
                state.getInputStates().getOrDefault(Input.SPRINT, false)
        );
        if (!takeoff.reachesTarget()) {
            // Reached the takeoff block but the simulated jump does not clear
            // the step. This is an ACTION failure (not a planner failure): log
            // exactly where and why so it can be diagnosed from corrections.log.
            logTakeoffFailure(takeoff, sideDist, flatDistToNext, lateralMotion);
            // keep walking and re-evaluate next tick
            state.setTarget(new MovementState.MovementTarget(
                    RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()),
                    false
            ));
            return state;
        }

        logTakeoffCommit(takeoff, sideDist, flatDistToNext);

        // Once we are pointing the right way and moving, start jumping
        return state.setInput(Input.JUMP, true);
    }

    /**
     * Logs a failed takeoff: the bot reached the step (sideDist &lt;= 0.2) but the
     * simulated jump could not clear it. Throttled: identical messages repeat
     * at most once per 4s (persistence signal), changed messages as they occur.
     */
    private void logTakeoffFailure(JumpTrajectory trajectory, double sideDist, double flatDistToNext, double lateralMotion) {
        long now = System.currentTimeMillis();
        if (now - lastTakeoffFailMs < 250) {
            return;
        }
        String msg = String.format(java.util.Locale.US,
                "ascend takeoff FAIL: src=(%d,%d,%d) dest=(%d,%d,%d) player=(%.2f,%.2f,%.2f) side=%.2f flat=%.2f lateral=%.2f outcome=%s landingError=%.2f",
                src.getX(), src.getY(), src.getZ(),
                dest.getX(), dest.getY(), dest.getZ(),
                ctx.player().position().x, ctx.player().position().y, ctx.player().position().z,
                sideDist, flatDistToNext, lateralMotion,
                trajectory == null ? "unknown" : trajectory.outcome().name(),
                trajectory == null ? Double.NaN : trajectory.landingError());
        if (msg.equals(lastTakeoffFailMsg) && now - lastTakeoffFailMs < 4000) {
            return;
        }
        lastTakeoffFailMsg = msg;
        lastTakeoffFailMs = now;
        CorrectionLogger.log(msg);
    }

    /**
     * Logs the jump commit (takeoff cleared) once per takeoff attempt.
     */
    private void logTakeoffCommit(JumpTrajectory trajectory, double sideDist, double flatDistToNext) {
        String msg = String.format(java.util.Locale.US,
                "ascend takeoff OK: dest=(%d,%d,%d) side=%.2f flat=%.2f outcome=%s landingError=%.2f",
                dest.getX(), dest.getY(), dest.getZ(), sideDist, flatDistToNext,
                trajectory == null ? "unknown" : trajectory.outcome().name(),
                trajectory == null ? Double.NaN : trajectory.landingError());
        long now = System.currentTimeMillis();
        if (msg.equals(lastTakeoffCommitMsg) && now - lastTakeoffCommitMs < 4000) {
            return;
        }
        lastTakeoffCommitMsg = msg;
        lastTakeoffCommitMs = now;
        CorrectionLogger.log(msg);
    }

    public boolean headBonkClear() {
        BetterBlockPos startUp = src.above(MovementHelper.pathingPlayerHeight());
        for (int i = 0; i < 4; i++) {
            BetterBlockPos check = startUp.relative(Direction.from2DDataValue(i));
            if (!MovementHelper.canWalkThrough(ctx, check)) {
                // We might bonk our head
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // Once a simulated takeoff is committed (or the player is already in
        // flight), path reconciliation must not rewind and create a backward
        // correction flick. Reconcile only after landing or completion.
        return state.getStatus() != MovementStatus.RUNNING
                || (ticksWithoutPlacement == 0 && ctx.player().onGround());
    }
}

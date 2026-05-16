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
import baritone.api.Settings;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.api.utils.VecUtils;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MovementParkour extends Movement implements ParkourDebuggable {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final double TAKEOFF_WINDOW_PROGRESS = 0.5D;
    private static final double ICE_TAKEOFF_WINDOW_PROGRESS = 0.36D;
    private static final double CENTERING_STRAFE_THRESHOLD = 0.08D;
    private static final double MAX_CENTERING_AIM = 0.16D;
    private static final double ICE_RUNWAY_BACKUP_MARGIN = 0.35D;
    private static final double CLIMBABLE_CATCH_COST = LADDER_DOWN_ONE_COST;
    private static final double CLIMBABLE_FRONT_AIM_OFFSET = 0.28D;
    private static final double CLIMBABLE_INWARD_AIM_OFFSET = 0.08D;

    private final Direction direction;
    private final int dist;
    private final int yOffset;
    private final int runwayBack;
    private boolean takeoffWindowStarted;
    private int takeoffWindowTicks;

    private enum TakeoffProfile {
        JAM(0),
        HEAD_HITTER(1),
        LATE(2);

        private final int additionalDelayTicks;

        TakeoffProfile(int additionalDelayTicks) {
            this.additionalDelayTicks = additionalDelayTicks;
        }
    }

    private MovementParkour(IBaritone baritone, BetterBlockPos src, int dist, Direction dir, int yOffset, int runwayBack) {
        super(baritone, src, src.relative(dir, dist).above(yOffset), EMPTY, src.relative(dir, dist).above(yOffset - 1));
        this.direction = dir;
        this.dist = dist;
        this.yOffset = yOffset;
        this.runwayBack = runwayBack;
    }

    @Override
    public void reset() {
        super.reset();
        takeoffWindowStarted = false;
        takeoffWindowTicks = 0;
    }

    public static MovementParkour cost(CalculationContext context, BetterBlockPos src, Direction direction) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        int dist = Math.abs(res.x - src.x) + Math.abs(res.z - src.z);
        return new MovementParkour(context.getBaritone(), src, dist, direction, res.y - src.y, runwayBackForDistance(context, src.x, src.y, src.z, direction, dist));
    }

    public static void cost(CalculationContext context, int x, int y, int z, Direction dir, MutableMoveResult res) {
        if (!context.allowParkour) {
            return;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.world.getMaxBuildHeight()) {
            return;
        }
        int xDiff = dir.getStepX();
        int zDiff = dir.getStepZ();
        if (!MovementHelper.fullyPassable(context, x + xDiff, y, z + zDiff)) {
            // most common case at the top -- the adjacent block isn't air
            return;
        }
        BlockState adj = context.get(x + xDiff, y - 1, z + zDiff);
        if (MovementHelper.canWalkOn(context, x + xDiff, y - 1, z + zDiff, adj)) { // don't parkour if we could just traverse (for now)
            // second most common case -- we could just traverse not parkour
            return;
        }
        if (MovementHelper.avoidWalkingInto(adj) && !(adj.getFluidState().getType() instanceof WaterFluid)) { // magma sucks
            return;
        }
        if (!MovementHelper.hasPlayerClearance(context, x + xDiff, y, z + zDiff)) {
            return;
        }
        boolean normalTakeoffClearance = hasNormalTakeoffClearance(context, x, y, z, x + xDiff, z + zDiff);
        BlockState standingOn = context.get(x, y - 1, z);
        if (standingOn.getBlock() == Blocks.VINE || standingOn.getBlock() == Blocks.LADDER || standingOn.getBlock() instanceof StairBlock || MovementHelper.isBottomSlab(standingOn)) {
            return;
        }
        // we can't jump from (frozen) water with assumeWalkOnWater because we can't be sure it will be frozen
        if (context.assumeWalkOnWater && !standingOn.getFluidState().isEmpty()) {
            return;
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return; // can't jump out of water
        }
        int maxJump;
        if (context.allowWalkOnMagmaBlocks && standingOn.is(Blocks.MAGMA_BLOCK)) {
            maxJump = 2;
        } else if (standingOn.getBlock() == Blocks.SOUL_SAND) {
            maxJump = 2; // 1 block gap
        } else if (context.canSprint) {
            maxJump = 4;
        } else {
            maxJump = 3;
        }
        maxJump = Math.max(maxJump, iceMomentumMaxJump(context, standingOn));

        // check parkour jumps from smallest to largest for obstacles/walls and landing positions
        int verifiedMaxJump = 1; // i - 1 (when i = 2)
        for (int i = 2; i <= maxJump; i++) {
            int destX = x + xDiff * i;
            int destZ = z + zDiff * i;
            int runwayBack = runwayBackForDistance(context, x, y, z, dir, i);
            if (i > 4) {
                if (!context.isLoaded(destX, destZ) || runwayBack <= 0) {
                    break;
                }
            }
            BlockState destInto = context.bsi.get0(destX, y, destZ);
            boolean climbableCatch = canCatchOnClimbable(context, destX, y, destZ, dir, destInto);
            boolean headHitterFlat = i == 2
                    && MovementHelper.fullyPassable(context, destX, y, destZ, destInto)
                    && canUseFlatHeadHitter(context, x, y, z, dir, destX, destZ);

            if (!normalTakeoffClearance && !headHitterFlat) {
                break;
            }
            if (!hasNormalLandingClearance(context, destX, y, destZ) && !climbableCatch && !headHitterFlat) {
                break;
            }

            if (climbableCatch) {
                res.x = destX;
                res.y = y;
                res.z = destZ;
                res.cost = costFromJumpDistance(i) + iceRunwaySetupCost(runwayBack) + CLIMBABLE_CATCH_COST + context.jumpPenalty;
                return;
            }

            // check for ascend landing position
            if (!MovementHelper.fullyPassable(context, destX, y, destZ, destInto)) {
                if (i <= 3
                        && context.allowParkourAscend
                        && context.canSprint
                        && !isClimbableLandingBlock(destInto)
                        && MovementHelper.canWalkOn(context, destX, y, destZ, destInto)
                        && checkOvershootSafety(context.bsi, destX + xDiff, y + 1, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y + 1;
                    res.z = destZ;
                    res.cost = i * SPRINT_ONE_BLOCK_COST + context.jumpPenalty;
                    return;
                }
                break;
            }

            // check for flat landing position
            if (canLandOn(context, destX, y, destZ, i)) {
                if (checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + iceRunwaySetupCost(runwayBack) + context.jumpPenalty;
                    return;
                }
                break;
            }

            // check for descend landing position
            if (i <= 4
                    && MovementHelper.fullyPassable(context, destX, y - 1, destZ)
                    && canLandOn(context, destX, y - 1, destZ, i)
                    && checkOvershootSafety(context.bsi, destX + xDiff, y - 1, destZ + zDiff)) {
                res.x = destX;
                res.y = y - 1;
                res.z = destZ;
                res.cost = costFromJumpDistance(i) + Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST) + context.jumpPenalty;
                return;
            }

            if (!hasContinuationClearance(context, destX, y, destZ) && !headHitterFlat) {
                break;
            }

            verifiedMaxJump = i;
        }

        // parkour place starts here
        if (!context.allowParkourPlace) {
            return;
        }
        // check parkour jumps from largest to smallest for positions to place blocks
        for (int i = verifiedMaxJump; i > 1; i--) {
            int destX = x + i * xDiff;
            int destZ = z + i * zDiff;
            int runwayBack = runwayBackForDistance(context, x, y, z, dir, i);
            if (i > 4 && runwayBack <= 0) {
                continue;
            }
            BlockState toReplace = context.get(destX, y - 1, destZ);
            double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, toReplace);
            if (placeCost >= COST_INF) {
                continue;
            }
            if (!MovementHelper.isReplaceable(destX, y - 1, destZ, toReplace, context.bsi)) {
                continue;
            }
            if (!checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                continue;
            }
            for (int j = 0; j < 5; j++) {
                int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepX();
                int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepY();
                int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepZ();
                if (againstX == destX - xDiff && againstZ == destZ - zDiff) { // we can't turn around that fast
                    continue;
                }
                if (MovementHelper.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + iceRunwaySetupCost(runwayBack) + placeCost + context.jumpPenalty;
                    return;
                }
            }
        }
    }

    private static boolean checkOvershootSafety(BlockStateInterface bsi, int x, int y, int z) {
        // we're going to walk into these two blocks after the landing of the parkour anyway, so make sure they aren't avoidWalkingInto
        return !MovementHelper.avoidWalkingInto(bsi.get0(x, y, z)) && !MovementHelper.avoidWalkingInto(bsi.get0(x, y + 1, z));
    }

    private static boolean canLandOn(CalculationContext context, int destX, int feetY, int destZ, int jumpDistance) {
        BlockState landingOn = context.bsi.get0(destX, feetY - 1, destZ);
        // farmland needs to be canWalkOn otherwise farm can never work at all, but we want to specifically disallow ending a jump on farmland haha
        // frostwalker works here because we can't jump from possibly unfrozen water
        return (landingOn.getBlock() != Blocks.FARMLAND
                && !isClimbableLandingBlock(landingOn)
                && MovementHelper.canWalkOn(context, destX, feetY - 1, destZ, landingOn))
                || (Math.min(16, context.frostWalker + 2) >= jumpDistance && MovementHelper.canUseFrostWalker(context, landingOn));
    }

    private static boolean hasNormalTakeoffClearance(CalculationContext context, int x, int y, int z, int nextX, int nextZ) {
        return MovementHelper.hasFullyPassableClearance(context, nextX, y + 1, nextZ, context.playerHeight)
                && MovementHelper.fullyPassable(context, x, y + context.playerHeight, z);
    }

    private static boolean hasNormalLandingClearance(CalculationContext context, int destX, int feetY, int destZ) {
        return MovementHelper.hasFullyPassableClearance(context, destX, feetY + 1, destZ, context.playerHeight);
    }

    private static boolean hasContinuationClearance(CalculationContext context, int destX, int feetY, int destZ) {
        return MovementHelper.fullyPassable(context, destX, feetY + context.playerHeight + 1, destZ);
    }

    private static boolean canUseFlatHeadHitter(CalculationContext context, int x, int y, int z, Direction dir, int destX, int destZ) {
        int nextX = x + dir.getStepX();
        int nextZ = z + dir.getStepZ();
        if (!MovementHelper.hasPlayerClearance(context, x, y, z)
                || !MovementHelper.hasPlayerClearance(context, nextX, y, nextZ)
                || !MovementHelper.hasPlayerClearance(context, destX, y, destZ)) {
            return false;
        }
        return hasLowCeiling(context, x, y, z)
                || hasLowCeiling(context, nextX, y, nextZ)
                || hasLowCeiling(context, destX, y, destZ);
    }

    private static boolean hasLowCeiling(CalculationContext context, int x, int y, int z) {
        return !MovementHelper.fullyPassable(context, x, y + context.playerHeight, z);
    }

    private static boolean canCatchOnClimbable(CalculationContext context, int destX, int feetY, int destZ, Direction dir, BlockState destInto) {
        return isClimbableLandingBlock(destInto)
                && climbableSupportDirection(context, destX, feetY, destZ, destInto, dir) != null
                && MovementHelper.hasVerticalClearance(context, destX, feetY, destZ, context.playerHeight + 1);
    }

    private static boolean isClimbableLandingBlock(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.LADDER || (block == Blocks.VINE && Baritone.settings().allowVines.value);
    }

    private static Direction climbableSupportDirection(CalculationContext context, int x, int y, int z, BlockState state, Direction approach) {
        Block block = state.getBlock();
        if (block == Blocks.LADDER) {
            Direction support = state.getValue(LadderBlock.FACING).getOpposite();
            return support == approach ? support : null;
        }
        if (block == Blocks.VINE
                && Baritone.settings().allowVines.value
                && MovementHelper.isBlockNormalCube(context.get(x + approach.getStepX(), y, z + approach.getStepZ()))) {
            return approach;
        }
        return null;
    }

    private static double costFromJumpDistance(int dist) {
        switch (dist) {
            case 2:
                return WALK_ONE_BLOCK_COST * 2; // IDK LOL
            case 3:
                return WALK_ONE_BLOCK_COST * 3;
            case 4:
                return SPRINT_ONE_BLOCK_COST * 4;
            case 5:
                return SPRINT_ONE_BLOCK_COST * 5 + WALK_ONE_BLOCK_COST;
            case 6:
                return SPRINT_ONE_BLOCK_COST * 6 + WALK_ONE_BLOCK_COST * 2;
            default:
                throw new IllegalStateException("LOL " + dist);
        }
    }

    private static int iceMomentumMaxJump(CalculationContext context, BlockState standingOn) {
        if (!context.allowIceParkour || !context.canSprint) {
            return 0;
        }
        switch (iceMomentumTier(standingOn)) {
            case 2:
                return 6;
            case 1:
                return 5;
            default:
                return 0;
        }
    }

    private static int runwayBackForDistance(CalculationContext context, int x, int y, int z, Direction dir, int jumpDistance) {
        if (jumpDistance <= 4 || !context.allowIceParkour || !context.canSprint) {
            return 0;
        }
        int minimumTier;
        int required;
        int requiredIceBehind;
        switch (jumpDistance) {
            case 5:
                minimumTier = 1;
                required = 1;
                requiredIceBehind = 0;
                break;
            case 6:
                minimumTier = 2;
                required = 2;
                requiredIceBehind = 1;
                break;
            default:
                return 0;
        }
        if (iceMomentumTier(context.get(x, y - 1, z)) < minimumTier) {
            return 0;
        }
        for (int back = 1; back <= required; back++) {
            int runwayX = x - dir.getStepX() * back;
            int runwayZ = z - dir.getStepZ() * back;
            if (!context.isLoaded(runwayX, runwayZ)) {
                return 0;
            }
            BlockState support = context.get(runwayX, y - 1, runwayZ);
            if (!MovementHelper.canWalkOn(context, runwayX, y - 1, runwayZ, support)
                    || !MovementHelper.hasVerticalClearance(context, runwayX, y, runwayZ, context.playerHeight)
                    || MovementHelper.avoidWalkingInto(context.get(runwayX, y, runwayZ))) {
                return 0;
            }
            if (back <= requiredIceBehind && iceMomentumTier(support) < minimumTier) {
                return 0;
            }
        }
        return required;
    }

    private static int iceMomentumTier(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.BLUE_ICE) {
            return 2;
        }
        if (block == Blocks.PACKED_ICE || block == Blocks.ICE || block == Blocks.FROSTED_ICE) {
            return 1;
        }
        return 0;
    }

    private static double iceRunwaySetupCost(int runwayBack) {
        return runwayBack <= 0 ? 0.0D : runwayBack * (WALK_ONE_BLOCK_COST + SPRINT_ONE_BLOCK_COST);
    }


    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        if (res.x != dest.x || res.y != dest.y || res.z != dest.z) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        int minYOffset = Math.min(0, yOffset);
        for (int i = -runwayBack; i <= dist; i++) {
            for (int y = minYOffset; y < minYOffset + 2; y++) {
                set.add(src.relative(direction, i).above(y));
            }
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // once this movement is instantiated, the state is default to PREPPING
        // but once it's ticked for the first time it changes to RUNNING
        // since we don't really know anything about momentum, it suffices to say Parkour can only be canceled on the 0th tick
        return state.getStatus() != MovementStatus.RUNNING;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().y < Math.min(src.y, dest.y)) {
            // we have fallen
            logDebug("sorry");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (dist >= 4 || yOffset > 0) {
            state.setInput(Input.SPRINT, true);
        }
        if (Baritone.settings().allowWalkOnMagmaBlocks.value && ctx.world().getBlockState(ctx.playerFeet().below()).is(Blocks.MAGMA_BLOCK)) {
            state.setInput(Input.SNEAK, true);
        }
        if (needsMoreIceRunway()) {
            state.setInput(Input.SPRINT, false);
            MovementHelper.moveTowards(ctx, state, runwayStart());
            return state;
        }
        if (shouldSneakForIceLanding()) {
            state.setInput(Input.SNEAK, true);
        }
        if (shouldSneakForClimbableCatch()) {
            state.setInput(Input.SNEAK, true);
        }

        updateMovementTarget(state);
        if (hasTouchedLandingBlock()) {
            Block d = BlockStateInterface.getBlock(ctx, dest);
            if (d == Blocks.VINE || d == Blocks.LADDER) {
                // it physically hurt me to add support for parkour jumping onto a vine
                // but i did it anyway
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (ctx.player().position().y - ctx.playerFeet().getY() < 0.094) { // lilypads
                state.setStatus(MovementStatus.SUCCESS);
            }
        } else if (hasIceRunway()) {
            if (isInTakeoffWindow(takeoffProgress())) {
                handleTakeoffInputs(state);
            }
        } else if (!ctx.playerFeet().equals(src)) {
            if (isInTakeoffWindow(takeoffProgress())) {
                handleTakeoffInputs(state);
            } else if (!ctx.playerFeet().equals(dest.relative(direction, -1)) && (!hasIceRunway() || takeoffProgress() >= 0.0D)) {
                state.setInput(Input.SPRINT, false);
                if (ctx.playerFeet().equals(src.relative(direction, -1))) {
                    MovementHelper.moveTowards(ctx, state, src);
                } else {
                    MovementHelper.moveTowards(ctx, state, src.relative(direction, -1));
                }
            }
        }
        return state;
    }

    private void handleTakeoffInputs(MovementState state) {
        if (!isClimbableDestination()
                && Baritone.settings().allowPlace.value // see PR #3775
                && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway()
                && !MovementHelper.canWalkOn(ctx, dest.below())
                && !ctx.player().onGround()
                && MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), true, false) == PlaceResult.READY_TO_PLACE
        ) {
            // go in the opposite order to check DOWN before all horizontals -- down is preferable because you don't have to look to the side while in midair, which could mess up the trajectory
            state.setInput(Input.CLICK_RIGHT, true);
        }
        if (shouldPressJumpThisTick()) {
            state.setInput(Input.JUMP, true);
        }
    }

    private boolean shouldPressJumpThisTick() {
        double progress = takeoffProgress();
        if (!takeoffWindowStarted) {
            if (!isInTakeoffWindow(progress)) {
                return false;
            }
            takeoffWindowStarted = true;
        }
        takeoffWindowTicks++;
        Settings.ParkourTakeoffTiming timing = Baritone.settings().parkourTakeoffTiming.value;
        if (timing == Settings.ParkourTakeoffTiming.VANILLA) {
            return shouldPressVanillaJump(progress);
        }
        TakeoffProfile profile = resolveTakeoffProfile(timing);
        return takeoffWindowTicks > profile.additionalDelayTicks || progress >= safetyTakeoffProgress(profile);
    }

    private boolean shouldPressVanillaJump(double progress) {
        if (hasIceRunway()) {
            return progress >= safetyTakeoffProgress(TakeoffProfile.JAM);
        }
        // Flat and descending 2 block gaps were intentionally delayed in vanilla Baritone.
        return dist != 3 || yOffset > 0 || progress >= 0.7D;
    }

    private TakeoffProfile resolveTakeoffProfile(Settings.ParkourTakeoffTiming timing) {
        switch (timing) {
            case JAM:
                return TakeoffProfile.JAM;
            case HEAD_HITTER:
                return TakeoffProfile.HEAD_HITTER;
            case LATE:
                return TakeoffProfile.LATE;
            case DYNAMIC:
            default:
                return dynamicTakeoffProfile();
        }
    }

    private TakeoffProfile dynamicTakeoffProfile() {
        if (hasIceRunway()) {
            return TakeoffProfile.JAM;
        }
        if (isFlatHeadHitterGeometry()) {
            return TakeoffProfile.HEAD_HITTER;
        }
        if (dist <= 2) {
            return yOffset < 0 ? TakeoffProfile.HEAD_HITTER : TakeoffProfile.JAM;
        }
        if (yOffset > 0 || dist >= 4) {
            return TakeoffProfile.JAM;
        }
        if (dist == 3) {
            return yOffset < 0 ? TakeoffProfile.LATE : TakeoffProfile.HEAD_HITTER;
        }
        return yOffset < 0 ? TakeoffProfile.HEAD_HITTER : TakeoffProfile.LATE;
    }

    private boolean isInTakeoffWindow(double progress) {
        double threshold = hasIceRunway() ? ICE_TAKEOFF_WINDOW_PROGRESS : TAKEOFF_WINDOW_PROGRESS;
        return progress >= threshold || ctx.player().position().y - src.y > 0.0001;
    }

    private double takeoffProgress() {
        return (ctx.player().position().x - (src.x + 0.5D)) * direction.getStepX()
                + (ctx.player().position().z - (src.z + 0.5D)) * direction.getStepZ();
    }

    private double safetyTakeoffProgress(TakeoffProfile profile) {
        if (hasIceRunway()) {
            return dist >= 6 ? 0.44D : 0.5D;
        }
        switch (profile) {
            case JAM:
                return yOffset > 0 || dist >= 4 ? 0.58D : 0.62D;
            case HEAD_HITTER:
                return dist >= 4 || yOffset > 0 ? 0.62D : 0.68D;
            case LATE:
            default:
                if (dist >= 4 || yOffset > 0) {
                    return 0.64D;
                }
                if (dist == 3) {
                    return yOffset < 0 ? 0.74D : 0.7D;
                }
                return yOffset < 0 ? 0.7D : 0.66D;
        }
    }

    private void updateMovementTarget(MovementState state) {
        if (shouldCommitTakeoffDirection()) {
            state.setTarget(new MovementState.MovementTarget(ctx.playerRotations(), false));
            MovementHelper.moveTowardsWithoutRotation(ctx, state, landingCommitYaw());
            return;
        }
        Vec3 aim = parkourAimPoint();
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aim, ctx.playerRotations())
                .withPitch(ctx.playerRotations().getPitch());
        state.setTarget(new MovementState.MovementTarget(rotation, false));
        state.setInput(Input.MOVE_FORWARD, true);
        if (!hasIceRunway() && !parkourWindowOpen() && ctx.player().onGround()) {
            applyCenteringStrafe(state);
        }
    }

    private boolean shouldCommitTakeoffDirection() {
        return ctx.player().onGround()
                && !parkourWindowOpen()
                && ctx.playerFeet().equals(src);
    }

    private float landingCommitYaw() {
        return RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                VecUtils.getBlockPosCenter(dest),
                ctx.playerRotations()
        ).getYaw();
    }

    private Vec3 parkourAimPoint() {
        if (isClimbableDestination()) {
            return climbableAimPoint();
        }
        Vec3 base = VecUtils.getBlockPosCenter(dest);
        double lead = landingLead();
        if (!parkourWindowOpen() && ctx.player().onGround()) {
            base = new Vec3(
                    src.x + 0.5D + direction.getStepX() * takeoffAimProgress(),
                    src.y + 0.5D,
                    src.z + 0.5D + direction.getStepZ() * takeoffAimProgress()
            );
            lead = 0.0D;
        }
        double lateralCorrection = lateralAimCorrection();
        return new Vec3(
                base.x + direction.getStepX() * lead + leftStepX() * lateralCorrection,
                base.y,
                base.z + direction.getStepZ() * lead + leftStepZ() * lateralCorrection
        );
    }

    private Vec3 climbableAimPoint() {
        Direction support = climbableSupportDirection();
        Vec3 base = VecUtils.getBlockPosCenter(dest);
        if (support == null) {
            return base;
        }
        double wallOffset = parkourWindowOpen() ? CLIMBABLE_INWARD_AIM_OFFSET : -CLIMBABLE_FRONT_AIM_OFFSET;
        double lateralCorrection = lateralAimCorrection();
        return new Vec3(
                base.x + support.getStepX() * wallOffset + leftStepX() * lateralCorrection,
                base.y,
                base.z + support.getStepZ() * wallOffset + leftStepZ() * lateralCorrection
        );
    }

    private void applyCenteringStrafe(MovementState state) {
        double lateral = lateralOffsetFromLane();
        if (Math.abs(lateral) < CENTERING_STRAFE_THRESHOLD) {
            return;
        }
        if (lateral > 0.0D) {
            state.setInput(Input.MOVE_RIGHT, true);
        } else {
            state.setInput(Input.MOVE_LEFT, true);
        }
    }

    private boolean parkourWindowOpen() {
        return takeoffWindowStarted || isInTakeoffWindow(takeoffProgress()) || !ctx.player().onGround();
    }

    private double takeoffAimProgress() {
        if (dist >= 6) {
            return 0.44D;
        }
        if (dist == 5) {
            return 0.48D;
        }
        if (dist >= 4 || yOffset > 0) {
            return 0.56D;
        }
        if (dist == 3) {
            return yOffset < 0 ? 0.68D : 0.62D;
        }
        return yOffset < 0 ? 0.64D : 0.6D;
    }

    private double landingLead() {
        if (dist >= 6) {
            return 0.34D;
        }
        if (dist == 5) {
            return 0.3D;
        }
        if (dist >= 4) {
            return 0.24D;
        }
        if (yOffset < 0) {
            return 0.2D;
        }
        if (yOffset > 0) {
            return 0.12D;
        }
        return 0.16D;
    }

    private double lateralAimCorrection() {
        if (parkourWindowOpen() && !ctx.player().onGround()) {
            return 0.0D;
        }
        double correction = -lateralOffsetFromLane() * 0.8D;
        if (correction > MAX_CENTERING_AIM) {
            return MAX_CENTERING_AIM;
        }
        if (correction < -MAX_CENTERING_AIM) {
            return -MAX_CENTERING_AIM;
        }
        return correction;
    }

    private boolean shouldSneakForClimbableCatch() {
        if (!isClimbableDestination() || ctx.player().onGround()) {
            return false;
        }
        if (ctx.player().onClimbable()) {
            return true;
        }
        return dist - takeoffProgress() <= 0.45D;
    }

    private boolean shouldSneakForIceLanding() {
        if (iceMomentumTier(BlockStateInterface.get(ctx, dest.below())) == 0) {
            return false;
        }
        return ctx.player().onGround() && hasTouchedLandingBlock();
    }

    private boolean hasIceRunway() {
        return runwayBack > 0;
    }

    private BetterBlockPos runwayStart() {
        return src.relative(direction, -runwayBack);
    }

    private boolean needsMoreIceRunway() {
        return hasIceRunway()
                && runwayBack > 1
                && ctx.player().onGround()
                && !parkourWindowOpen()
                && -takeoffProgress() < runwayBack - ICE_RUNWAY_BACKUP_MARGIN;
    }

    private boolean isClimbableDestination() {
        return isClimbableLandingBlock(BlockStateInterface.get(ctx, dest));
    }

    private boolean isFlatHeadHitterGeometry() {
        if (hasIceRunway() || yOffset != 0 || dist != 2 || isClimbableDestination()) {
            return false;
        }
        BetterBlockPos gap = src.relative(direction);
        int playerHeight = MovementHelper.pathingPlayerHeight();
        return MovementHelper.hasVerticalClearance(ctx, src, playerHeight)
                && MovementHelper.hasVerticalClearance(ctx, gap, playerHeight)
                && MovementHelper.hasVerticalClearance(ctx, dest, playerHeight)
                && (hasLowCeiling(src, playerHeight)
                || hasLowCeiling(gap, playerHeight)
                || hasLowCeiling(dest, playerHeight));
    }

    private Direction climbableSupportDirection() {
        BlockState state = BlockStateInterface.get(ctx, dest);
        Direction direct = climbableSupportDirection(state, direction);
        if (direct != null || state.getBlock() != Blocks.VINE || !Baritone.settings().allowVines.value) {
            return direct;
        }
        if (MovementHelper.isBlockNormalCube(BlockStateInterface.get(ctx, dest.north()))) {
            return Direction.NORTH;
        }
        if (MovementHelper.isBlockNormalCube(BlockStateInterface.get(ctx, dest.south()))) {
            return Direction.SOUTH;
        }
        if (MovementHelper.isBlockNormalCube(BlockStateInterface.get(ctx, dest.east()))) {
            return Direction.EAST;
        }
        if (MovementHelper.isBlockNormalCube(BlockStateInterface.get(ctx, dest.west()))) {
            return Direction.WEST;
        }
        return null;
    }

    private Direction climbableSupportDirection(BlockState state, Direction approach) {
        if (state.getBlock() == Blocks.LADDER) {
            Direction support = state.getValue(LadderBlock.FACING).getOpposite();
            return support == approach ? support : null;
        }
        if (state.getBlock() == Blocks.VINE
                && Baritone.settings().allowVines.value
                && MovementHelper.isBlockNormalCube(BlockStateInterface.get(ctx, dest.relative(approach)))) {
            return approach;
        }
        return null;
    }

    private double lateralOffsetFromLane() {
        return (ctx.player().position().x - (src.x + 0.5D)) * leftStepX()
                + (ctx.player().position().z - (src.z + 0.5D)) * leftStepZ();
    }

    private int leftStepX() {
        return direction.getStepZ();
    }

    private int leftStepZ() {
        return -direction.getStepX();
    }

    private boolean hasLowCeiling(BetterBlockPos pos, int playerHeight) {
        return !MovementHelper.fullyPassable(ctx, pos.above(playerHeight));
    }

    private boolean hasTouchedLandingBlock() {
        if (ctx.player() == null) {
            return false;
        }
        if (!ctx.player().onGround() && ctx.player().position().y - dest.y > 0.12D) {
            return false;
        }
        AABB landingBox = new AABB(dest.x, dest.y, dest.z, dest.x + 1.0D, dest.y + 1.0D, dest.z + 1.0D);
        return ctx.player().getBoundingBox().inflate(0.001D).intersects(landingBox);
    }

    @Override
    public ParkourDebugInfo parkourDebugInfo() {
        double threshold = hasIceRunway() ? ICE_TAKEOFF_WINDOW_PROGRESS : TAKEOFF_WINDOW_PROGRESS;
        Vec3 thresholdPoint = new Vec3(
                src.x + 0.5D + direction.getStepX() * threshold,
                src.y + 0.5D,
                src.z + 0.5D + direction.getStepZ() * threshold
        );
        return new ParkourDebugInfo(
                List.of(src),
                dest,
                hasIceRunway() ? runwayStart() : null,
                parkourAimPoint(),
                VecUtils.getBlockPosCenter(dest),
                thresholdPoint,
                shouldCommitTakeoffDirection(),
                parkourWindowOpen()
        );
    }
}

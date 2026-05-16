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

import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MovementParkourDiagonal extends Movement implements ParkourDebuggable {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    private static final double CENTERING_STRAFE_THRESHOLD = 0.08D;
    private static final double TAKEOFF_SIDE_ALIGNMENT_THRESHOLD = 0.14D;
    private static final double TAKEOFF_START_SIDE_ALIGNMENT_THRESHOLD = 0.08D;
    private static final double TAKEOFF_START_MIN_PROGRESS = -0.14D;
    private static final double TAKEOFF_START_MAX_PROGRESS = 0.12D;

    private final Direction forward;
    private final Direction side;
    private final int forwardBlocks;
    private final int yOffset;
    private final int runwayBack;
    private final int minimumIceTier;
    private boolean takeoffWindowStarted;
    private boolean takeoffDirectionCommitted;
    private Integer lastLoggedAlignmentDistanceHundredths;

    private MovementParkourDiagonal(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, Direction forward, Direction side, int forwardBlocks, int yOffset, int runwayBack, int minimumIceTier) {
        super(baritone, src, dest, EMPTY, dest.below());
        this.forward = forward;
        this.side = side;
        this.forwardBlocks = forwardBlocks;
        this.yOffset = yOffset;
        this.runwayBack = runwayBack;
        this.minimumIceTier = minimumIceTier;
    }

    @Override
    public void reset() {
        super.reset();
        takeoffWindowStarted = false;
        takeoffDirectionCommitted = false;
        lastLoggedAlignmentDistanceHundredths = null;
    }

    public static MovementParkourDiagonal cost(CalculationContext context, BetterBlockPos src, Direction forward, Direction side, int forwardBlocks, int yOffset, int runwayBack, int minimumIceTier) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, forward, side, forwardBlocks, yOffset, runwayBack, minimumIceTier, res);
        return new MovementParkourDiagonal(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z), forward, side, forwardBlocks, yOffset, runwayBack, minimumIceTier);
    }

    public static void cost(CalculationContext context, int x, int y, int z, Direction forward, Direction side, int forwardBlocks, int yOffset, int runwayBack, int minimumIceTier, MutableMoveResult res) {
        if (!context.allowParkour) {
            return;
        }
        if (yOffset > 0 && (!context.allowParkourAscend || !context.canSprint)) {
            return;
        }
        if (minimumIceTier > 0 && (!context.allowIceParkour || !context.canSprint)) {
            return;
        }
        if (forwardBlocks > 2 && !context.canSprint) {
            return;
        }
        if (!MovementHelper.hasPlayerClearance(context, x, y, z)) {
            return;
        }
        if (!hasJumpHeadroom(context, x, y, z, forward, side, forwardBlocks, yOffset)) {
            return;
        }
        BlockState standingOn = context.get(x, y - 1, z);
        if (standingOn.getBlock() == Blocks.VINE || standingOn.getBlock() == Blocks.LADDER || standingOn.getBlock() instanceof StairBlock || MovementHelper.isBottomSlab(standingOn)) {
            return;
        }
        if (context.assumeWalkOnWater && !standingOn.getFluidState().isEmpty()) {
            return;
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return;
        }
        if (iceTier(standingOn) < minimumIceTier) {
            return;
        }
        if (runwayBack > 0 && !hasRunway(context, x, y, z, forward, runwayBack)) {
            return;
        }

        int forwardX = forward.getStepX();
        int forwardZ = forward.getStepZ();
        int sideX = side.getStepX();
        int sideZ = side.getStepZ();

        if (!MovementHelper.fullyPassable(context, x + forwardX, y, z + forwardZ)) {
            return;
        }
        BlockState adj = context.get(x + forwardX, y - 1, z + forwardZ);
        if (MovementHelper.canWalkOn(context, x + forwardX, y - 1, z + forwardZ, adj)) {
            return;
        }
        if (MovementHelper.avoidWalkingInto(adj) && !(adj.getFluidState().getType() instanceof WaterFluid)) {
            return;
        }

        for (int step = 1; step <= forwardBlocks; step++) {
            int laneAX = x + forwardX * step;
            int laneAZ = z + forwardZ * step;
            if (!MovementHelper.hasPlayerClearance(context, laneAX, y, laneAZ)) {
                return;
            }
            if (step < forwardBlocks) {
                int laneBX = laneAX + sideX;
                int laneBZ = laneAZ + sideZ;
                if (!MovementHelper.hasPlayerClearance(context, laneBX, y, laneBZ)) {
                    return;
                }
                if (MovementHelper.canWalkOn(context, laneAX, y - 1, laneAZ, context.get(laneAX, y - 1, laneAZ))
                        || MovementHelper.canWalkOn(context, laneBX, y - 1, laneBZ, context.get(laneBX, y - 1, laneBZ))) {
                    return;
                }
            }
        }

        int destX = x + forwardX * forwardBlocks + sideX;
        int destZ = z + forwardZ * forwardBlocks + sideZ;
        int feetY = y + yOffset;
        if (!MovementHelper.hasPlayerClearance(context, destX, feetY, destZ)) {
            return;
        }
        if (!checkOvershootSafety(context.bsi, destX + forwardX, feetY, destZ + forwardZ)) {
            return;
        }

        if (yOffset == 0) {
            BlockState landingOn = context.get(destX, y - 1, destZ);
            if (landingOn.getBlock() == Blocks.FARMLAND || !MovementHelper.canWalkOn(context, destX, y - 1, destZ, landingOn)) {
                return;
            }
            BlockState destInto = context.get(destX, y, destZ);
            if (!MovementHelper.fullyPassable(context, destX, y, destZ, destInto)) {
                return;
            }
        } else if (yOffset == 1) {
            BlockState landingOn = context.get(destX, y, destZ);
            if (landingOn.getBlock() == Blocks.FARMLAND || !MovementHelper.canWalkOn(context, destX, y, destZ, landingOn)) {
                return;
            }
            if (!MovementHelper.hasFullyPassableClearance(context, destX, y + 1, destZ, context.playerHeight)) {
                return;
            }
        } else {
            return;
        }

        res.x = destX;
        res.y = feetY;
        res.z = destZ;
        res.cost = baseCost(forwardBlocks, yOffset) + runwayCost(runwayBack) + context.jumpPenalty;
    }

    private static boolean hasJumpHeadroom(CalculationContext context, int x, int y, int z, Direction forward, Direction side, int forwardBlocks, int yOffset) {
        int forwardX = forward.getStepX();
        int forwardZ = forward.getStepZ();
        int sideX = side.getStepX();
        int sideZ = side.getStepZ();
        int headroomY = y + context.playerHeight;
        if (!MovementHelper.fullyPassable(context, x, headroomY, z)) {
            return false;
        }
        for (int step = 1; step <= forwardBlocks; step++) {
            int laneAX = x + forwardX * step;
            int laneAZ = z + forwardZ * step;
            if (!MovementHelper.fullyPassable(context, laneAX, headroomY, laneAZ)) {
                return false;
            }
            int laneBX = laneAX + sideX;
            int laneBZ = laneAZ + sideZ;
            if (!MovementHelper.fullyPassable(context, laneBX, headroomY, laneBZ)) {
                return false;
            }
        }
        return yOffset <= 0 || MovementHelper.fullyPassable(context, x + forwardX * forwardBlocks + sideX, headroomY + yOffset, z + forwardZ * forwardBlocks + sideZ);
    }

    private static boolean hasRunway(CalculationContext context, int x, int y, int z, Direction forward, int runwayBack) {
        for (int back = 1; back <= runwayBack; back++) {
            int runwayX = x - forward.getStepX() * back;
            int runwayZ = z - forward.getStepZ() * back;
            if (!context.isLoaded(runwayX, runwayZ)) {
                return false;
            }
            BlockState support = context.get(runwayX, y - 1, runwayZ);
            if (!MovementHelper.canWalkOn(context, runwayX, y - 1, runwayZ, support)
                    || !MovementHelper.hasVerticalClearance(context, runwayX, y, runwayZ, context.playerHeight)
                    || MovementHelper.avoidWalkingInto(context.get(runwayX, y, runwayZ))) {
                return false;
            }
        }
        return true;
    }

    private static int iceTier(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.BLUE_ICE) {
            return 2;
        }
        if (block == Blocks.PACKED_ICE || block == Blocks.ICE || block == Blocks.FROSTED_ICE) {
            return 1;
        }
        return 0;
    }

    private static boolean checkOvershootSafety(BlockStateInterface bsi, int x, int y, int z) {
        return !MovementHelper.avoidWalkingInto(bsi.get0(x, y, z)) && !MovementHelper.avoidWalkingInto(bsi.get0(x, y + 1, z));
    }

    private static double baseCost(int forwardBlocks, int yOffset) {
        double base;
        switch (forwardBlocks) {
            case 2:
                base = WALK_ONE_BLOCK_COST * 2.4D;
                break;
            case 3:
                base = SPRINT_ONE_BLOCK_COST * 3.2D;
                break;
            case 4:
                base = SPRINT_ONE_BLOCK_COST * 4.2D;
                break;
            default:
                base = SPRINT_ONE_BLOCK_COST * forwardBlocks + WALK_ONE_BLOCK_COST;
                break;
        }
        if (yOffset > 0) {
            base += SPRINT_ONE_BLOCK_COST * 0.35D;
        }
        return base;
    }

    private static double runwayCost(int runwayBack) {
        return runwayBack <= 0 ? 0.0D : runwayBack * (WALK_ONE_BLOCK_COST + SPRINT_ONE_BLOCK_COST);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, forward, side, forwardBlocks, yOffset, runwayBack, minimumIceTier, result);
        if (result.x != dest.x || result.y != dest.y || result.z != dest.z) {
            return COST_INF;
        }
        return result.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        int maxYOffset = Math.max(1, yOffset);
        for (int back = -runwayBack; back <= forwardBlocks; back++) {
            BetterBlockPos straight = src.relative(forward, back);
            BetterBlockPos diagonal = straight.relative(side);
            for (int y = 0; y <= maxYOffset; y++) {
                set.add(straight.above(y));
                set.add(diagonal.above(y));
            }
        }
        set.add(dest);
        return set;
    }

    @Override
    protected boolean safeToCancel(MovementState currentState) {
        return currentState.getStatus() != MovementStatus.RUNNING;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().y < Math.min(src.y, dest.y)) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (shouldSprintForTakeoff()) {
            state.setInput(Input.SPRINT, true);
        }
        if (shouldSneakForIceLanding()) {
            state.setInput(Input.SNEAK, true);
        }
        updateMovementTarget(state);
        if (hasTouchedLandingBlock()) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        if (shouldPressJumpThisTick()) {
            state.setInput(Input.JUMP, true);
        }
        return state;
    }

    private void updateMovementTarget(MovementState state) {
        if (shouldAlignBeforeTakeoff()) {
            applyTakeoffAlignment(state);
            return;
        }
        lastLoggedAlignmentDistanceHundredths = null;
        if (shouldCommitTakeoffDirection()) {
            takeoffDirectionCommitted = true;
            state.setTarget(new MovementState.MovementTarget(new Rotation(landingCommitYaw(), ctx.playerRotations().getPitch()), false));
            state.setInput(Input.SNEAK, false);
            state.setInput(Input.SPRINT, true);
            state.setInput(Input.MOVE_FORWARD, true);
            return;
        }
        Vec3 aim = parkourAimPoint();
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), aim, ctx.playerRotations())
                .withPitch(ctx.playerRotations().getPitch());
        state.setTarget(new MovementState.MovementTarget(rotation, false));
        state.setInput(Input.MOVE_FORWARD, true);
        if (!parkourWindowOpen() && ctx.player().onGround()) {
            applyCenteringStrafe(state);
        }
    }

    private boolean shouldSprintForTakeoff() {
        return !hasTouchedLandingBlock()
                && (forwardBlocks >= 3 || yOffset > 0 || runwayBack > 0);
    }

    private boolean shouldCommitTakeoffDirection() {
        if (takeoffDirectionCommitted) {
            return ctx.player().onGround() && !parkourWindowOpen();
        }
        return (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.relative(side)))
                && ctx.player().onGround()
                && !parkourWindowOpen()
                && isAlignedForTakeoffStart();
    }

    private float landingCommitYaw() {
        return RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                VecUtils.getBlockPosCenter(dest),
                ctx.playerRotations()
        ).getYaw();
    }

    private Vec3 parkourAimPoint() {
        if (!parkourWindowOpen() && ctx.player().onGround()) {
            return new Vec3(
                    src.x + 0.5D + forward.getStepX() * takeoffAimProgress() + side.getStepX() * takeoffSideAim(),
                    src.y + 0.5D,
                    src.z + 0.5D + forward.getStepZ() * takeoffAimProgress() + side.getStepZ() * takeoffSideAim()
            );
        }
        Vec3 destCenter = VecUtils.getBlockPosCenter(dest);
        return new Vec3(
                destCenter.x + forward.getStepX() * landingLead(),
                destCenter.y,
                destCenter.z + forward.getStepZ() * landingLead()
        );
    }

    private boolean isInTakeoffWindow() {
        if (shouldAlignBeforeTakeoff()) {
            return false;
        }
        double progress = takeoffProgress();
        if (ctx.player().position().y - src.y > 0.0001D) {
            takeoffWindowStarted = true;
        } else if (progress >= takeoffWindowThreshold()
                && (isSideAlignedForTakeoff() || progress >= takeoffWindowThreshold() + 0.12D)) {
            takeoffWindowStarted = true;
        }
        return takeoffWindowStarted;
    }

    private boolean shouldPressJumpThisTick() {
        double progress = takeoffProgress();
        if (!takeoffWindowStarted && !isInTakeoffWindow()) {
            return false;
        }
        return progress >= takeoffJumpThreshold();
    }

    private boolean parkourWindowOpen() {
        return takeoffWindowStarted || !ctx.player().onGround();
    }

    private double takeoffProgress() {
        return (ctx.player().position().x - (src.x + 0.5D)) * forward.getStepX()
                + (ctx.player().position().z - (src.z + 0.5D)) * forward.getStepZ();
    }

    private double takeoffWindowThreshold() {
        if (runwayBack > 0) {
            return yOffset > 0 ? 0.34D : 0.36D;
        }
        if (yOffset > 0) {
            return forwardBlocks >= 4 ? 0.5D : 0.56D;
        }
        return forwardBlocks >= 3 ? 0.52D : 0.58D;
    }

    private double takeoffAimProgress() {
        if (runwayBack > 0) {
            return 0.58D;
        }
        if (yOffset > 0) {
            return forwardBlocks >= 4 ? 0.54D : 0.58D;
        }
        return forwardBlocks >= 3 ? 0.56D : 0.6D;
    }

    private double takeoffJumpThreshold() {
        if (runwayBack > 0) {
            return 0.5D;
        }
        if (yOffset > 0 || forwardBlocks >= 4) {
            return 0.58D;
        }
        return 0.62D;
    }

    private double takeoffSideAim() {
        return takeoffAimProgress() / forwardBlocks;
    }

    private double landingLead() {
        if (yOffset > 0) {
            return 0.12D;
        }
        return forwardBlocks >= 4 ? 0.22D : 0.16D;
    }

    private boolean shouldSneakForIceLanding() {
        if (iceTier(BlockStateInterface.get(ctx, dest.below())) == 0) {
            return false;
        }
        return ctx.player().onGround() && hasTouchedLandingBlock();
    }

    private boolean isSideAlignedForTakeoff() {
        return Math.abs(sideProgress() - takeoffSideAim()) <= TAKEOFF_SIDE_ALIGNMENT_THRESHOLD;
    }

    private boolean shouldAlignBeforeTakeoff() {
        return ctx.player().onGround()
                && !takeoffDirectionCommitted
                && !parkourWindowOpen()
                && ctx.playerFeet().equals(src)
                && !isAlignedForTakeoffStart();
    }

    private boolean isAlignedForTakeoffStart() {
        double progress = takeoffProgress();
        return progress >= TAKEOFF_START_MIN_PROGRESS
                && progress <= TAKEOFF_START_MAX_PROGRESS
                && Math.abs(sideProgress()) <= TAKEOFF_START_SIDE_ALIGNMENT_THRESHOLD;
    }

    private void applyTakeoffAlignment(MovementState state) {
        state.setTarget(new MovementState.MovementTarget(new Rotation(landingCommitYaw(), ctx.playerRotations().getPitch()), false));
        state.setInput(Input.SNEAK, true);
        state.setInput(Input.SPRINT, false);
        logAlignmentDistance();

        double progress = takeoffProgress();
        if (progress > TAKEOFF_START_MAX_PROGRESS) {
            state.setInput(Input.MOVE_BACK, true);
        } else if (progress < TAKEOFF_START_MIN_PROGRESS) {
            state.setInput(Input.MOVE_FORWARD, true);
        }

        double lateral = sideProgress();
        if (Math.abs(lateral) <= TAKEOFF_START_SIDE_ALIGNMENT_THRESHOLD) {
            return;
        }
        boolean sideIsRight = side == forward.getClockWise();
        if (lateral < 0.0D) {
            state.setInput(sideIsRight ? Input.MOVE_RIGHT : Input.MOVE_LEFT, true);
        } else {
            state.setInput(sideIsRight ? Input.MOVE_LEFT : Input.MOVE_RIGHT, true);
        }
    }

    private void logAlignmentDistance() {
        double correctionDistance = Math.hypot(
                Math.max(0.0D, Math.abs(sideProgress()) - TAKEOFF_START_SIDE_ALIGNMENT_THRESHOLD),
                progressCorrectionDistance()
        );
        int hundredths = (int) Math.round(correctionDistance * 100.0D);
        if (lastLoggedAlignmentDistanceHundredths != null && lastLoggedAlignmentDistanceHundredths == hundredths) {
            return;
        }
        lastLoggedAlignmentDistanceHundredths = hundredths;
        logDirect(String.format(Locale.ROOT, "Angled parkour correction dist: %.2f", correctionDistance));
    }

    private double progressCorrectionDistance() {
        double progress = takeoffProgress();
        if (progress < TAKEOFF_START_MIN_PROGRESS) {
            return TAKEOFF_START_MIN_PROGRESS - progress;
        }
        if (progress > TAKEOFF_START_MAX_PROGRESS) {
            return progress - TAKEOFF_START_MAX_PROGRESS;
        }
        return 0.0D;
    }

    private double sideProgress() {
        return (ctx.player().position().x - (src.x + 0.5D)) * side.getStepX()
                + (ctx.player().position().z - (src.z + 0.5D)) * side.getStepZ();
    }

    private void applyCenteringStrafe(MovementState state) {
        double error = sideProgress() - Math.min(Math.max(takeoffProgress(), 0.0D), takeoffAimProgress()) / forwardBlocks;
        if (Math.abs(error) < CENTERING_STRAFE_THRESHOLD) {
            return;
        }
        boolean sideIsRight = side == forward.getClockWise();
        if (error < 0.0D) {
            state.setInput(sideIsRight ? Input.MOVE_RIGHT : Input.MOVE_LEFT, true);
        } else {
            state.setInput(sideIsRight ? Input.MOVE_LEFT : Input.MOVE_RIGHT, true);
        }
    }

    private boolean hasTouchedLandingBlock() {
        if (ctx.player() == null) {
            return false;
        }
        if (!ctx.player().onGround()) {
            return false;
        }
        AABB landingBox = new AABB(dest.x, dest.y, dest.z, dest.x + 1.0D, dest.y + 1.0D, dest.z + 1.0D);
        return ctx.player().getBoundingBox().inflate(0.001D).intersects(landingBox);
    }

    @Override
    public ParkourDebugInfo parkourDebugInfo() {
        Vec3 thresholdPoint = new Vec3(
                src.x + 0.5D + forward.getStepX() * takeoffWindowThreshold() + side.getStepX() * takeoffSideAim(),
                src.y + 0.5D,
                src.z + 0.5D + forward.getStepZ() * takeoffWindowThreshold() + side.getStepZ() * takeoffSideAim()
        );
        return new ParkourDebugInfo(
                List.of(src, src.relative(side)),
                dest,
                runwayBack > 0 ? src.relative(forward, -runwayBack) : null,
                parkourAimPoint(),
                VecUtils.getBlockPosCenter(dest),
                thresholdPoint,
                shouldCommitTakeoffDirection(),
                parkourWindowOpen()
        );
    }
}

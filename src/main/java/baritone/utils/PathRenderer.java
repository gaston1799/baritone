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

import baritone.api.BaritoneAPI;
import baritone.api.event.events.RenderEvent;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalInverted;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.VecUtils;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.PathingBehavior;
import baritone.command.defaults.TempPathCommand;
import baritone.pathing.movement.movements.AscendDebugInfo;
import baritone.pathing.movement.movements.AscendDebugTrace;
import baritone.pathing.movement.movements.ParkourDebugInfo;
import baritone.pathing.movement.movements.ParkourDebuggable;
import baritone.pathing.path.PathExecutor;
import baritone.process.StripmineProcess;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class PathRenderer implements IRenderer {

    private static final ResourceLocation TEXTURE_BEACON_BEAM = ResourceLocation.parse("textures/entity/beacon_beam.png");

    private PathRenderer() {}

    public static double posX() {
        return renderManager.renderPosX();
    }

    public static double posY() {
        return renderManager.renderPosY();
    }

    public static double posZ() {
        return renderManager.renderPosZ();
    }

    public static void render(RenderEvent event, PathingBehavior behavior) {
        final IPlayerContext ctx = behavior.ctx;
        if (ctx.world() == null) {
            return;
        }
        if (ctx.minecraft().screen instanceof GuiClick) {
            ((GuiClick) ctx.minecraft().screen).onRender(event.getModelViewStack(), event.getProjectionMatrix());
        }

        final float partialTicks = event.getPartialTicks();
        final Goal goal = behavior.getGoal();

        final DimensionType thisPlayerDimension = ctx.world().dimensionType();
        final DimensionType currentRenderViewDimension = BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().world().dimensionType();

        if (thisPlayerDimension != currentRenderViewDimension) {
            return;
        }

        if (goal != null && settings.renderGoal.value) {
            drawGoal(event.getModelViewStack(), ctx, goal, partialTicks, settings.colorGoalBox.value);
        }

        renderTempPath(event.getModelViewStack(), ctx);
        if (settings.renderJumpArc.value) {
            renderJumpArc(event.getModelViewStack(), ctx);
        }

        PathExecutor current = behavior.getCurrent();
        PathExecutor next = behavior.getNext();
        if (settings.renderParkourDebug.value && current != null) {
            renderParkourDebug(event.getModelViewStack(), ctx, current);
        }
        if (settings.renderAscendDebug.value || settings.logAscendDebug.value) {
            renderAscendDebug(event.getModelViewStack(), ctx);
        }
        if (settings.renderVelocityDebug.value) {
            renderVelocityDebug(event.getModelViewStack(), ctx);
        }

        if (!settings.renderPath.value) {
            return;
        }
        if (current != null && settings.renderSelectionBoxes.value) {
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toBreak(), settings.colorBlocksToBreak.value);
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toPlace(), settings.colorBlocksToPlace.value);
            drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), current.toWalkInto(), settings.colorBlocksToWalkInto.value);
        }

        if (current != null && current.getPath() != null) {
            int renderBegin = Math.max(current.getPosition() - 3, 0);
            drawPath(event.getModelViewStack(), current.getPath().positions(), renderBegin, settings.colorCurrentPath.value, settings.fadePath.value, 10, 20);
        }

        if (next != null && next.getPath() != null) {
            drawPath(event.getModelViewStack(), next.getPath().positions(), 0, settings.colorNextPath.value, settings.fadePath.value, 10, 20);
        }

        behavior.getInProgress().ifPresent(currentlyRunning -> {
            currentlyRunning.bestPathSoFar().ifPresent(p ->
                    drawPath(event.getModelViewStack(), p.positions(), 0, settings.colorBestPathSoFar.value, settings.fadePath.value, 10, 20)
            );

            currentlyRunning.pathToMostRecentNodeConsidered().ifPresent(mr -> {
                drawPath(event.getModelViewStack(), mr.positions(), 0, settings.colorMostRecentConsidered.value, settings.fadePath.value, 10, 20);
                drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), Collections.singletonList(mr.getDest()), settings.colorMostRecentConsidered.value);
            });
        });

        if (settings.renderSelectionBoxes.value) {
            StripmineProcess stripmineProcess = behavior.baritone.getStripmineProcess();
            if (stripmineProcess.isActive()) {
                Collection<BlockPos> unsafe = stripmineProcess.getUnsafeTargets();
                if (!unsafe.isEmpty()) {
                    drawManySelectionBoxes(event.getModelViewStack(), ctx.player(), unsafe, new Color(0xFF6600));
                }
            }
        }
    }

    private static void renderParkourDebug(PoseStack stack, IPlayerContext ctx, PathExecutor executor) {
        if (executor.getPath() == null) {
            return;
        }
        int position = executor.getPosition();
        if (position < 0 || position >= executor.getPath().movements().size()) {
            return;
        }
        IMovement movement = executor.getPath().movements().get(position);
        if (!(movement instanceof ParkourDebuggable debuggable)) {
            return;
        }
        ParkourDebugInfo debug = debuggable.parkourDebugInfo();
        drawManySelectionBoxes(stack, ctx.player(), debug.takeoffBlocks(), new Color(0xFFB000));
        drawManySelectionBoxes(stack, ctx.player(), Collections.singletonList(debug.dest()), debug.commitActive() ? Color.RED : Color.GREEN);
        if (debug.runwayStart() != null && !debug.takeoffBlocks().contains(debug.runwayStart())) {
            drawManySelectionBoxes(stack, ctx.player(), Collections.singletonList(debug.runwayStart()), new Color(0xCC4444));
        }
        drawDebugMarker(stack, debug.thresholdPoint(), debug.windowOpen() ? Color.GREEN : Color.WHITE);
        drawDebugLine(stack, ctx.playerHead(), debug.aimPoint(), new Color(0xCC33FF));
        drawDebugLine(stack, ctx.playerHead(), debug.landingTarget(), debug.commitActive() ? new Color(0xFF4444) : new Color(0x33D1FF));
    }

    private static void renderAscendDebug(PoseStack stack, IPlayerContext ctx) {
        AscendDebugInfo pending = settings.logAscendDebug.value ? AscendDebugTrace.pollForChat() : null;
        if (pending != null) {
            Helper.HELPER.logDirect("Ascend debug: " + pending.reason() + " (" + pending.src() + " -> " + pending.dest() + ")", false);
        }
        if (!settings.renderAscendDebug.value) {
            return;
        }
        AscendDebugInfo debug = AscendDebugTrace.current();
        if (debug == null) {
            return;
        }
        drawManySelectionBoxes(stack, ctx.player(), Collections.singletonList(debug.src()), new Color(0xFFB000));
        drawManySelectionBoxes(stack, ctx.player(), Collections.singletonList(debug.dest()), new Color(0xFF4444));
        if (!debug.blockers().isEmpty()) {
            drawManySelectionBoxes(stack, ctx.player(), debug.blockers(), new Color(0xAA33FF));
        }
        drawDebugLine(stack, VecUtils.getBlockPosCenter(debug.src()), VecUtils.getBlockPosCenter(debug.dest()), new Color(0xFF6666));
    }

    private static void renderVelocityDebug(PoseStack stack, IPlayerContext ctx) {
        if (ctx.player() == null) {
            return;
        }
        Vec3 velocity = ctx.player().getDeltaMovement();
        if (ctx.player().onGround() && Math.abs(velocity.y) < 0.08D) {
            velocity = new Vec3(velocity.x, 0.0D, velocity.z);
        }
        if (velocity.lengthSqr() < 1.0E-4D) {
            return;
        }
        Vec3 start = new Vec3(ctx.player().position().x, ctx.player().getBoundingBox().minY + 0.05D, ctx.player().position().z);
        Vec3 end = start.add(velocity);
        Color color = new Color(0x33D1FF);
        drawDebugMarker(stack, start, color);
        drawDebugLine(stack, start, end, color);
        drawDebugMarker(stack, end, new Color(0xFFB000));
    }

    private static void drawDebugMarker(PoseStack stack, Vec3 center, Color color) {
        IRenderer.startLines(color, settings.pathRenderLineWidthPixels.value, settings.renderPathIgnoreDepth.value);
        IRenderer.emitAABB(stack, new AABB(
                center.x - 0.12D,
                center.y - 0.12D,
                center.z - 0.12D,
                center.x + 0.12D,
                center.y + 0.12D,
                center.z + 0.12D
        ));
        IRenderer.endLines(settings.renderPathIgnoreDepth.value);
    }

    private static void drawDebugLine(PoseStack stack, Vec3 start, Vec3 end, Color color) {
        IRenderer.startLines(color, settings.pathRenderLineWidthPixels.value, settings.renderPathIgnoreDepth.value);
        IRenderer.emitLine(stack, start, end);
        IRenderer.endLines(settings.renderPathIgnoreDepth.value);
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0) {
        drawPath(stack, positions, startIndex, color, fadeOut, fadeStart0, fadeEnd0, 0.5D);
    }

    public static void drawPath(PoseStack stack, List<BetterBlockPos> positions, int startIndex, Color color, boolean fadeOut, int fadeStart0, int fadeEnd0, double offset) {
        IRenderer.startLines(color, settings.pathRenderLineWidthPixels.value, settings.renderPathIgnoreDepth.value);

        int fadeStart = fadeStart0 + startIndex;
        int fadeEnd = fadeEnd0 + startIndex;

        for (int i = startIndex, next; i < positions.size() - 1; i = next) {
            BetterBlockPos start = positions.get(i);
            BetterBlockPos end = positions.get(next = i + 1);

            int dirX = end.x - start.x;
            int dirY = end.y - start.y;
            int dirZ = end.z - start.z;

            while (next + 1 < positions.size() && (!fadeOut || next + 1 < fadeStart)
                    && dirX == positions.get(next + 1).x - end.x
                    && dirY == positions.get(next + 1).y - end.y
                    && dirZ == positions.get(next + 1).z - end.z) {
                end = positions.get(++next);
            }

            if (fadeOut) {
                float alpha;
                if (i <= fadeStart) {
                    alpha = 0.4F;
                } else {
                    if (i > fadeEnd) {
                        break;
                    }
                    alpha = 0.4F * (1.0F - (float) (i - fadeStart) / (float) (fadeEnd - fadeStart));
                }
                IRenderer.glColor(color, alpha);
            }

            emitPathLine(stack, start.x, start.y, start.z, end.x, end.y, end.z, offset);
        }

        IRenderer.endLines(settings.renderPathIgnoreDepth.value);
    }

    private static void emitPathLine(PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, double offset) {
        final double extraOffset = offset + 0.03D;

        double vpX = posX();
        double vpY = posY();
        double vpZ = posZ();
        boolean renderPathAsThing = !settings.renderPathAsLine.value;

        IRenderer.emitLine(stack,
                x1 + offset - vpX, y1 + offset - vpY, z1 + offset - vpZ,
                x2 + offset - vpX, y2 + offset - vpY, z2 + offset - vpZ
        );
        if (renderPathAsThing) {
            IRenderer.emitLine(stack,
                    x2 + offset - vpX, y2 + offset - vpY, z2 + offset - vpZ,
                    x2 + offset - vpX, y2 + extraOffset - vpY, z2 + offset - vpZ
            );
            IRenderer.emitLine(stack,
                    x2 + offset - vpX, y2 + extraOffset - vpY, z2 + offset - vpZ,
                    x1 + offset - vpX, y1 + extraOffset - vpY, z1 + offset - vpZ
            );
            IRenderer.emitLine(stack,
                    x1 + offset - vpX, y1 + extraOffset - vpY, z1 + offset - vpZ,
                    x1 + offset - vpX, y1 + offset - vpY, z1 + offset - vpZ
            );
        }
    }

    public static void drawManySelectionBoxes(PoseStack stack, Entity player, Collection<? extends BlockPos> positions, Color color) {
        IRenderer.startLines(color, settings.pathRenderLineWidthPixels.value, settings.renderSelectionBoxesIgnoreDepth.value);

        BlockStateInterface bsi = new BlockStateInterface(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext());
        positions.forEach(pos -> {
            BlockState state = bsi.get0(pos);
            VoxelShape shape = state.getShape(player.level(), pos);
            AABB toDraw = shape.isEmpty() ? Shapes.block().bounds() : shape.bounds();
            IRenderer.emitAABB(stack, toDraw.move(pos), .002D);
        });

        IRenderer.endLines(settings.renderSelectionBoxesIgnoreDepth.value);
    }

    public static void drawGoal(PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color) {
        drawGoal(stack, ctx, goal, partialTicks, color, true);
    }

    private static void drawGoal(PoseStack stack, IPlayerContext ctx, Goal goal, float partialTicks, Color color, boolean setupRender) {
        double renderPosX = posX();
        double renderPosY = posY();
        double renderPosZ = posZ();
        double minX;
        double maxX;
        double minZ;
        double maxZ;
        double minY;
        double maxY;
        double y;
        double y1;
        double y2;

        if (!settings.renderGoalAnimated.value) {
            y = 0.999F;
        } else {
            y = Mth.cos((float) (((float) ((System.nanoTime() / 100000L) % 20000L)) / 20000F * Math.PI * 2));
        }
        if (goal instanceof IGoalRenderPos) {
            BlockPos goalPos = ((IGoalRenderPos) goal).getGoalPos();
            minX = goalPos.getX() + 0.002 - renderPosX;
            maxX = goalPos.getX() + 1 - 0.002 - renderPosX;
            minZ = goalPos.getZ() + 0.002 - renderPosZ;
            maxZ = goalPos.getZ() + 1 - 0.002 - renderPosZ;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y /= 2;
            }
            y1 = 1 + y + goalPos.getY() - renderPosY;
            y2 = 1 - y + goalPos.getY() - renderPosY;
            minY = goalPos.getY() - renderPosY;
            maxY = minY + 2;
            if (goal instanceof GoalGetToBlock || goal instanceof GoalTwoBlocks) {
                y1 -= 0.5;
                y2 -= 0.5;
                maxY--;
            }
            drawDankLitGoalBox(stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        } else if (goal instanceof GoalXZ goalXZ) {
            minY = ctx.world().getMinY();
            maxY = ctx.world().getMaxY();

            if (settings.renderGoalXZBeacon.value) {
                stack.pushPose();
                stack.translate(goalXZ.getX() - renderPosX, -renderPosY, goalXZ.getZ() - renderPosZ);
                BeaconRenderer.renderBeaconBeam(
                        stack,
                        ctx.minecraft().renderBuffers().bufferSource(),
                        BeaconRenderer.BEAM_LOCATION,
                        settings.renderGoalAnimated.value ? partialTicks : 0,
                        1.0F,
                        settings.renderGoalAnimated.value ? ctx.world().getGameTime() : 0,
                        (int) minY,
                        (int) maxY,
                        color.getRGB(),
                        0.2F,
                        0.25F
                );
                stack.popPose();
                return;
            }

            minX = goalXZ.getX() + 0.002 - renderPosX;
            maxX = goalXZ.getX() + 1 - 0.002 - renderPosX;
            minZ = goalXZ.getZ() + 0.002 - renderPosZ;
            maxZ = goalXZ.getZ() + 1 - 0.002 - renderPosZ;
            y1 = 0;
            y2 = 0;
            minY -= renderPosY;
            maxY -= renderPosY;
            drawDankLitGoalBox(stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        } else if (goal instanceof GoalComposite goalComposite) {
            boolean batch = Arrays.stream(goalComposite.goals()).allMatch(IGoalRenderPos.class::isInstance);

            if (batch) {
                IRenderer.startLines(color, settings.goalRenderLineWidthPixels.value, settings.renderGoalIgnoreDepth.value);
            }
            for (Goal inner : goalComposite.goals()) {
                drawGoal(stack, ctx, inner, partialTicks, color, !batch);
            }
            if (batch) {
                IRenderer.endLines(settings.renderGoalIgnoreDepth.value);
            }
        } else if (goal instanceof GoalInverted goalInverted) {
            drawGoal(stack, ctx, goalInverted.origin, partialTicks, settings.colorInvertedGoalBox.value);
        } else if (goal instanceof GoalYLevel goalYLevel) {
            minX = ctx.player().position().x - settings.yLevelBoxSize.value - renderPosX;
            minZ = ctx.player().position().z - settings.yLevelBoxSize.value - renderPosZ;
            maxX = ctx.player().position().x + settings.yLevelBoxSize.value - renderPosX;
            maxZ = ctx.player().position().z + settings.yLevelBoxSize.value - renderPosZ;
            minY = goalYLevel.level - renderPosY;
            maxY = minY + 2;
            y1 = 1 + y + goalYLevel.level - renderPosY;
            y2 = 1 - y + goalYLevel.level - renderPosY;
            drawDankLitGoalBox(stack, color, minX, maxX, minZ, maxZ, minY, maxY, y1, y2, setupRender);
        }
    }

    private static void renderTempPath(PoseStack stack, IPlayerContext ctx) {
        BetterBlockPos start = TempPathCommand.tempPathStart;
        BetterBlockPos end = TempPathCommand.tempPathEnd;
        if (start == null || end == null) {
            return;
        }
        IRenderer.startLines(new Color(255, 220, 0), 0.6F, settings.pathRenderLineWidthPixels.value, settings.renderSelectionBoxesIgnoreDepth.value);
        IRenderer.emitLine(stack,
                start.getX() + 0.5D, start.getY() + 1.0D, start.getZ() + 0.5D,
                end.getX() + 0.5D, end.getY() + 1.0D, end.getZ() + 0.5D);
        IRenderer.endLines(settings.renderSelectionBoxesIgnoreDepth.value);
    }

    private static void renderJumpArc(PoseStack stack, IPlayerContext ctx) {
        if (ctx.player() == null) {
            return;
        }
        Vec3 pos = ctx.player().position();
        Vec3 vel = ctx.player().getDeltaMovement();
        double jumpPower = baritone.pathing.movement.MovementHelper.playerJumpPower(ctx);
        double vx = vel.x;
        double vy = ctx.player().onGround() ? jumpPower : vel.y;
        double vz = vel.z;
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed < 0.1D) {
            // barely moving: use a nominal forward speed so the arc still shows
            double yaw = Math.toRadians(ctx.player().getYRot());
            vx = -Math.sin(yaw) * 0.25D;
            vz = Math.cos(yaw) * 0.25D;
        }
        IRenderer.startLines(new Color(0, 255, 200), 0.6F, settings.pathRenderLineWidthPixels.value, settings.renderSelectionBoxesIgnoreDepth.value);
        double px = pos.x, py = pos.y, pz = pos.z;
        double startY = pos.y;
        for (int i = 0; i < 24; i++) {
            double nx = px + vx;
            double ny = py + vy;
            double nz = pz + vz;
            IRenderer.emitLine(stack, px, py, pz, nx, ny, nz);
            px = nx;
            py = ny;
            pz = nz;
            vy -= 0.08D;
            vx *= 0.91D;
            vz *= 0.91D;
            if (py < startY - 0.5D) {
                break;
            }
        }
        IRenderer.endLines(settings.renderSelectionBoxesIgnoreDepth.value);
    }

    private static void drawDankLitGoalBox(PoseStack stack, Color color, double minX, double maxX, double minZ, double maxZ, double minY, double maxY, double y1, double y2, boolean setupRender) {
        if (setupRender) {
            IRenderer.startLines(color, settings.goalRenderLineWidthPixels.value, settings.renderGoalIgnoreDepth.value);
        }

        renderHorizontalQuad(stack, minX, maxX, minZ, maxZ, y1);
        renderHorizontalQuad(stack, minX, maxX, minZ, maxZ, y2);

        for (double y = minY; y < maxY; y += 16) {
            double max = Math.min(maxY, y + 16);
            IRenderer.emitLine(stack, minX, y, minZ, minX, max, minZ, 0.0, 1.0, 0.0);
            IRenderer.emitLine(stack, maxX, y, minZ, maxX, max, minZ, 0.0, 1.0, 0.0);
            IRenderer.emitLine(stack, maxX, y, maxZ, maxX, max, maxZ, 0.0, 1.0, 0.0);
            IRenderer.emitLine(stack, minX, y, maxZ, minX, max, maxZ, 0.0, 1.0, 0.0);
        }

        if (setupRender) {
            IRenderer.endLines(settings.renderGoalIgnoreDepth.value);
        }
    }

    private static void renderHorizontalQuad(PoseStack stack, double minX, double maxX, double minZ, double maxZ, double y) {
        if (y != 0) {
            IRenderer.emitLine(stack, minX, y, minZ, maxX, y, minZ, 1.0, 0.0, 0.0);
            IRenderer.emitLine(stack, maxX, y, minZ, maxX, y, maxZ, 0.0, 0.0, 1.0);
            IRenderer.emitLine(stack, maxX, y, maxZ, minX, y, maxZ, -1.0, 0.0, 0.0);
            IRenderer.emitLine(stack, minX, y, maxZ, minX, y, minZ, 0.0, 0.0, -1.0);
        }
    }
}

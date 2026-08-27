/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.pathing.movement;

import baritone.api.utils.BetterBlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Small deterministic search over Minecraft jump physics. It evaluates the
 * player's live velocity and jump power, then selects the forward/coast/brake
 * sequence whose descending footprint is closest to the target block center.
 */
public final class JumpTrajectorySimulator {

    static final int MAX_TICKS = 30;
    static final double AIR_CONTROL = 0.02D;
    static final double HORIZONTAL_DRAG = 0.91D;
    static final double GRAVITY = 0.08D;
    static final double VERTICAL_DRAG = 0.98D;
    static final double SPRINT_JUMP_IMPULSE = 0.2D;
    private static final double EPSILON = 1.0E-5D;

    private JumpTrajectorySimulator() {}

    public static JumpTrajectory plan(
            Vec3 start,
            Vec3 velocity,
            double jumpPower,
            boolean sprintJump,
            double playerWidth,
            double playerHeight,
            BetterBlockPos target,
            Predicate<AABB> collides
    ) {
        if (jumpPower <= 0.0D) {
            return new JumpTrajectory(
                    target,
                    List.of(start),
                    start,
                    JumpTrajectory.Outcome.NO_JUMP_POWER,
                    new JumpTrajectory.ControlPlan(0, false),
                    Double.POSITIVE_INFINITY
            );
        }

        double targetX = target.getX() + 0.5D;
        double targetZ = target.getZ() + 0.5D;
        double dx = targetX - start.x;
        double dz = targetZ - start.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < EPSILON) {
            dx = 0.0D;
            dz = 1.0D;
            distance = 1.0D;
        }
        double directionX = dx / distance;
        double directionZ = dz / distance;

        JumpTrajectory bestTarget = null;
        JumpTrajectory closestMiss = null;
        for (boolean brakeAfter : new boolean[]{true, false}) {
            for (int forwardTicks = 0; forwardTicks <= 16; forwardTicks++) {
                JumpTrajectory candidate = simulate(
                        start,
                        velocity,
                        jumpPower,
                        sprintJump,
                        playerWidth,
                        playerHeight,
                        target,
                        directionX,
                        directionZ,
                        new JumpTrajectory.ControlPlan(forwardTicks, brakeAfter),
                        collides
                );
                if (candidate.reachesTarget()) {
                    if (bestTarget == null || candidate.landingError() < bestTarget.landingError()) {
                        bestTarget = candidate;
                    }
                } else if (closestMiss == null || candidate.landingError() < closestMiss.landingError()) {
                    closestMiss = candidate;
                }
            }
        }
        return bestTarget != null ? bestTarget : closestMiss;
    }

    /**
     * Simulate a one-block ascent. Until the player's feet clear the top of
     * the destination support, its vertical face pins horizontal movement.
     * This is intentionally different from a gap jump: treating that face as
     * either passable or fatal makes a close-range ascent look like an
     * overshoot even though vanilla collision keeps the player against it.
     */
    public static JumpTrajectory planAscend(
            Vec3 start,
            double forwardSpeed,
            double jumpPower,
            boolean sprintJump,
            double playerWidth,
            double playerHeight,
            BetterBlockPos target,
            Predicate<AABB> collides
    ) {
        if (jumpPower <= 0.0D) {
            return new JumpTrajectory(
                    target,
                    List.of(start),
                    start,
                    JumpTrajectory.Outcome.NO_JUMP_POWER,
                    new JumpTrajectory.ControlPlan(MAX_TICKS, false),
                    Double.POSITIVE_INFINITY
            );
        }

        double targetCenterX = target.getX() + 0.5D;
        double targetCenterZ = target.getZ() + 0.5D;
        double dx = targetCenterX - start.x;
        double dz = targetCenterZ - start.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < EPSILON) {
            return new JumpTrajectory(
                    target,
                    List.of(start),
                    start,
                    JumpTrajectory.Outcome.TARGET,
                    new JumpTrajectory.ControlPlan(MAX_TICKS, false),
                    0.0D
            );
        }
        double directionX = dx / distance;
        double directionZ = dz / distance;
        double halfWidth = playerWidth * 0.5D;
        // Pin the box's front edge at the step face, measured along the movement
        // axis. Using the full euclidean distance lets a small lateral offset
        // push the pin fractionally past the face, so the box clips the step
        // column by an epsilon and the collision check reports BLOCKED - which
        // is why the takeoff only ever cleared once the player was pressed
        // against the wall (flat=0.8) instead of ~1 block before it.
        double axisDistance = Math.max(Math.abs(dx), Math.abs(dz));
        double supportFaceProgress = Math.max(0.0D, axisDistance - 0.5D - halfWidth - EPSILON);
        double speed = Math.max(0.0D, forwardSpeed) + (sprintJump ? SPRINT_JUMP_IMPULSE : 0.0D);
        double progress = 0.0D;
        double py = start.y;
        double vy = jumpPower;
        List<Vec3> samples = new ArrayList<>();
        samples.add(start);

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            speed += AIR_CONTROL;
            double nextProgress = progress + speed;
            double nextY = py + vy;

            // The step face cancels forward velocity until the player's feet
            // are high enough to pass over the support block.
            if (nextY < target.getY() && nextProgress > supportFaceProgress) {
                nextProgress = supportFaceProgress;
                speed = 0.0D;
            }

            progress = nextProgress;
            py = nextY;
            double px = start.x + directionX * progress;
            double pz = start.z + directionZ * progress;
            Vec3 sample = new Vec3(px, py, pz);
            samples.add(sample);

            AABB body = boxAt(px, py, pz, halfWidth, playerHeight);
            if (collides.test(body)) {
                return result(
                        target,
                        samples,
                        sample,
                        JumpTrajectory.Outcome.BLOCKED,
                        new JumpTrajectory.ControlPlan(MAX_TICKS, false),
                        horizontalError(px, pz, targetCenterX, targetCenterZ) + 4.0D
                );
            }

            boolean footprintTouchesTarget = px + halfWidth > target.getX() + 0.05D
                    && px - halfWidth < target.getX() + 0.95D
                    && pz + halfWidth > target.getZ() + 0.05D
                    && pz - halfWidth < target.getZ() + 0.95D;
            if (py >= target.getY() && footprintTouchesTarget) {
                return result(
                        target,
                        samples,
                        sample,
                        JumpTrajectory.Outcome.TARGET,
                        new JumpTrajectory.ControlPlan(MAX_TICKS, false),
                        horizontalError(px, pz, targetCenterX, targetCenterZ)
                );
            }

            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            speed *= HORIZONTAL_DRAG;
            if (vy < 0.0D && py < target.getY()) {
                JumpTrajectory.Outcome outcome = progress < supportFaceProgress
                        ? JumpTrajectory.Outcome.SHORT
                        : JumpTrajectory.Outcome.OVERSHOOT;
                return result(
                        target,
                        samples,
                        sample,
                        outcome,
                        new JumpTrajectory.ControlPlan(MAX_TICKS, false),
                        horizontalError(px, pz, targetCenterX, targetCenterZ)
                );
            }
        }

        Vec3 finalSample = samples.get(samples.size() - 1);
        return result(
                target,
                samples,
                finalSample,
                JumpTrajectory.Outcome.SHORT,
                new JumpTrajectory.ControlPlan(MAX_TICKS, false),
                horizontalError(finalSample.x, finalSample.z, targetCenterX, targetCenterZ)
        );
    }

    static JumpTrajectory simulate(
            Vec3 start,
            Vec3 velocity,
            double jumpPower,
            boolean sprintJump,
            double playerWidth,
            double playerHeight,
            BetterBlockPos target,
            double directionX,
            double directionZ,
            JumpTrajectory.ControlPlan controls,
            Predicate<AABB> collides
    ) {
        List<Vec3> samples = new ArrayList<>();
        samples.add(start);

        double px = start.x;
        double py = start.y;
        double pz = start.z;
        double vx = velocity.x;
        double vz = velocity.z;
        if (sprintJump) {
            vx += directionX * SPRINT_JUMP_IMPULSE;
            vz += directionZ * SPRINT_JUMP_IMPULSE;
        }
        double vy = jumpPower;
        double halfWidth = playerWidth * 0.5D;
        double targetCenterX = target.getX() + 0.5D;
        double targetCenterZ = target.getZ() + 0.5D;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            JumpTrajectory.Control control = controls.atTick(tick);
            double controlAmount = control == JumpTrajectory.Control.FORWARD ? AIR_CONTROL
                    : control == JumpTrajectory.Control.BACK ? -AIR_CONTROL
                    : 0.0D;
            vx += directionX * controlAmount;
            vz += directionZ * controlAmount;

            double previousX = px;
            double previousY = py;
            double previousZ = pz;
            px += vx;
            py += vy;
            pz += vz;

            if (vy <= 0.0D && previousY >= target.getY() && py <= target.getY()) {
                double denominator = previousY - py;
                double fraction = denominator < EPSILON ? 0.0D : (previousY - target.getY()) / denominator;
                double landingX = previousX + (px - previousX) * fraction;
                double landingZ = previousZ + (pz - previousZ) * fraction;
                Vec3 landing = new Vec3(landingX, target.getY(), landingZ);
                samples.add(landing);
                double error = horizontalError(landingX, landingZ, targetCenterX, targetCenterZ);
                boolean footprintTouchesTarget = landingX + halfWidth > target.getX() + 0.05D
                        && landingX - halfWidth < target.getX() + 0.95D
                        && landingZ + halfWidth > target.getZ() + 0.05D
                        && landingZ - halfWidth < target.getZ() + 0.95D;
                AABB landingBox = boxAt(landingX, target.getY(), landingZ, halfWidth, playerHeight);
                if (footprintTouchesTarget && !collides.test(landingBox)) {
                    return result(target, samples, landing, JumpTrajectory.Outcome.TARGET, controls, error);
                }
                double progress = (landingX - targetCenterX) * directionX + (landingZ - targetCenterZ) * directionZ;
                JumpTrajectory.Outcome outcome = progress < 0.0D
                        ? JumpTrajectory.Outcome.SHORT
                        : JumpTrajectory.Outcome.OVERSHOOT;
                return result(target, samples, landing, outcome, controls, error);
            }

            Vec3 sample = new Vec3(px, py, pz);
            samples.add(sample);
            AABB body = boxAt(px, py, pz, halfWidth, playerHeight);
            if (collides.test(body)) {
                double error = horizontalError(px, pz, targetCenterX, targetCenterZ);
                return result(target, samples, sample, JumpTrajectory.Outcome.BLOCKED, controls, error + 4.0D);
            }

            vy = (vy - GRAVITY) * VERTICAL_DRAG;
            vx *= HORIZONTAL_DRAG;
            vz *= HORIZONTAL_DRAG;
        }

        Vec3 landing = samples.get(samples.size() - 1);
        double error = horizontalError(landing.x, landing.z, targetCenterX, targetCenterZ);
        double progress = (landing.x - targetCenterX) * directionX + (landing.z - targetCenterZ) * directionZ;
        JumpTrajectory.Outcome outcome = progress < 0.0D
                ? JumpTrajectory.Outcome.SHORT
                : JumpTrajectory.Outcome.OVERSHOOT;
        return result(target, samples, landing, outcome, controls, error);
    }

    private static JumpTrajectory result(
            BetterBlockPos target,
            List<Vec3> samples,
            Vec3 landing,
            JumpTrajectory.Outcome outcome,
            JumpTrajectory.ControlPlan controls,
            double error
    ) {
        return new JumpTrajectory(target, samples, landing, outcome, controls, error);
    }

    private static AABB boxAt(double x, double y, double z, double halfWidth, double height) {
        return new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
    }

    private static double horizontalError(double x, double z, double targetX, double targetZ) {
        double dx = x - targetX;
        double dz = z - targetZ;
        return dx * dx + dz * dz;
    }
}

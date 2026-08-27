/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.utils.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Small receding-horizon planner for ground melee movement. It deliberately
 * owns only deterministic, tick-sensitive input selection; target selection,
 * pathing and weapon policy remain in their existing systems.
 */
public final class CombatMovementPlanner {

    private static final double GROUND_ACCELERATION = 0.1D;
    private static final double GROUND_DRAG = 0.91D;

    private CombatMovementPlanner() {
    }

    public static Decision choose(State state, Predicate<Candidate> safe) {
        int horizon = Math.max(2, Math.min(20, state.horizonTicks()));
        double initialDistance = Math.hypot(state.targetX() - state.playerX(), state.targetZ() - state.playerZ());
        List<Candidate> evaluated = new ArrayList<>();
        for (Move move : Move.values()) {
            if (!state.allowStrafe() && move.strafe() != 0) {
                continue;
            }
            double playerX = state.playerX();
            double playerZ = state.playerZ();
            double playerVelocityX = state.playerVelocityX();
            double playerVelocityZ = state.playerVelocityZ();
            double targetX = state.targetX();
            double targetZ = state.targetZ();
            double targetVelocityX = state.targetVelocityX();
            double targetVelocityZ = state.targetVelocityZ();
            List<Point> path = new ArrayList<>(horizon + 1);
            path.add(new Point(playerX, playerZ));

            for (int tick = 0; tick < horizon; tick++) {
                // The live look controller keeps turning toward the target, so
                // forward/strafe rotate every tick. Rebuilding the basis here
                // predicts an orbit instead of an incorrect world-space line.
                double toTargetX = targetX - playerX;
                double toTargetZ = targetZ - playerZ;
                double magnitude = Math.hypot(toTargetX, toTargetZ);
                double forwardX = magnitude < 1.0E-6D ? 0.0D : toTargetX / magnitude;
                double forwardZ = magnitude < 1.0E-6D ? 1.0D : toTargetZ / magnitude;
                double rightX = -forwardZ;
                double rightZ = forwardX;
                double inputX = (forwardX * move.forward()) + (rightX * move.strafe());
                double inputZ = (forwardZ * move.forward()) + (rightZ * move.strafe());
                double inputLength = Math.hypot(inputX, inputZ);
                if (inputLength > 1.0D) {
                    inputX /= inputLength;
                    inputZ /= inputLength;
                }
                playerVelocityX = (playerVelocityX + inputX * GROUND_ACCELERATION) * GROUND_DRAG;
                playerVelocityZ = (playerVelocityZ + inputZ * GROUND_ACCELERATION) * GROUND_DRAG;
                playerX += playerVelocityX;
                playerZ += playerVelocityZ;
                targetX += targetVelocityX;
                targetZ += targetVelocityZ;
                targetVelocityX *= GROUND_DRAG;
                targetVelocityZ *= GROUND_DRAG;
                path.add(new Point(playerX, playerZ));
            }

            double distance = Math.hypot(targetX - playerX, targetZ - playerZ);
            double desired = state.attackReady() ? state.attackDistance() : state.cooldownDistance();
            double score = Math.abs(distance - desired);
            if (state.attackReady() && distance > SelfDefenceHelper.MELEE_REACH - 0.15D) {
                score += (distance - (SelfDefenceHelper.MELEE_REACH - 0.15D)) * 4.0D;
            }
            if (!state.attackReady() && distance < desired) {
                score += (desired - distance) * 3.0D;
            }
            if (distance < 1.15D) {
                score += (1.15D - distance) * 8.0D;
            }
            boolean inOrbitBand = Math.abs(initialDistance - desired) < 0.65D;
            if (state.allowStrafe() && move.strafe() != 0 && inOrbitBand) {
                // Evasion has value beyond pure spacing: a stationary optimum
                // is easy for mobs and players to hit. Receding-horizon replans
                // pull the next tick back toward the band if this orbit drifts.
                score -= move.forward() == 0 ? 1.75D : 1.0D;
            }
            if (move == state.previousMove()) {
                score -= 0.25D;
            }
            if (move == Move.HOLD) {
                score += inOrbitBand && state.allowStrafe() ? 0.5D : 0.03D;
            }

            Candidate provisional = new Candidate(move, playerX, playerZ, targetX, targetZ, distance, score, true, path);
            boolean isSafe = safe == null || safe.test(provisional);
            evaluated.add(new Candidate(move, playerX, playerZ, targetX, targetZ, distance,
                    isSafe ? score : Double.POSITIVE_INFINITY, isSafe, path));
        }

        Candidate selected = evaluated.stream()
                .filter(Candidate::safe)
                .min(Comparator.comparingDouble(Candidate::score).thenComparing(candidate -> candidate.move().ordinal()))
                .orElseGet(() -> evaluated.stream()
                        .filter(candidate -> candidate.move() == Move.HOLD)
                        .findFirst()
                        .orElse(null));
        return new Decision(selected == null ? Move.HOLD : selected.move(), selected,
                Collections.unmodifiableList(evaluated));
    }

    public enum Move {
        HOLD(0, 0),
        FORWARD(1, 0),
        BACK(-1, 0),
        LEFT(0, -1),
        RIGHT(0, 1),
        FORWARD_LEFT(1, -1),
        FORWARD_RIGHT(1, 1),
        BACK_LEFT(-1, -1),
        BACK_RIGHT(-1, 1);

        private final int forward;
        private final int strafe;

        Move(int forward, int strafe) {
            this.forward = forward;
            this.strafe = strafe;
        }

        public int forward() {
            return forward;
        }

        public int strafe() {
            return strafe;
        }
    }

    public record State(
            double playerX,
            double playerZ,
            double playerVelocityX,
            double playerVelocityZ,
            double targetX,
            double targetZ,
            double targetVelocityX,
            double targetVelocityZ,
            boolean attackReady,
            boolean allowStrafe,
            double attackDistance,
            double cooldownDistance,
            int horizonTicks,
            Move previousMove
    ) {
    }

    public record Point(double x, double z) {
    }

    public record Candidate(
            Move move,
            double endX,
            double endZ,
            double predictedTargetX,
            double predictedTargetZ,
            double distance,
            double score,
            boolean safe,
            List<Point> path
    ) {
    }

    public record Decision(Move move, Candidate selected, List<Candidate> candidates) {
    }
}

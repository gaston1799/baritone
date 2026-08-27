/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.pathing.movement;

/**
 * Thread-safe handoff from movement execution to the render thread.
 */
public final class JumpTrajectoryTrace {

    private static final long MAX_AGE_MILLIS = 750L;
    private static volatile JumpTrajectory latest;
    private static volatile long updatedAtMillis;

    private JumpTrajectoryTrace() {}

    public static void publish(JumpTrajectory trajectory) {
        latest = trajectory;
        updatedAtMillis = System.currentTimeMillis();
    }

    public static JumpTrajectory current() {
        JumpTrajectory trajectory = latest;
        if (trajectory == null || System.currentTimeMillis() - updatedAtMillis > MAX_AGE_MILLIS) {
            return null;
        }
        return trajectory;
    }

    public static void clear() {
        latest = null;
        updatedAtMillis = 0L;
    }
}

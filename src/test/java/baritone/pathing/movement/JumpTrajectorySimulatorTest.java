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
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JumpTrajectorySimulatorTest {

    @Test
    public void sprintForecastSelectsAirBrakeToCenterThreeBlockLanding() {
        JumpTrajectory result = JumpTrajectorySimulator.plan(
                new Vec3(1.0D, 0.0D, 0.5D),
                new Vec3(0.28D, 0.0D, 0.0D),
                0.42D,
                true,
                0.6D,
                1.8D,
                new BetterBlockPos(3, 0, 0),
                box -> false
        );

        assertTrue(result.reachesTarget());
        assertTrue(result.controls().brakeAfter());
        assertTrue(result.landing().x >= 3.0D && result.landing().x <= 4.0D);
        assertEquals(0.5D, result.landing().z, 1.0E-6D);
    }

    @Test
    public void liveSpeedLimitsAnUnboostedForecast() {
        JumpTrajectory result = JumpTrajectorySimulator.plan(
                new Vec3(0.5D, 0.0D, 0.5D),
                Vec3.ZERO,
                0.42D,
                false,
                0.6D,
                1.8D,
                new BetterBlockPos(4, 0, 0),
                box -> false
        );

        assertEquals(JumpTrajectory.Outcome.SHORT, result.outcome());
    }

    @Test
    public void collisionRejectsOtherwiseReachableTrajectory() {
        JumpTrajectory result = JumpTrajectorySimulator.plan(
                new Vec3(1.0D, 0.0D, 0.5D),
                new Vec3(0.28D, 0.0D, 0.0D),
                0.42D,
                true,
                0.6D,
                1.8D,
                new BetterBlockPos(3, 0, 0),
                box -> box.maxX > 2.25D
        );

        assertEquals(JumpTrajectory.Outcome.BLOCKED, result.outcome());
    }

    @Test
    public void closeAscendPinsAgainstSupportUntilFeetClearTop() {
        JumpTrajectory result = JumpTrajectorySimulator.planAscend(
                new Vec3(0.7D, 0.0D, 0.5D),
                0.086D,
                0.42D,
                true,
                0.6D,
                1.8D,
                new BetterBlockPos(1, 1, 0),
                box -> box.maxX > 1.000001D && box.minY < 1.0D
        );

        assertTrue(result.reachesTarget());
        assertEquals(0.7D, result.samples().get(1).x, 1.0E-6D);
        assertEquals(0.7D, result.samples().get(2).x, 1.0E-6D);
        assertTrue(result.landing().y >= 1.0D);
    }

    @Test
    public void ascentStillRejectsARealLowCeiling() {
        JumpTrajectory result = JumpTrajectorySimulator.planAscend(
                new Vec3(0.7D, 0.0D, 0.5D),
                0.086D,
                0.42D,
                false,
                0.6D,
                1.8D,
                new BetterBlockPos(1, 1, 0),
                box -> box.maxY > 2.0D
        );

        assertEquals(JumpTrajectory.Outcome.BLOCKED, result.outcome());
    }
}

/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.utils.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CombatMovementPlannerTest {

    @Test
    public void closesDistanceWhenWeaponIsReady() {
        CombatMovementPlanner.Decision decision = CombatMovementPlanner.choose(
                state(0.0D, 0.0D, 4.0D, 0.0D, true, CombatMovementPlanner.Move.HOLD),
                candidate -> true
        );

        assertTrue(decision.move().forward() > 0);
        assertTrue(decision.selected().distance() < 4.0D);
    }

    @Test
    public void retreatsWhileCoolingDownAndTooClose() {
        CombatMovementPlanner.Decision decision = CombatMovementPlanner.choose(
                state(0.0D, 0.0D, 1.5D, 0.0D, false, CombatMovementPlanner.Move.HOLD),
                candidate -> true
        );

        assertTrue(decision.move().forward() < 0);
        assertTrue(decision.selected().distance() > 1.5D);
    }

    @Test
    public void rejectsUnsafeBestCandidate() {
        CombatMovementPlanner.Decision decision = CombatMovementPlanner.choose(
                state(0.0D, 0.0D, 4.0D, 0.0D, true, CombatMovementPlanner.Move.HOLD),
                candidate -> candidate.move().forward() <= 0
        );

        assertTrue(decision.selected().safe());
        assertTrue(decision.move().forward() <= 0);
        assertNotEquals(CombatMovementPlanner.Move.FORWARD, decision.move());
    }

    @Test
    public void previousMoveHysteresisKeepsStableStrafeAtGoodSpacing() {
        CombatMovementPlanner.Decision decision = CombatMovementPlanner.choose(
                state(0.0D, 0.0D, 2.35D, 0.0D, true, CombatMovementPlanner.Move.LEFT),
                candidate -> true
        );

        assertEquals(decision.toString(), CombatMovementPlanner.Move.LEFT, decision.move());
    }

    private static CombatMovementPlanner.State state(
            double playerX,
            double playerZ,
            double targetX,
            double targetZ,
            boolean ready,
            CombatMovementPlanner.Move previous
    ) {
        return new CombatMovementPlanner.State(
                playerX, playerZ, 0.0D, 0.0D,
                targetX, targetZ, 0.0D, 0.0D,
                ready, true, 2.35D, 3.1D, 8, previous
        );
    }
}

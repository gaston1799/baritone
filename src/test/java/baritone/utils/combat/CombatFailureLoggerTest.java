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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CombatFailureLoggerTest {

    @Test
    public void failureFreezesOnlyBoundedRecentFrames() {
        CombatFailureLogger logger = new CombatFailureLogger(20, 2);
        for (int tick = 0; tick < 30; tick++) {
            logger.capture(frame(tick, "move"));
        }

        CombatFailureLogger.Failure failure = logger.record("MISS", "test");

        assertEquals(20, failure.frames().size());
        assertEquals(10, failure.frames().get(0).tick());
        assertEquals(29, failure.frames().get(19).tick());
    }

    @Test
    public void jsonContainsStructuredReasonAndFrames() {
        CombatFailureLogger logger = new CombatFailureLogger(20, 2);
        logger.capture(frame(7, "attack:sword\"jump"));
        String json = CombatFailureLogger.toJson(logger.record("ATTACK_MISSED", "no damage",
                "horizon=8 attackDist=2.35", java.util.List.of(
                        new CombatFailureLogger.CandidateInfo("hold", 2.3D, true, null),
                        new CombatFailureLogger.CandidateInfo("forward", 1.1D, false, "collision at sample 3"))));

        assertTrue(json.contains("\"reason\":\"ATTACK_MISSED\""));
        assertTrue(json.contains("\"context\":\"horizon=8 attackDist=2.35\""));
        assertTrue(json.contains("\"candidates\":[{"));
        assertTrue(json.contains("\"move\":\"forward\""));
        assertTrue(json.contains("\"reason\":\"collision at sample 3\""));
        assertTrue(json.contains("\"frames\":[{"));
        assertTrue(json.contains("attack:sword\\\"jump"));
    }

    @Test
    public void clearingFailuresPreservesUsability() {
        CombatFailureLogger logger = new CombatFailureLogger(20, 1);
        logger.capture(frame(1, "hold"));
        logger.record("ONE", "first");
        logger.clearFailures();

        assertFalse(logger.latest().isPresent());
        logger.record("TWO", "second");
        assertEquals("TWO", logger.latest().orElseThrow().reason());
    }

    private static CombatFailureLogger.Frame frame(int tick, String action) {
        return new CombatFailureLogger.Frame(
                tick, action,
                tick, 64.0D, 0.0D,
                0.1D, 0.0D, 0.0D,
                2.0D, 64.0D, 0.0D,
                0.0D, 0.0D, 0.0D,
                2.0D, 20.0D, 1.0D,
                0.0D, 0.0D, 0.0D,
                true, false,
                true, true, 20.0D
        );
    }
}

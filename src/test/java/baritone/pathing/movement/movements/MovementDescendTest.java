/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.pathing.movement.movements;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MovementDescendTest {

    @Test
    public void groundedOnUpperLipNeedsEdgeClearance() {
        assertTrue(MovementDescend.needsEdgeClearance(20, 19, true));
        assertFalse(MovementDescend.shouldAcceptOvershoot(20, 19, true, true, 0.0D));
    }

    @Test
    public void airbornePositionAboveDestinationDoesNotUseGroundedEdgeRecovery() {
        assertFalse(MovementDescend.needsEdgeClearance(20, 19, false));
        assertFalse(MovementDescend.shouldAcceptOvershoot(20, 19, true, false, -0.2D));
    }

    @Test
    public void groundedLandingAtDestinationCanCompleteOvershoot() {
        assertFalse(MovementDescend.needsEdgeClearance(19, 19, true));
        assertTrue(MovementDescend.shouldAcceptOvershoot(19, 19, true, true, 0.0D));
    }

    @Test
    public void fastDownwardMotionDoesNotCompleteOvershoot() {
        assertFalse(MovementDescend.shouldAcceptOvershoot(19, 19, true, true, -0.6D));
    }
}

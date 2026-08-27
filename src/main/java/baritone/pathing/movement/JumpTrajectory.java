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

import java.util.List;

/**
 * One deterministic jump forecast. The same object drives input commitment,
 * diagnostics, and rendering so the visible arc is the trajectory Baritone
 * actually selected.
 */
public record JumpTrajectory(
        BetterBlockPos target,
        List<Vec3> samples,
        Vec3 landing,
        Outcome outcome,
        ControlPlan controls,
        double landingError
) {

    public JumpTrajectory {
        samples = List.copyOf(samples);
    }

    public boolean reachesTarget() {
        return outcome == Outcome.TARGET;
    }

    public enum Outcome {
        TARGET,
        SHORT,
        OVERSHOOT,
        BLOCKED,
        NO_JUMP_POWER
    }

    public enum Control {
        FORWARD,
        COAST,
        BACK
    }

    /**
     * Hold forward for {@code forwardTicks}, then either coast or air-brake.
     * This compact plan is stable across render frames and movement ticks.
     */
    public record ControlPlan(int forwardTicks, boolean brakeAfter) {

        public Control atTick(int tick) {
            if (tick < forwardTicks) {
                return Control.FORWARD;
            }
            return brakeAfter ? Control.BACK : Control.COAST;
        }
    }
}

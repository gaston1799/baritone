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

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Map;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;
    private boolean sprintKeyForcedByBaritone;

    public InputOverrideHandler(Baritone baritone) {
        super(baritone);
        this.blockBreakHelper = new BlockBreakHelper(baritone.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(baritone.getPlayerContext());
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        return input == null ? false : this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        boolean previous = this.inputForceStateMap.getOrDefault(input, false);
        this.inputForceStateMap.put(input, forced);
        if ((input == Input.CLICK_RIGHT || input == Input.CLICK_LEFT) && previous != forced) {
            CorrectionLogger.logAlways("input-set input=" + input + " forced=" + forced
                    + " caller=" + inputCaller());
        }
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        boolean right = isInputForcedDown(Input.CLICK_RIGHT);
        boolean left = isInputForcedDown(Input.CLICK_LEFT);
        if (right || left) {
            CorrectionLogger.logAlways("input-clear right=" + right + " left=" + left
                    + " caller=" + inputCaller());
        }
        this.inputForceStateMap.clear();
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (MovementHelper.isConsumingItem(ctx)) {
            setInputForceState(Input.CLICK_LEFT, false);
        }
        if (isInputForcedDown(Input.CLICK_LEFT)) {
            if (isInputForcedDown(Input.CLICK_RIGHT)) {
                CorrectionLogger.logAlways("input-gate rightSuppressedByLeft=true");
            }
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        CorrectionLogger.logAlways("input-gates beforeRight=" + isInputForcedDown(Input.CLICK_RIGHT)
                + " left=" + isInputForcedDown(Input.CLICK_LEFT)
                + " sneak=" + isInputForcedDown(Input.SNEAK)
                + " consuming=" + MovementHelper.isConsumingItem(ctx));
        blockBreakHelper.tick(isInputForcedDown(Input.CLICK_LEFT));
        blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT));
        syncSprintKey();

        if (inControl()) {
            if (ctx.player().input.getClass() != PlayerMovementInput.class) {
                ctx.player().input = new PlayerMovementInput(this);
            }
        } else {
            if (ctx.player().input.getClass() == PlayerMovementInput.class) { // allow other movement inputs that aren't this one, e.g. for a freecam
                ctx.player().input = new KeyboardInput(ctx.minecraft().options);
            }
        }
        // only set it if it was previously incorrect
        // gotta do it this way, or else it constantly thinks you're beginning a double tap W sprint lol
    }

    private void syncSprintKey() {
        boolean forced = isInputForcedDown(Input.SPRINT);
        if (forced || sprintKeyForcedByBaritone) {
            ctx.minecraft().options.keySprint.setDown(forced);
            sprintKeyForcedByBaritone = forced;
        }
    }

    private boolean inControl() {
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP, Input.SPRINT}) {
            if (isInputForcedDown(input)) {
                return true;
            }
        }
        // if we are not primary (a bot) we should set the movementinput even when idle (not pathing)
        return baritone.getPathingBehavior().isPathing() || baritone != BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }

    /**
     * Executes one placement attempt immediately. This is used by the builder
     * after it has already validated a live block hit; waiting for the next
     * input tick can lose the one-tick click request when another behavior
     * clears the override map first.
     */
    public void attemptPlacementNow() {
        blockPlaceHelper.tick(true);
    }

    public void attemptPlacementNow(BlockHitResult validatedHit) {
        blockPlaceHelper.tick(true, validatedHit);
    }

    private String inputCaller() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (!element.getClassName().equals(InputOverrideHandler.class.getName())
                    && !element.getClassName().equals(Thread.class.getName())) {
                return element.getClassName() + "." + element.getMethodName() + ":" + element.getLineNumber();
            }
        }
        return "unknown";
    }
}

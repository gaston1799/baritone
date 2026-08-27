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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.behavior.SelfDefenceBehavior;
import baritone.utils.BaritoneProcessHelper;

public final class SelfDefenceProcess extends BaritoneProcessHelper {

    private Goal lastSentGoal;

    public SelfDefenceProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return baritone.getSelfDefenceBehavior().isDefending();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        SelfDefenceBehavior behaviour = baritone.getSelfDefenceBehavior();
        if (!behaviour.isDefending()) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (!isSafeToCancel || behaviour.shouldPauseForCombat()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        Goal chaseGoal = behaviour.chaseGoal();
        if (chaseGoal != null) {
            if (chaseGoal.equals(lastSentGoal)) {
                // Goal unchanged since last request: keep the running path
                // executor alive. Revalidating every tick would cancel+replan
                // for a moving target (GoalNear differs each tick), so the bot
                // would never actually walk. The behavior refreshes the goal
                // every 15 ticks, which triggers a fresh REVALIDATE here.
                return new PathingCommand(chaseGoal, PathingCommandType.SET_GOAL_AND_PATH);
            }
            lastSentGoal = chaseGoal;
            return new PathingCommand(chaseGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public void onLostControl() {
        lastSentGoal = null;
    }

    @Override
    public String displayName0() {
        return baritone.getSelfDefenceBehavior().getCurrentTarget() == null
                ? "self defence"
                : "self defence vs " + baritone.getSelfDefenceBehavior().getCurrentTarget().getName().getString();
    }

    @Override
    public double priority() {
        return 6.0D;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }
}

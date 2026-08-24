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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Temporary path markers: #tps sets the start, #tpe sets the end (your feet,
 * or the block you're looking at) and paths to it. The markers are also
 * rendered as a line by PathRenderer so you can see the temp path corridor.
 */
public class TempPathCommand extends Command {

    public static volatile BetterBlockPos tempPathStart;
    public static volatile BetterBlockPos tempPathEnd;

    public TempPathCommand(IBaritone baritone) {
        super(baritone, "tps", "tpe");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        if (label.equalsIgnoreCase("tps")) {
            tempPathStart = ctx.playerFeet();
            logDirect("Temp path start: " + tempPathStart);
            return;
        }
        BetterBlockPos end = ctx.playerFeet();
        HitResult hit = ctx.objectMouseOver();
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos target = ((BlockHitResult) hit).getBlockPos();
            if (target != null) {
                end = BetterBlockPos.from(target);
            }
        }
        tempPathEnd = end;
        if (tempPathStart == null) {
            tempPathStart = ctx.playerFeet();
        }
        baritone.getCustomGoalProcess().setGoal(new GoalBlock(end));
        // path() flips the goal process to PATH_REQUESTED so the planner actually
        // computes a route (setGoal alone only stores the goal, nothing renders)
        baritone.getCustomGoalProcess().path();
        logDirect("Temp path: " + tempPathStart + " -> " + end + " (pathing)");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Set temp path start/end and follow it";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Creates a temporary path between two markers and paths to the end",
                "",
                "Usage:",
                "> tps - set the start marker at your position",
                "> tpe - set the end marker (your feet, or the block you're looking at) and path to it"
        );
    }
}

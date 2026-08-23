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

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalStrictDirection;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public class TunnelCommand extends Command {

    public TunnelCommand(IBaritone baritone) {
        super(baritone, "tunnel");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(3);
        if (args.hasExactly(3)) {
            boolean cont = true;
            int height = Integer.parseInt(args.getArgs().get(0).getValue());
            int width = Integer.parseInt(args.getArgs().get(1).getValue());
            int depth = Integer.parseInt(args.getArgs().get(2).getValue());
            int minHeight = Math.max(1, Baritone.settings().playerHeight.value) == 1 ? 1 : 2;

            if (width < 1 || height < minHeight || depth < 1 || height > ctx.world().getMaxY()) {
                logDirect(String.format(
                        "Width and depth must be at least 1 block; Height must be at least %d block%s, and cannot be greater than the build limit.%s",
                        minHeight,
                        minHeight == 1 ? "" : "s",
                        minHeight == 1 ? "" : " Height 1 requires playerHeight 1."
                ));
                cont = false;
            }

            if (cont) {
                height--;
                width--;
                BlockPos corner1;
                BlockPos corner2;
                Direction enumFacing = ctx.player().getDirection();
                int addition = ((width % 2 == 0) ? 0 : 1);
                switch (enumFacing) {
                    case EAST:
                        corner1 = new BlockPos(ctx.playerFeet().x, ctx.playerFeet().y, ctx.playerFeet().z - width / 2);
                        corner2 = new BlockPos(ctx.playerFeet().x + depth, ctx.playerFeet().y + height, ctx.playerFeet().z + width / 2 + addition);
                        break;
                    case WEST:
                        corner1 = new BlockPos(ctx.playerFeet().x, ctx.playerFeet().y, ctx.playerFeet().z + width / 2 + addition);
                        corner2 = new BlockPos(ctx.playerFeet().x - depth, ctx.playerFeet().y + height, ctx.playerFeet().z - width / 2);
                        break;
                    case NORTH:
                        corner1 = new BlockPos(ctx.playerFeet().x - width / 2, ctx.playerFeet().y, ctx.playerFeet().z);
                        corner2 = new BlockPos(ctx.playerFeet().x + width / 2 + addition, ctx.playerFeet().y + height, ctx.playerFeet().z - depth);
                        break;
                    case SOUTH:
                        corner1 = new BlockPos(ctx.playerFeet().x + width / 2 + addition, ctx.playerFeet().y, ctx.playerFeet().z);
                        corner2 = new BlockPos(ctx.playerFeet().x - width / 2, ctx.playerFeet().y + height, ctx.playerFeet().z + depth);
                        break;
                    default:
                        throw new IllegalStateException("Unexpected value: " + enumFacing);
                }
                logDirect(String.format("Creating a tunnel %s block(s) high, %s block(s) wide, and %s block(s) deep", height + 1, width + 1, depth));
                baritone.getBuilderProcess().clearArea(corner1, corner2);
            }
        } else {
            Goal goal = new GoalStrictDirection(
                    ctx.playerFeet(),
                    ctx.player().getDirection()
            );
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            logDirect(String.format("Goal: %s", goal.toString()));
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Set a goal to tunnel in your current direction";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The tunnel command sets a goal that tells Baritone to mine completely straight in the direction that you're facing.",
                "",
                "Usage:",
                "> tunnel - No arguments, mines in a 1x2 radius.",
                "> tunnel <height> <width> <depth> - Tunnels in a user defined height, width and depth. Height 1 requires playerHeight 1."
        );
    }
}

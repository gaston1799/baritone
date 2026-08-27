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
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.Direction;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class BranchMineCommand extends Command {

    public BranchMineCommand(IBaritone baritone) {
        super(baritone, "branchmine");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        int mainLength = args.getAsOrDefault(Integer.class, Baritone.settings().branchMineMainLength.value);
        int sideLength = args.getAsOrDefault(Integer.class, Baritone.settings().branchMineSideLength.value);
        int spacing    = args.getAsOrDefault(Integer.class, Baritone.settings().branchMineSpacing.value);

        BetterBlockPos origin = ctx.playerFeet();
        int targetY = Baritone.settings().branchMineTargetY.value;
        if (targetY >= -64 && targetY <= 320) {
            origin = new BetterBlockPos(origin.getX(), targetY, origin.getZ());
        }

        Direction facing = ctx.player().getDirection();

        ((Baritone) baritone).getBranchMineProcess().start(origin, facing, mainLength, sideLength, spacing);
        logDirect(String.format("Branch mine: heading %s, %d-block corridor, %d-block branches every %d blocks",
                facing.getName(), mainLength, sideLength, spacing));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny() || args.hasExactlyOne()) {
            return CustomCommandCompleter.suggest(args,
                    String.valueOf(Baritone.settings().branchMineMainLength.value), "32", "64", "128", "256");
        }
        args.get();
        if (args.hasExactlyOne()) {
            return CustomCommandCompleter.suggest(args,
                    String.valueOf(Baritone.settings().branchMineSideLength.value), "8", "16", "32");
        }
        args.get();
        if (args.hasExactlyOne()) {
            return CustomCommandCompleter.suggest(args,
                    String.valueOf(Baritone.settings().branchMineSpacing.value), "2", "3", "4");
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Mine a branch mine pattern from current position";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Mines a branch mine pattern starting from your current position in the direction you are facing.",
                "Side branches are dug perpendicular to the main corridor at regular intervals.",
                "",
                "Settings: branchMineMainLength, branchMineSideLength, branchMineSpacing, branchMineTargetY",
                "",
                "Usage:",
                "> branchmine - Uses all settings defaults",
                "> branchmine <mainLength> - Custom main corridor length",
                "> branchmine <mainLength> <sideLength> - Custom lengths",
                "> branchmine <mainLength> <sideLength> <spacing> - Fully custom"
        );
    }
}

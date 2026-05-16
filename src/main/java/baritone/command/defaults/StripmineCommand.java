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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class StripmineCommand extends Command {

    public StripmineCommand(IBaritone baritone) {
        super(baritone, "stripmine");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (args.hasAny() && args.peekString().equalsIgnoreCase("setdeposit")) {
            args.get();
            ((Baritone) baritone).getStripmineProcess().setDeposit(ctx.playerFeet());
            return;
        }
        if (args.hasAny() && args.peekString().equalsIgnoreCase("setjunk")) {
            args.get();
            Optional<BlockPos> targeted = ctx.getSelectedBlock();
            if (!targeted.isPresent()) {
                logDirect("Look directly at the chest you want to use as the junk chest, then run setjunk");
                return;
            }
            ((Baritone) baritone).getStripmineProcess().setJunk(targeted.get());
            return;
        }

        int length    = args.getAsOrDefault(Integer.class, Baritone.settings().stripMineLength.value);
        int corridors = args.getAsOrDefault(Integer.class, Baritone.settings().stripMineCorridors.value);

        BetterBlockPos origin = ctx.playerFeet();
        int targetY = Baritone.settings().stripMineTargetY.value;
        if (targetY >= -64 && targetY <= 320) {
            origin = new BetterBlockPos(origin.getX(), targetY, origin.getZ());
        }

        Direction facing = ctx.player().getDirection();

        ((Baritone) baritone).getStripmineProcess().start(origin, facing, length, corridors);

        logDirect(String.format(
                "Strip mine: heading %s, %d block(s) long, %d corridor(s), spacing %d",
                facing.getName(), length, corridors, Baritone.settings().stripMineSpacing.value));

        String depositInfo = ((Baritone) baritone).getStripmineProcess().getDepositPos() != null
                ? "deposit set" : "no deposit set (use #stripmine setdeposit to configure)";
        logDirect(depositInfo);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactly(1)) {
            String partial = args.peekString().toLowerCase();
            return Stream.of("setdeposit", "setjunk").filter(s -> s.startsWith(partial));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Mine parallel corridors in a strip mine pattern";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Digs parallel corridors in the direction you are facing.",
                "Corridors are spaced by stripMineSpacing blocks (default 3) so every ore vein",
                "between them is exposed.  Height matches playerHeight (default 2).",
                "",
                "When a deposit location is configured and the inventory fills up, the bot",
                "automatically paths there and shift-clicks matching items into any storage",
                "container whose contents already include that item type (iron into iron-chest, etc).",
                "",
                "Settings: stripMineLength, stripMineCorridors, stripMineSpacing,",
                "          stripMineTargetY, stripmineInventoryFreeSlots",
                "",
                "Usage:",
                "> stripmine                    - Uses all setting defaults",
                "> stripmine <length>            - Custom corridor length",
                "> stripmine <length> <corridors>- Custom length and corridor count",
                "> stripmine setdeposit          - Save your current position as the deposit location",
                "> stripmine setjunk             - Save your current position as the junk chest location"
        );
    }
}

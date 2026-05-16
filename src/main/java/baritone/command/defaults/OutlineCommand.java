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
import baritone.api.command.datatypes.ForBlockOptionalMeta;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.datatypes.RelativeCoordinate;
import baritone.api.command.exception.CommandException;
import baritone.api.schematic.OutlineSchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * #outline x1 y1 z1  x2 y2 z2  block
 *
 * Builds only the outer shell (walls, floor, ceiling) of the bounding box.
 * Interior blocks are untouched.  Supports ~ relative coordinates.
 */
public class OutlineCommand extends Command {

    public OutlineCommand(IBaritone baritone) {
        super(baritone, "outline");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(7);
        BetterBlockPos origin = ctx.playerFeet();

        BetterBlockPos pos1 = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
        BetterBlockPos pos2 = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
        BlockOptionalMeta bom = args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);

        BlockPos min = new BlockPos(
                Math.min(pos1.x, pos2.x),
                Math.min(pos1.y, pos2.y),
                Math.min(pos1.z, pos2.z)
        );
        int widthX  = Math.abs(pos1.x - pos2.x) + 1;
        int heightY = Math.abs(pos1.y - pos2.y) + 1;
        int lengthZ = Math.abs(pos1.z - pos2.z) + 1;

        baritone.getBuilderProcess().build(
                "outline",
                new OutlineSchematic(widthX, heightY, lengthZ, bom),
                min
        );

        logDirect(String.format(
                "Outlining %dx%dx%d shell from %s to %s with %s",
                widthX, heightY, lengthZ,
                formatPos(pos1), formatPos(pos2),
                bom.getBlock().getName().getString()
        ));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        for (int i = 0; i < 6 && args.has(2); i++) {
            if (args.peekDatatypeOrNull(RelativeCoordinate.INSTANCE) != null) {
                args.get();
            } else {
                return Stream.empty();
            }
        }
        if (args.hasAny()) {
            return args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE);
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Build only the outer shell of a region";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Builds the walls, floor, and ceiling of a rectangular box without filling the interior.",
                "Uses BuilderProcess from your inventory.  Supports relative coordinates (~).",
                "",
                "Usage:",
                "> outline <x1> <y1> <z1> <x2> <y2> <z2> <block>",
                "",
                "Examples:",
                "> outline ~ ~ ~ ~10 ~5 ~10 stone",
                "    Builds an 11×6×11 stone shell from your feet",
                "> outline 100 64 200 120 70 220 glass",
                "    Builds a glass shell at absolute coordinates"
        );
    }

    private static String formatPos(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }
}

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
import baritone.api.schematic.FillSchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * #fill x1 y1 z1  x2 y2 z2  <block>
 *
 * Fills the rectangular region defined by two corners with the given block.
 * Blocks must be in inventory; Baritone's BuilderProcess handles placement.
 * Supports relative coordinates (~).
 */
public class FillCommand extends Command {

    public FillCommand(IBaritone baritone) {
        super(baritone, "fill");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(7); // 3 coords + 3 coords + 1 block
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
                "fill",
                new FillSchematic(widthX, heightY, lengthZ, bom),
                min
        );

        logDirect(String.format(
                "Filling %dx%dx%d region from %s to %s with %s",
                widthX, heightY, lengthZ,
                formatPos(pos1), formatPos(pos2),
                bom.getBlock().getName().getString()
        ));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        for (int i = 0; i < 6; i++) {
            if (!args.hasAny() || args.hasExactlyOne()) {
                return args.tabCompleteDatatype(RelativeCoordinate.INSTANCE);
            }
            if (args.peekDatatypeOrNull(RelativeCoordinate.INSTANCE) == null) {
                return args.tabCompleteDatatype(RelativeCoordinate.INSTANCE);
            }
            args.get();
        }
        if (args.hasExactlyOne()) {
            return args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE);
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Fill a region with a block";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Fill a rectangular region between two corners with a block.",
                "Blocks are placed using BuilderProcess from your inventory.",
                "Supports relative coordinates (~).",
                "",
                "Usage:",
                "> fill <x1> <y1> <z1> <x2> <y2> <z2> <block>",
                "",
                "Examples:",
                "> fill ~ ~ ~ ~10 ~5 ~10 stone",
                "    Fill an 11x6x11 cube from your feet with stone",
                "> fill 100 64 200 120 70 220 glass",
                "    Fill absolute region with glass"
        );
    }

    private static String formatPos(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }
}

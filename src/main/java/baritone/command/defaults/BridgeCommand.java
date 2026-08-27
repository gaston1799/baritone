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
import baritone.api.command.datatypes.ForBlockOptionalMeta;
import baritone.api.command.exception.CommandException;
import baritone.api.schematic.FillSchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class BridgeCommand extends Command {

    public BridgeCommand(IBaritone baritone) {
        super(baritone, "bridge");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);

        int length = args.getAs(Integer.class);
        if (length < 1) { logDirect("Length must be at least 1"); return; }

        // Optional width — consume only if it looks like a positive integer
        int width = 1;
        if (args.hasAny()) {
            try {
                int candidate = Integer.parseInt(args.peek().getValue());
                args.get(); // consume
                width = candidate;
            } catch (NumberFormatException ignored) {}
        }
        if (width < 1) { logDirect("Width must be at least 1"); return; }

        // Optional block (arg 3) — parsed via ForBlockOptionalMeta so no manual ResourceLocation needed
        BlockOptionalMeta bom = null;
        if (args.hasAny()) {
            bom = args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
        }

        // Optional player name (arg 4) — only present when block was also given
        String playerName = null;
        if (args.hasAny()) {
            playerName = args.getString();
        }

        // Fall back to first BlockItem in acceptableThrowawayItems when no block arg given
        if (bom == null) {
            bom = Baritone.settings().acceptableThrowawayItems.value.stream()
                    .filter(it -> it instanceof BlockItem)
                    .findFirst()
                    .map(it -> new BlockOptionalMeta(((BlockItem) it).getBlock()))
                    .orElse(null);
            if (bom == null) {
                logDirect("No block specified and no BlockItem found in acceptableThrowawayItems.");
                logDirect("Use: #set add acceptableThrowawayItems <block>  or pass a block as arg 3.");
                return;
            }
        }

        // Resolve facing from named player or self
        Direction facing;
        if (playerName != null) {
            String name = playerName;
            Optional<? extends Player> target = ctx.world().players().stream()
                    .filter(p -> p.getName().getString().equalsIgnoreCase(name))
                    .findFirst();
            if (target.isEmpty()) {
                logDirect("No player named '" + playerName + "' is visible");
                return;
            }
            facing = target.get().getDirection();
        } else {
            facing = ctx.player().getDirection();
        }

        BetterBlockPos feet = ctx.playerFeet();
        int floorY = feet.getY() - 1;

        // Compute schematic bounding box and world-space min corner based on facing direction
        int schX, schZ;
        BlockPos origin;
        switch (facing) {
            case EAST:
                schX = length; schZ = width;
                origin = new BlockPos(feet.getX(), floorY, feet.getZ() - width / 2);
                break;
            case WEST:
                schX = length; schZ = width;
                origin = new BlockPos(feet.getX() - length + 1, floorY, feet.getZ() - width / 2);
                break;
            case SOUTH:
                schX = width; schZ = length;
                origin = new BlockPos(feet.getX() - width / 2, floorY, feet.getZ());
                break;
            case NORTH:
                schX = width; schZ = length;
                origin = new BlockPos(feet.getX() - width / 2, floorY, feet.getZ() - length + 1);
                break;
            default:
                logDirect("Cannot bridge diagonally — face N, S, E, or W");
                return;
        }

        baritone.getBuilderProcess().build("bridge", new FillSchematic(schX, 1, schZ, bom), origin);

        logDirect(String.format("Bridging %d block(s) %s, %d wide, with %s",
                length, facing.getName(), width,
                bom.getBlock().getName().getString()));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny() || args.hasExactlyOne()) {
            return CustomCommandCompleter.suggest(args, "8", "16", "32", "64", "128");
        }
        args.get(); // completed length
        if (args.hasExactlyOne()) {
            return Stream.concat(
                    CustomCommandCompleter.suggest(args, "1", "2", "3", "5"),
                    args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE)
            ).distinct();
        }

        boolean widthPresent = false;
        try {
            Integer.parseInt(args.peek().getValue());
            args.get();
            widthPresent = true;
        } catch (NumberFormatException ignored) {
        }

        if (widthPresent && args.hasExactlyOne()) {
            return args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE);
        }

        if (!widthPresent) {
            args.get(); // completed block in the optional-width form
        } else if (args.has(2)) {
            args.get(); // completed block after width
        }
        if (args.hasExactlyOne()) {
            String partial = args.peekString().toLowerCase(java.util.Locale.US);
            return ctx.world().players().stream()
                    .map(player -> player.getName().getString())
                    .filter(name -> name.toLowerCase(java.util.Locale.US).startsWith(partial));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Bridge forward in your facing direction";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Builds a flat 1-block-high floor extending in the direction you're facing.",
                "Uses BuilderProcess — only places blocks where there is currently air.",
                "",
                "Usage:",
                "> bridge <length>",
                "> bridge <length> <width>",
                "> bridge <length> <width> <block>",
                "> bridge <length> <width> <block> <player>  — use that player's facing direction",
                "",
                "If no block is given, the first BlockItem in acceptableThrowawayItems is used."
        );
    }
}

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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ForBlockOptionalMeta;
import baritone.api.command.exception.CommandException;
import baritone.api.utils.BlockOptionalMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MineCommand extends Command {

    public MineCommand(IBaritone baritone) {
        super(baritone, MineTargetPresets.commandNames());
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        int quantity = args.getAsOrDefault(Integer.class, 0);
        List<BlockOptionalMeta> boms = new ArrayList<>();
        if (MineTargetPresets.isCommandAlias(label)) {
            boms.addAll(MineTargetPresets.resolve(label));
        }
        if (boms.isEmpty()) {
            args.requireMin(1);
        }
        while (args.hasAny()) {
            appendTarget(args, boms);
        }
        boms = MineTargetPresets.deduplicate(boms);
        BaritoneAPI.getProvider().getWorldScanner().repack(ctx);
        logDirect(String.format("Mining %s", describeTargets(boms)));
        baritone.getMineProcess().mine(quantity, boms.toArray(new BlockOptionalMeta[0]));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        args.getAsOrDefault(Integer.class, 0);
        while (args.has(2)) {
            appendTarget(args, new ArrayList<>());
        }
        String prefix = args.hasAny() ? args.peekString() : "";
        return Stream.concat(
                        MineTargetPresets.tabComplete(prefix),
                        args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE)
                )
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER);
    }

    @Override
    public String getShortDesc() {
        return "Mine some blocks";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The mine command allows you to tell Baritone to search for and mine individual blocks.",
                "",
                "The specified blocks can be ores, or any other block.",
                "",
                "Also see the legitMine settings (see #set l legitMine).",
                "",
                "Usage:",
                "> mine diamond_ore - Mines that ore family, including deepslate variants when they exist.",
                "> mine logs - Mines all log and stem types.",
                "> logs 64 - Shorthand for mining up to 64 logs of any wood type."
        );
    }

    private void appendTarget(IArgConsumer args, List<BlockOptionalMeta> targets) throws CommandException {
        List<BlockOptionalMeta> preset = MineTargetPresets.resolve(args.peekString());
        if (preset != null) {
            args.getString();
            targets.addAll(preset);
            return;
        }
        targets.add(args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE));
    }

    private String describeTargets(List<BlockOptionalMeta> targets) {
        return targets.stream()
                .map(BlockOptionalMeta::getBlock)
                .map(block -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString())
                .collect(Collectors.joining(", "));
    }
}

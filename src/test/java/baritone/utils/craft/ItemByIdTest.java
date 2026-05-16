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

package baritone.utils.craft;

import baritone.api.IBaritone;
import baritone.api.command.ICommand;
import baritone.api.command.argument.ICommandArgument;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.manager.ICommandManager;
import baritone.api.command.registry.Registry;
import baritone.command.argument.ArgConsumer;
import baritone.command.argument.CommandArguments;
import net.minecraft.util.Tuple;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

public class ItemByIdTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        MinecraftTestBootstrap.ensureInitialized();
    }

    @Test
    public void tabCompleteSuggestsItemsNotJustBlocks() {
        ArgConsumer consumer = new ArgConsumer(new StubCommandManager(), CommandArguments.from("diamond_s", true));

        List<String> suggestions = consumer.tabCompleteDatatype(ItemById.INSTANCE).collect(Collectors.toList());

        assertTrue(suggestions.contains("minecraft:diamond_sword"));
    }

    private static final class StubCommandManager implements ICommandManager {

        @Override
        public IBaritone getBaritone() {
            return null;
        }

        @Override
        public Registry<ICommand> getRegistry() {
            return null;
        }

        @Override
        public ICommand getCommand(String name) {
            return null;
        }

        @Override
        public boolean execute(String string) {
            return false;
        }

        @Override
        public boolean execute(Tuple<String, List<ICommandArgument>> expanded) {
            return false;
        }

        @Override
        public Stream<String> tabComplete(Tuple<String, List<ICommandArgument>> expanded) {
            return Stream.empty();
        }

        @Override
        public Stream<String> tabComplete(String prefix) {
            return Stream.empty();
        }
    }
}

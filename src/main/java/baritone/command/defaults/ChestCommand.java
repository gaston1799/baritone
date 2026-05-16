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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class ChestCommand extends Command {

    public ChestCommand(IBaritone baritone) {
        super(baritone, "chest");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        boolean enabled = ((Baritone) baritone).getChestLogBehavior().enabled ^= true;
        logDirect("Chest logging " + (enabled ? "enabled" : "disabled"));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Toggle container-open logging";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Toggles chest/container logging. When enabled, every time a container GUI",
                "opens, Baritone will print its title, position, and slot count to chat.",
                "",
                "Usage:",
                "> chest  - Toggle on/off"
        );
    }
}

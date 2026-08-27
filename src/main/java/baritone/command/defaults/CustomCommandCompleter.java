/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.command.defaults;

import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

final class CustomCommandCompleter {

    private CustomCommandCompleter() {
    }

    static Stream<String> suggest(IArgConsumer args, String... values) throws CommandException {
        String partial = args.hasAny() ? args.peekString().toLowerCase(Locale.US) : "";
        return Arrays.stream(values)
                .distinct()
                .filter(value -> value.toLowerCase(Locale.US).startsWith(partial));
    }
}

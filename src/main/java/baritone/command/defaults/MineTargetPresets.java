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

import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class MineTargetPresets {

    private static final String LOGS = "logs";
    private static final List<String> PRESET_NAMES = List.of(LOGS);

    private MineTargetPresets() {
    }

    static String[] commandNames() {
        return Stream.concat(Stream.of("mine"), PRESET_NAMES.stream()).toArray(String[]::new);
    }

    static boolean isCommandAlias(String label) {
        return PRESET_NAMES.contains(normalize(label));
    }

    static List<BlockOptionalMeta> resolve(String token) {
        switch (normalize(token)) {
            case LOGS:
                return BuiltInRegistries.BLOCK.stream()
                        .filter(MineTargetPresets::isNaturalLogTarget)
                        .sorted((left, right) -> BuiltInRegistries.BLOCK.getKey(left).toString().compareToIgnoreCase(BuiltInRegistries.BLOCK.getKey(right).toString()))
                        .map(BlockOptionalMeta::new)
                        .collect(Collectors.toList());
            default:
                return resolveOreFamily(token);
        }
    }

    static Stream<String> tabComplete(String prefix) {
        return new TabCompleteHelper()
                .append(PRESET_NAMES.stream())
                .filterPrefix(prefix)
                .sortAlphabetically()
                .stream();
    }

    static List<BlockOptionalMeta> deduplicate(List<BlockOptionalMeta> targets) {
        Map<String, BlockOptionalMeta> deduped = new LinkedHashMap<>();
        for (BlockOptionalMeta target : targets) {
            deduped.putIfAbsent(target.toString(), target);
        }
        return new ArrayList<>(deduped.values());
    }

    private static boolean isNaturalLogTarget(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        return !path.startsWith("stripped_") && (path.endsWith("_log") || path.endsWith("_stem"));
    }

    private static List<BlockOptionalMeta> resolveOreFamily(String token) {
        Block requested = BlockUtils.stringToBlockNullable(token);
        if (requested == null) {
            return null;
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(requested);
        if (key == null) {
            return null;
        }
        String path = key.getPath();
        String orePath;
        if (path.endsWith("_ore")) {
            orePath = path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
        } else {
            return null;
        }
        List<BlockOptionalMeta> resolved = new ArrayList<>();
        addIfPresent(resolved, key.getNamespace(), orePath);
        addIfPresent(resolved, key.getNamespace(), "deepslate_" + orePath);
        return resolved.isEmpty() ? null : resolved;
    }

    private static void addIfPresent(List<BlockOptionalMeta> resolved, String namespace, String path) {
        Block block = BlockUtils.stringToBlockNullable(namespace + ":" + path);
        if (block != null) {
            resolved.add(new BlockOptionalMeta(block));
        }
    }

    private static String normalize(String token) {
        return token.toLowerCase(Locale.US);
    }
}

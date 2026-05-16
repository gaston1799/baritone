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

import baritone.api.utils.BlockOptionalMeta;
import baritone.utils.craft.MinecraftTestBootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MineTargetPresetsTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        MinecraftTestBootstrap.ensureInitialized();
    }

    @Test
    public void logsPresetIncludesLogsAndStems() {
        List<String> ids = MineTargetPresets.resolve("logs").stream()
                .map(BlockOptionalMeta::getBlock)
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .collect(Collectors.toList());

        assertTrue(ids.contains("minecraft:oak_log"));
        assertTrue(ids.contains("minecraft:cherry_log"));
        assertTrue(ids.contains("minecraft:crimson_stem"));
        assertFalse(ids.contains("minecraft:stripped_oak_log"));
        assertFalse(ids.contains("minecraft:oak_wood"));
    }

    @Test
    public void logsPresetTabCompletes() {
        List<String> suggestions = MineTargetPresets.tabComplete("lo").collect(Collectors.toList());

        assertTrue(suggestions.contains("logs"));
    }

    @Test
    public void oreNameExpandsToDeepslateVariant() {
        List<String> ids = MineTargetPresets.resolve("iron_ore").stream()
                .map(BlockOptionalMeta::getBlock)
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .collect(Collectors.toList());

        assertTrue(ids.contains("minecraft:iron_ore"));
        assertTrue(ids.contains("minecraft:deepslate_iron_ore"));
    }

    @Test
    public void deepslateOreNameExpandsBackToBaseVariant() {
        List<String> ids = MineTargetPresets.resolve("deepslate_gold_ore").stream()
                .map(BlockOptionalMeta::getBlock)
                .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                .collect(Collectors.toList());

        assertTrue(ids.contains("minecraft:gold_ore"));
        assertTrue(ids.contains("minecraft:deepslate_gold_ore"));
    }
}

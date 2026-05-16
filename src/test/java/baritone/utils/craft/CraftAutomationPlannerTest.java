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

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CraftAutomationPlannerTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        MinecraftTestBootstrap.ensureInitialized();
    }

    @Test
    public void oreClassifierTreatsOresDifferentlyFromLogs() {
        assertTrue(CraftAutomationPlanner.isOreLike(Blocks.DIAMOND_ORE));
        assertTrue(CraftAutomationPlanner.isOreLike(Blocks.DEEPSLATE_IRON_ORE));
        assertTrue(CraftAutomationPlanner.isOreLike(Blocks.ANCIENT_DEBRIS));
        assertFalse(CraftAutomationPlanner.isOreLike(Blocks.OAK_LOG));
    }

    @Test
    public void inventoryMapToolCheckHandlesSimpleCases() {
        assertTrue(ToolRequirementHelper.hasMatchingTool(Collections.emptyMap(), Blocks.DIRT.defaultBlockState()));
        assertFalse(ToolRequirementHelper.hasMatchingTool(Map.of(Items.STICK, 1), Blocks.DIAMOND_ORE.defaultBlockState()));
    }

    @Test
    public void fuelCapacityCountsExpectedSmeltOperations() {
        assertEquals(1, CraftAutomationPlanner.fuelItemCount(Items.COAL, 8));
        assertEquals(1, CraftAutomationPlanner.fuelItemCount(Items.CHARCOAL, 8));
        assertEquals(6, CraftAutomationPlanner.fuelItemCount(Items.OAK_LOG, 8));
        assertEquals(16, CraftAutomationPlanner.fuelItemCount(Items.STICK, 8));
    }
}

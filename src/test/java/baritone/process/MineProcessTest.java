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

package baritone.process;

import baritone.api.utils.BlockOptionalMetaLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static baritone.utils.craft.MinecraftTestBootstrap.ensureInitialized;
import static org.junit.Assert.assertEquals;

public class MineProcessTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        ensureInitialized();
    }

    @Test
    public void quantityOverrideCountsRequestedCraftItem() {
        BlockOptionalMetaLookup filter = new BlockOptionalMetaLookup(Blocks.STONE, Blocks.COBBLESTONE);
        List<ItemStack> stacks = List.of(new ItemStack(Items.COBBLESTONE, 3));

        assertEquals(3, MineProcess.countInventoryItems(stacks, filter, Set.of(Items.COBBLESTONE)));
    }

    @Test
    public void droppedItemGoalsBypassMiningChecks() {
        BlockPos drop = new BlockPos(1, 64, 1);

        assertEquals(true, MineProcess.shouldBypassMiningChecks(drop, List.of(drop)));
        assertEquals(false, MineProcess.shouldBypassMiningChecks(BlockPos.ZERO, List.of(drop)));
    }

}

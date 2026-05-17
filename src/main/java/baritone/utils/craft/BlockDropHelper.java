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

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import sun.misc.Unsafe;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BlockDropHelper {

    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final ItemStack[] SAMPLE_TOOLS = createSampleTools();
    private static final ServerLevelStub STUB_LEVEL = ServerLevelStub.fastCreate();

    private BlockDropHelper() {
    }

    public static List<ItemStack> getDroppedStacks(Block block, ItemStack tool) {
        return getDroppedStacks(block.defaultBlockState(), tool);
    }

    public static List<ItemStack> getDroppedStacks(BlockState state, ItemStack tool) {
        try {
            return Block.getDrops(state, STUB_LEVEL, BlockPos.ZERO, null, null, tool);
        } catch (RuntimeException ex) {
            return Collections.emptyList();
        }
    }

    public static List<ItemStack> getPossibleDroppedStacks(Block block) {
        List<ItemStack> drops = new ArrayList<>();
        Item directItem = block.asItem();
        if (directItem != Items.AIR) {
            drops.add(new ItemStack(directItem));
        }
        for (ItemStack sampleTool : SAMPLE_TOOLS) {
            for (ItemStack dropped : getDroppedStacks(block, sampleTool)) {
                if (!dropped.isEmpty()) {
                    drops.add(dropped.copy());
                }
            }
        }
        return drops;
    }

    public static ItemStack[] sampleTools() {
        return SAMPLE_TOOLS.clone();
    }

    private static ItemStack[] createSampleTools() {
        List<ItemStack> tools = new ArrayList<>();
        tools.add(ItemStack.EMPTY);
        tools.add(new ItemStack(Items.SHEARS));
        tools.add(new ItemStack(Items.WOODEN_PICKAXE));
        tools.add(new ItemStack(Items.STONE_PICKAXE));
        tools.add(new ItemStack(Items.IRON_PICKAXE));
        tools.add(new ItemStack(Items.DIAMOND_PICKAXE));
        tools.add(new ItemStack(Items.NETHERITE_PICKAXE));
        tools.add(new ItemStack(Items.WOODEN_AXE));
        tools.add(new ItemStack(Items.STONE_AXE));
        tools.add(new ItemStack(Items.IRON_AXE));
        tools.add(new ItemStack(Items.DIAMOND_AXE));
        tools.add(new ItemStack(Items.NETHERITE_AXE));
        tools.add(new ItemStack(Items.WOODEN_SHOVEL));
        tools.add(new ItemStack(Items.STONE_SHOVEL));
        tools.add(new ItemStack(Items.IRON_SHOVEL));
        tools.add(new ItemStack(Items.DIAMOND_SHOVEL));
        tools.add(new ItemStack(Items.NETHERITE_SHOVEL));
        tools.add(new ItemStack(Items.WOODEN_HOE));
        tools.add(new ItemStack(Items.STONE_HOE));
        tools.add(new ItemStack(Items.IRON_HOE));
        tools.add(new ItemStack(Items.DIAMOND_HOE));
        tools.add(new ItemStack(Items.NETHERITE_HOE));

        tools.add(new ItemStack(Items.DIAMOND_PICKAXE));

        return tools.toArray(new ItemStack[0]);
    }

    private static final class ServerLevelStub extends ServerLevel {

        private static final Unsafe UNSAFE = getUnsafe();

        private ServerLevelStub(MinecraftServer server, java.util.concurrent.Executor executor, LevelStorageSource.LevelStorageAccess access, ServerLevelData data, ResourceKey<Level> key, LevelStem stem, ChunkProgressListener listener, boolean debug, long seed, List<CustomSpawner> spawners, boolean tickTime, @Nullable RandomSequences randomSequences) {
            super(server, executor, access, data, key, stem, listener, debug, seed, spawners, tickTime, randomSequences);
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            assert CLIENT.level != null;
            return CLIENT.level.enabledFeatures();
        }

        private static ServerLevelStub fastCreate() {
            try {
                return (ServerLevelStub) UNSAFE.allocateInstance(ServerLevelStub.class);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            }
        }

        private static Unsafe getUnsafe() {
            try {
                Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return (Unsafe) theUnsafe.get(null);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}

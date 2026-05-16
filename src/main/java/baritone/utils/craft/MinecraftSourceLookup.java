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

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.pathing.movement.CalculationContext;
import baritone.process.MineProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MinecraftSourceLookup implements CraftingPlanner.SourceLookup {

    private static Map<Item, List<Block>> sourceIndex;

    private final Baritone baritone;
    private final Map<Item, CraftingPlanner.DirectSource> cache = new HashMap<>();

    public MinecraftSourceLookup(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public CraftingPlanner.DirectSource lookup(Item item) {
        return cache.computeIfAbsent(item, this::createSource);
    }

    private CraftingPlanner.DirectSource createSource(Item item) {
        List<Block> blocks = sourceIndex().getOrDefault(item, Collections.emptyList());
        if (blocks.isEmpty()) {
            return CraftingPlanner.DirectSource.none();
        }
        CalculationContext context = new CalculationContext(baritone);
        List<BlockPos> knownPositions = MineProcess.searchWorld(
                context,
                new BlockOptionalMetaLookup(blocks),
                32,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>()
        );
        BetterBlockPos feet = baritone.getPlayerContext().playerFeet();
        double estimatedCost = knownPositions.stream()
                .mapToDouble(feet::distSqr)
                .min()
                .orElse(Double.POSITIVE_INFINITY);
        return new CraftingPlanner.DirectSource(blocks, knownPositions, estimatedCost);
    }

    private static synchronized Map<Item, List<Block>> sourceIndex() {
        if (sourceIndex != null) {
            return sourceIndex;
        }
        Map<Item, List<Block>> built = new LinkedHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (ItemStack drop : BlockDropHelper.getPossibleDroppedStacks(block)) {
                Item item = drop.getItem();
                built.computeIfAbsent(item, ignored -> new ArrayList<>()).add(block);
            }
        }
        // Cobblestone is usually gathered by breaking stone without silk touch, so keep
        // both direct cobblestone and regular stone as valid craft sources.
        built.computeIfAbsent(Items.COBBLESTONE, ignored -> new ArrayList<>()).add(Blocks.STONE);
        built.computeIfAbsent(Items.COBBLESTONE, ignored -> new ArrayList<>()).add(Blocks.COBBLESTONE);
        for (List<Block> blocks : built.values()) {
            addOreFamilyVariants(blocks);
            java.util.LinkedHashSet<Block> deduped = new java.util.LinkedHashSet<>(blocks);
            blocks.clear();
            blocks.addAll(deduped);
            blocks.sort(Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString()));
        }
        sourceIndex = built;
        return sourceIndex;
    }

    static List<Block> sourceBlocksFor(Item item) {
        return sourceIndex().getOrDefault(item, Collections.emptyList());
    }

    private static void addOreFamilyVariants(List<Block> blocks) {
        List<Block> extra = new ArrayList<>();
        for (Block block : blocks) {
            extra.addAll(oreFamilyVariants(block));
        }
        blocks.addAll(extra);
    }

    private static List<Block> oreFamilyVariants(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) {
            return Collections.emptyList();
        }
        String path = key.getPath();
        if (!path.endsWith("_ore")) {
            return Collections.emptyList();
        }
        List<Block> variants = new ArrayList<>(2);
        Block base = path.startsWith("deepslate_")
                ? BuiltInRegistries.BLOCK.getOptional(new ResourceLocation(key.getNamespace(), path.substring("deepslate_".length()))).orElse(null)
                : block;
        Block deepslate = path.startsWith("deepslate_")
                ? block
                : BuiltInRegistries.BLOCK.getOptional(new ResourceLocation(key.getNamespace(), "deepslate_" + path)).orElse(null);
        if (base != null) {
            variants.add(base);
        }
        if (deepslate != null) {
            variants.add(deepslate);
        }
        return variants;
    }
}

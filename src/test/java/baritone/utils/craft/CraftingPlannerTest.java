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

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CraftingPlannerTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        MinecraftTestBootstrap.ensureInitialized();
    }

    @Test
    public void choosesInventorySatisfiedBranchBeforeOtherLeaves() {
        CraftingPlanner planner = new CraftingPlanner(
                recipes(
                        recipe(Items.DIAMOND_SWORD, "test:diamond_sword", CraftingPlanner.StationKind.CRAFTING_TABLE, 1,
                                CraftingPlanner.IngredientChoice.of(Items.DIAMOND, 2),
                                CraftingPlanner.IngredientChoice.of(Items.STICK, 1)),
                        recipe(Items.STICK, "test:stick", CraftingPlanner.StationKind.HAND_CRAFTING, 4,
                                CraftingPlanner.IngredientChoice.of(Items.OAK_PLANKS, 2)),
                        recipe(Items.OAK_PLANKS, "test:planks", CraftingPlanner.StationKind.HAND_CRAFTING, 4,
                                CraftingPlanner.IngredientChoice.of(Items.OAK_LOG, 1))
                ),
                sources(
                        sourceEntry(Items.DIAMOND, Blocks.DIAMOND_ORE, 4),
                        sourceEntry(Items.OAK_LOG, Blocks.OAK_LOG, 30)
                )
        );

        Map<Item, Integer> inventory = counts(Items.OAK_PLANKS, 2);
        CraftingPlanner.PlanNode root = planner.plan(Items.DIAMOND_SWORD, 1, inventory);

        assertEquals(CraftingPlanner.PlanNode.Kind.RECIPE, root.kind);
        assertEquals(Items.DIAMOND, CraftingPlanner.firstKnownSourceLeaf(root).item);
        assertEquals(Integer.valueOf(2), CraftingPlanner.aggregateMissingLeaves(root).get(Items.DIAMOND));
        assertFalse(CraftingPlanner.aggregateMissingLeaves(root).containsKey(Items.OAK_LOG));
    }

    @Test
    public void tracksRecipeOutputSurplus() {
        CraftingPlanner planner = new CraftingPlanner(
                recipes(
                        recipe(Items.STICK, "test:stick", CraftingPlanner.StationKind.HAND_CRAFTING, 4,
                                CraftingPlanner.IngredientChoice.of(Items.OAK_PLANKS, 2))
                ),
                sources(sourceEntry(Items.OAK_PLANKS, Blocks.OAK_PLANKS, 6))
        );

        CraftingPlanner.PlanNode root = planner.plan(Items.STICK, 1, Collections.emptyMap());

        assertEquals(CraftingPlanner.PlanNode.Kind.RECIPE, root.kind);
        assertEquals(1, root.crafts);
        assertEquals(3, root.surplusProduced);
        assertEquals(Items.OAK_PLANKS, root.children.get(0).item);
        assertEquals(2, root.children.get(0).requiredCount);
    }

    @Test
    public void prefersInventoryBackedAlternativeIngredient() {
        CraftingPlanner planner = new CraftingPlanner(
                recipes(
                        recipe(Items.CHEST, "test:chest", CraftingPlanner.StationKind.CRAFTING_TABLE, 1,
                                new CraftingPlanner.IngredientChoice(Arrays.asList(Items.OAK_PLANKS, Items.BIRCH_PLANKS), 2))
                ),
                sources(sourceEntry(Items.OAK_PLANKS, Blocks.OAK_PLANKS, 4))
        );

        CraftingPlanner.PlanNode root = planner.plan(Items.CHEST, 1, counts(Items.BIRCH_PLANKS, 2));

        assertEquals(CraftingPlanner.PlanNode.Kind.RECIPE, root.kind);
        assertEquals(CraftingPlanner.PlanNode.Kind.INVENTORY, root.children.get(0).kind);
        assertEquals(Items.BIRCH_PLANKS, root.children.get(0).item);
    }

    @Test
    public void unresolvedLeafProvidesHint() {
        CraftingPlanner planner = new CraftingPlanner(item -> Collections.emptyList(), item -> CraftingPlanner.DirectSource.none());

        CraftingPlanner.PlanNode root = planner.plan(Items.ENDER_PEARL, 1, Collections.emptyMap());

        assertEquals(CraftingPlanner.PlanNode.Kind.UNRESOLVED, root.kind);
        assertNotNull(root.unresolvedHint);
        assertFalse(root.unresolvedHint.isEmpty());
    }

    @Test
    public void tieBreakUsesRecipeIdLexically() {
        CraftingPlanner planner = new CraftingPlanner(
                recipes(
                        recipe(Items.CHEST, "test:zeta", CraftingPlanner.StationKind.CRAFTING_TABLE, 1,
                                CraftingPlanner.IngredientChoice.of(Items.OAK_LOG, 1)),
                        recipe(Items.CHEST, "test:alpha", CraftingPlanner.StationKind.CRAFTING_TABLE, 1,
                                CraftingPlanner.IngredientChoice.of(Items.OAK_LOG, 1))
                ),
                sources(sourceEntry(Items.OAK_LOG, Blocks.OAK_LOG, 3))
        );

        CraftingPlanner.PlanNode root = planner.plan(Items.CHEST, 1, Collections.emptyMap());

        assertEquals(CraftingPlanner.PlanNode.Kind.RECIPE, root.kind);
        assertEquals(ResourceLocation.fromNamespaceAndPath("test", "alpha"), root.recipe.id);
    }

    @Test
    public void cookingRecipeRetainsStationAndFuelFlag() {
        CraftingPlanner planner = new CraftingPlanner(
                recipes(
                        new CraftingPlanner.NormalizedRecipe(
                                ResourceLocation.fromNamespaceAndPath("test", "smelt_raw_iron"),
                                CraftingPlanner.StationKind.FURNACE,
                                Items.IRON_INGOT,
                                1,
                                Collections.singletonList(CraftingPlanner.IngredientChoice.of(Items.RAW_IRON, 1)),
                                true
                        )
                ),
                sources(sourceEntry(Items.RAW_IRON, Blocks.RAW_IRON_BLOCK, 5))
        );

        CraftingPlanner.PlanNode root = planner.plan(Items.IRON_INGOT, 1, Collections.emptyMap());

        assertEquals(CraftingPlanner.PlanNode.Kind.RECIPE, root.kind);
        assertEquals(CraftingPlanner.StationKind.FURNACE, root.recipe.station);
        assertTrue(root.recipe.fuelNotPlanned);
    }

    @Test
    public void disallowedStationForcesAlternativeRecipeChoice() {
        CraftingPlanner planner = new CraftingPlanner(
                recipes(
                        new CraftingPlanner.NormalizedRecipe(
                                ResourceLocation.fromNamespaceAndPath("test", "blast_iron"),
                                CraftingPlanner.StationKind.BLAST_FURNACE,
                                Items.IRON_INGOT,
                                1,
                                Collections.singletonList(CraftingPlanner.IngredientChoice.of(Items.RAW_IRON, 1)),
                                true
                        ),
                        new CraftingPlanner.NormalizedRecipe(
                                ResourceLocation.fromNamespaceAndPath("test", "smelt_iron"),
                                CraftingPlanner.StationKind.FURNACE,
                                Items.IRON_INGOT,
                                1,
                                Collections.singletonList(CraftingPlanner.IngredientChoice.of(Items.RAW_IRON, 1)),
                                true
                        )
                ),
                sources(sourceEntry(Items.RAW_IRON, Blocks.RAW_IRON_BLOCK, 5))
        );

        CraftingPlanner.PlanNode root = planner.plan(
                Items.IRON_INGOT,
                1,
                Collections.emptyMap(),
                Collections.singleton(CraftingPlanner.StationKind.BLAST_FURNACE)
        );

        assertEquals(CraftingPlanner.PlanNode.Kind.RECIPE, root.kind);
        assertEquals(CraftingPlanner.StationKind.FURNACE, root.recipe.station);
    }

    @Test
    public void firstRecipeStepReturnsDeepestCraftableRecipe() {
        CraftingPlanner planner = new CraftingPlanner(
                recipes(
                        recipe(Items.WOODEN_PICKAXE, "test:wooden_pickaxe", CraftingPlanner.StationKind.CRAFTING_TABLE, 1,
                                CraftingPlanner.IngredientChoice.of(Items.OAK_PLANKS, 3),
                                CraftingPlanner.IngredientChoice.of(Items.STICK, 2)),
                        recipe(Items.STICK, "test:stick", CraftingPlanner.StationKind.HAND_CRAFTING, 4,
                                CraftingPlanner.IngredientChoice.of(Items.OAK_PLANKS, 2)),
                        recipe(Items.OAK_PLANKS, "test:planks", CraftingPlanner.StationKind.HAND_CRAFTING, 4,
                                CraftingPlanner.IngredientChoice.of(Items.OAK_LOG, 1))
                ),
                item -> CraftingPlanner.DirectSource.none()
        );

        CraftingPlanner.PlanNode root = planner.plan(Items.WOODEN_PICKAXE, 1, counts(Items.OAK_LOG, 2));

        assertEquals(CraftingPlanner.PlanNode.Kind.RECIPE, root.kind);
        assertEquals(Items.OAK_PLANKS, CraftingPlanner.firstRecipeStep(root).item);
    }

    @Test
    public void prefersLogToBambooWhenBambooNeedsMoreBlocks() {
        CraftingPlanner planner = new CraftingPlanner(
                recipes(
                        recipe(Items.STICK, "test:bamboo_stick", CraftingPlanner.StationKind.HAND_CRAFTING, 1,
                                CraftingPlanner.IngredientChoice.of(Items.BAMBOO, 2)),
                        recipe(Items.STICK, "test:plank_stick", CraftingPlanner.StationKind.HAND_CRAFTING, 4,
                                CraftingPlanner.IngredientChoice.of(Items.OAK_PLANKS, 2)),
                        recipe(Items.OAK_PLANKS, "test:planks", CraftingPlanner.StationKind.HAND_CRAFTING, 4,
                                CraftingPlanner.IngredientChoice.of(Items.OAK_LOG, 1))
                ),
                sources(
                        sourceEntry(Items.BAMBOO, Blocks.BAMBOO, 10),
                        sourceEntry(Items.OAK_LOG, Blocks.OAK_LOG, 30)
                )
        );

        CraftingPlanner.PlanNode root = planner.plan(Items.STICK, 2, Collections.emptyMap());

        assertEquals(CraftingPlanner.PlanNode.Kind.RECIPE, root.kind);
        assertEquals(ResourceLocation.fromNamespaceAndPath("test", "plank_stick"), root.recipe.id);
    }

    @Test
    public void minimumRequiredToolDetectsRepresentativeBlocks() {
        assertTrue(ToolRequirementHelper.minimumRequiredTool(Blocks.DIRT.defaultBlockState()).isEmpty());
        assertTrue(Blocks.DIAMOND_ORE.defaultBlockState().requiresCorrectToolForDrops());
        assertTrue(Blocks.OBSIDIAN.defaultBlockState().requiresCorrectToolForDrops());
    }

    @Test
    public void blockDropHelperIncludesDirectBlockItemFallback() {
        assertTrue(BlockDropHelper.getPossibleDroppedStacks(Blocks.ACACIA_LOG).stream()
                .anyMatch(stack -> stack.getItem() == Items.ACACIA_LOG));
    }

    @Test
    public void cobblestoneSourcesIncludeStoneAndCobblestone() {
        List<net.minecraft.world.level.block.Block> sources = MinecraftSourceLookup.sourceBlocksFor(Items.COBBLESTONE);

        assertTrue(sources.contains(Blocks.STONE));
        assertTrue(sources.contains(Blocks.COBBLESTONE));
    }

    @Test
    public void oreBlockItemSourcesIncludeRegularAndDeepslateOre() {
        List<net.minecraft.world.level.block.Block> sources = MinecraftSourceLookup.sourceBlocksFor(Items.DIAMOND_ORE);

        assertTrue(sources.contains(Blocks.DIAMOND_ORE));
        assertTrue(sources.contains(Blocks.DEEPSLATE_DIAMOND_ORE));
    }

    private static CraftingPlanner.NormalizedRecipe recipe(Item result, String id, CraftingPlanner.StationKind station, int outputCount, CraftingPlanner.IngredientChoice... ingredients) {
        return new CraftingPlanner.NormalizedRecipe(
                ResourceLocation.parse(id),
                station,
                result,
                outputCount,
                Arrays.asList(ingredients),
                false
        );
    }

    private static CraftingPlanner.RecipeLookup recipes(CraftingPlanner.NormalizedRecipe... recipes) {
        Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult = new LinkedHashMap<>();
        for (CraftingPlanner.NormalizedRecipe recipe : recipes) {
            byResult.computeIfAbsent(recipe.result, ignored -> new java.util.ArrayList<>()).add(recipe);
        }
        return item -> byResult.getOrDefault(item, Collections.emptyList());
    }

    private static CraftingPlanner.SourceLookup sources(SourceEntry... sources) {
        Map<Item, CraftingPlanner.DirectSource> byItem = new LinkedHashMap<>();
        for (SourceEntry source : sources) {
            byItem.put(source.item, source.source);
        }
        return item -> byItem.getOrDefault(item, CraftingPlanner.DirectSource.none());
    }

    private static SourceEntry sourceEntry(Item item, net.minecraft.world.level.block.Block block, double estimatedCost) {
        return new SourceEntry(
                item,
                new CraftingPlanner.DirectSource(Collections.singletonList(block), Collections.singletonList(new BlockPos((int) estimatedCost, 64, 0)), estimatedCost)
        );
    }

    private static Map<Item, Integer> counts(Item item, int count) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        counts.put(item, count);
        return counts;
    }

    private static final class SourceEntry {
        private final Item item;
        private final CraftingPlanner.DirectSource source;

        private SourceEntry(Item item, CraftingPlanner.DirectSource source) {
            this.item = item;
            this.source = source;
        }
    }
}

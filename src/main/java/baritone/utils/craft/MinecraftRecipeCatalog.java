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
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexes the recipes available to the player so the craft planner can find
 * a recipe by result item.
 *
 * 1.21.5 removed the client-side RecipeManager; recipe data now lives in the
 * recipe book as RecipeDisplayEntry objects (server-side, falls back to the
 * real RecipeManager when a server level is available, e.g. in tests).
 */
public final class MinecraftRecipeCatalog implements CraftingPlanner.RecipeLookup {

    private final Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult;

    private MinecraftRecipeCatalog(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult) {
        this.byResult = byResult;
    }

    public static MinecraftRecipeCatalog create(Level level) {
        MinecraftServer server = level.getServer();
        if (server != null) {
            return createFromRecipeManager(server.getRecipeManager(), level.registryAccess());
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new MinecraftRecipeCatalog(new LinkedHashMap<>());
        }
        Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult = new LinkedHashMap<>();
        for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                addDisplayRecipe(byResult, entry);
            }
        }
        sortRecipes(byResult);
        return new MinecraftRecipeCatalog(byResult);
    }

    @Override
    public List<CraftingPlanner.NormalizedRecipe> getRecipes(Item item) {
        return byResult.getOrDefault(item, Collections.emptyList());
    }

    private static void addDisplayRecipe(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult, RecipeDisplayEntry entry) {
        RecipeDisplay display = entry.display();
        CraftingPlanner.StationKind station;
        boolean cooking;
        ItemStack result;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            station = (shaped.width() <= 2 && shaped.height() <= 2)
                    ? CraftingPlanner.StationKind.HAND_CRAFTING
                    : CraftingPlanner.StationKind.CRAFTING_TABLE;
            cooking = false;
            result = resolveSlot(shaped.result());
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            station = shapeless.ingredients().size() <= 4
                    ? CraftingPlanner.StationKind.HAND_CRAFTING
                    : CraftingPlanner.StationKind.CRAFTING_TABLE;
            cooking = false;
            result = resolveSlot(shapeless.result());
        } else if (display instanceof FurnaceRecipeDisplay furnace) {
            station = stationFromStationDisplay(furnace.craftingStation());
            cooking = true;
            result = resolveSlot(furnace.result());
        } else {
            return; // smithing, stonecutter, ...
        }
        if (result == null || result.isEmpty()) {
            return;
        }
        List<CraftingPlanner.IngredientChoice> ingredients = flattenIngredients(
                entry.craftingRequirements().orElse(Collections.emptyList()));
        if (cooking && ingredients.isEmpty()) {
            return;
        }
        // Display entries don't carry the recipe's ResourceLocation; use the display id as a stable synthetic key.
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("baritone_craft", Integer.toString(entry.id().index()));
        addRecipe(byResult, new CraftingPlanner.NormalizedRecipe(
                id,
                station,
                result.getItem(),
                result.getCount(),
                ingredients,
                cooking
        ));
    }

    private static CraftingPlanner.StationKind stationFromStationDisplay(SlotDisplay stationSlot) {
        ItemStack stack = resolveSlot(stationSlot);
        if (stack == null) {
            return CraftingPlanner.StationKind.FURNACE;
        }
        Item item = stack.getItem();
        if (item == Items.BLAST_FURNACE) {
            return CraftingPlanner.StationKind.BLAST_FURNACE;
        }
        if (item == Items.SMOKER) {
            return CraftingPlanner.StationKind.SMOKER;
        }
        if (item == Items.CAMPFIRE || item == Items.SOUL_CAMPFIRE) {
            return CraftingPlanner.StationKind.CAMPFIRE;
        }
        return CraftingPlanner.StationKind.FURNACE;
    }

    private static ItemStack resolveSlot(SlotDisplay slot) {
        if (slot instanceof SlotDisplay.ItemStackSlotDisplay stackDisplay) {
            return stackDisplay.stack();
        }
        if (slot instanceof SlotDisplay.ItemSlotDisplay itemDisplay) {
            return new ItemStack(itemDisplay.item());
        }
        return null;
    }

    private static MinecraftRecipeCatalog createFromRecipeManager(RecipeManager recipeManager, RegistryAccess registryAccess) {
        Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult = new LinkedHashMap<>();
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            if (holder.value().getType() != net.minecraft.world.item.crafting.RecipeType.CRAFTING) {
                continue;
            }
            net.minecraft.world.item.crafting.CraftingRecipe recipe = (net.minecraft.world.item.crafting.CraftingRecipe) holder.value();
            ItemStack result = recipe.assemble(net.minecraft.world.item.crafting.CraftingInput.EMPTY, registryAccess);
            if (result.isEmpty()) {
                continue;
            }
            CraftingPlanner.StationKind station = recipe.placementInfo().ingredients().size() <= 4
                    ? CraftingPlanner.StationKind.HAND_CRAFTING
                    : CraftingPlanner.StationKind.CRAFTING_TABLE;
            addRecipe(byResult, new CraftingPlanner.NormalizedRecipe(
                    holder.id().location(),
                    station,
                    result.getItem(),
                    result.getCount(),
                    flattenIngredients(recipe.placementInfo().ingredients()),
                    false
            ));
        }
        sortRecipes(byResult);
        return new MinecraftRecipeCatalog(byResult);
    }

    private static void sortRecipes(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult) {
        for (List<CraftingPlanner.NormalizedRecipe> recipes : byResult.values()) {
            recipes.sort(Comparator.comparing(recipe -> recipe.id.toString()));
        }
    }

    private static void addRecipe(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult, CraftingPlanner.NormalizedRecipe recipe) {
        byResult.computeIfAbsent(recipe.result, ignored -> new ArrayList<>()).add(recipe);
    }

    private static List<CraftingPlanner.IngredientChoice> flattenIngredients(List<Ingredient> ingredients) {
        Map<String, List<Item>> groupedOptions = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            List<Item> options = new ArrayList<>();
            ingredient.items().forEach(holder -> options.add(holder.value()));
            if (options.isEmpty()) {
                continue;
            }
            options.sort(Comparator.comparing(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString()));
            String key = options.stream()
                    .map(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString())
                    .distinct()
                    .reduce((left, right) -> left + "|" + right)
                    .orElse("");
            groupedOptions.putIfAbsent(key, options);
            counts.merge(key, 1, Integer::sum);
        }
        List<CraftingPlanner.IngredientChoice> flattened = new ArrayList<>();
        for (Map.Entry<String, List<Item>> entry : groupedOptions.entrySet()) {
            flattened.add(new CraftingPlanner.IngredientChoice(entry.getValue(), counts.getOrDefault(entry.getKey(), 0)));
        }
        flattened.sort(Comparator.comparing(choice -> choice.options.stream()
                .map(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString())
                .reduce((left, right) -> left + "|" + right)
                .orElse("")));
        return flattened;
    }
}

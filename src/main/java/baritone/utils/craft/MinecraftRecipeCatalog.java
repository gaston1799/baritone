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

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MinecraftRecipeCatalog implements CraftingPlanner.RecipeLookup {

    private final Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult;

    private MinecraftRecipeCatalog(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult) {
        this.byResult = byResult;
    }

    public static MinecraftRecipeCatalog create(Level level) {
        RegistryAccess registryAccess = level.registryAccess();
        RecipeManager recipeManager = level.getRecipeManager();
        Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult = new LinkedHashMap<>();

        addCraftingRecipes(byResult, recipeManager, registryAccess);
        addCookingRecipes(byResult, recipeManager, registryAccess, RecipeType.SMELTING, CraftingPlanner.StationKind.FURNACE);
        addCookingRecipes(byResult, recipeManager, registryAccess, RecipeType.BLASTING, CraftingPlanner.StationKind.BLAST_FURNACE);
        addCookingRecipes(byResult, recipeManager, registryAccess, RecipeType.SMOKING, CraftingPlanner.StationKind.SMOKER);
        addCookingRecipes(byResult, recipeManager, registryAccess, RecipeType.CAMPFIRE_COOKING, CraftingPlanner.StationKind.CAMPFIRE);

        for (List<CraftingPlanner.NormalizedRecipe> recipes : byResult.values()) {
            recipes.sort(Comparator.comparing(recipe -> recipe.id.toString()));
        }
        return new MinecraftRecipeCatalog(byResult);
    }

    @Override
    public List<CraftingPlanner.NormalizedRecipe> getRecipes(Item item) {
        return byResult.getOrDefault(item, Collections.emptyList());
    }

    private static void addCraftingRecipes(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult, RecipeManager recipeManager, RegistryAccess registryAccess) {
        for (CraftingRecipe recipe : recipeManager.getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack result = recipe.getResultItem(registryAccess);
            if (result.isEmpty()) {
                continue;
            }
            CraftingPlanner.StationKind station = recipe.canCraftInDimensions(2, 2)
                    ? CraftingPlanner.StationKind.HAND_CRAFTING
                    : CraftingPlanner.StationKind.CRAFTING_TABLE;
            addRecipe(byResult, new CraftingPlanner.NormalizedRecipe(
                    recipe.getId(),
                    station,
                    result.getItem(),
                    result.getCount(),
                    flattenIngredients(recipe.getIngredients()),
                    false
            ));
        }
    }

    private static <T extends AbstractCookingRecipe> void addCookingRecipes(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult, RecipeManager recipeManager, RegistryAccess registryAccess, RecipeType<T> type, CraftingPlanner.StationKind station) {
        for (T recipe : recipeManager.getAllRecipesFor(type)) {
            ItemStack result = recipe.getResultItem(registryAccess);
            if (result.isEmpty()) {
                continue;
            }
            List<CraftingPlanner.IngredientChoice> ingredients = flattenIngredients(recipe.getIngredients());
            if (ingredients.isEmpty()) {
                continue;
            }
            addRecipe(byResult, new CraftingPlanner.NormalizedRecipe(
                    recipe.getId(),
                    station,
                    result.getItem(),
                    result.getCount(),
                    ingredients,
                    true
            ));
        }
    }

    private static void addRecipe(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult, CraftingPlanner.NormalizedRecipe recipe) {
        byResult.computeIfAbsent(recipe.result, ignored -> new ArrayList<>()).add(recipe);
    }

    private static List<CraftingPlanner.IngredientChoice> flattenIngredients(NonNullList<Ingredient> ingredients) {
        Map<String, List<Item>> groupedOptions = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            List<Item> options = new ArrayList<>();
            for (ItemStack stack : ingredient.getItems()) {
                if (!stack.isEmpty()) {
                    options.add(stack.getItem());
                }
            }
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

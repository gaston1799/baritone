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
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
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
        Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult = new LinkedHashMap<>();
        RecipeManager recipeManager = Minecraft.getInstance().getSingleplayerServer() != null
                ? Minecraft.getInstance().getSingleplayerServer().getRecipeManager()
                : null;

        if (recipeManager != null) {
            addRecipes(byResult, level, recipeManager, registryAccess);
        }

        for (List<CraftingPlanner.NormalizedRecipe> recipes : byResult.values()) {
            recipes.sort(Comparator.comparing(recipe -> recipe.id.toString()));
        }
        return new MinecraftRecipeCatalog(byResult);
    }

    @Override
    public List<CraftingPlanner.NormalizedRecipe> getRecipes(Item item) {
        return byResult.getOrDefault(item, Collections.emptyList());
    }

    private static void addRecipes(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult, Level level, RecipeManager recipeManager, RegistryAccess registryAccess) {
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe instanceof CraftingRecipe craftingRecipe) {
                addCraftingRecipe(byResult, level, registryAccess, holder.id(), craftingRecipe);
                continue;
            }
            if (recipe instanceof AbstractCookingRecipe cookingRecipe) {
                CraftingPlanner.StationKind station = cookingStation(recipe.getType());
                if (station != null) {
                    addCookingRecipe(byResult, level, registryAccess, holder.id(), cookingRecipe, station);
                }
            }
        }
    }

    private static void addCraftingRecipe(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult, Level level, RegistryAccess registryAccess, ResourceKey<Recipe<?>> recipeId, CraftingRecipe recipe) {
        ItemStack result = recipeResult(level, registryAccess, recipe, CraftingInput.EMPTY);
        if (result.isEmpty()) {
            return;
        }
        addRecipe(byResult, new CraftingPlanner.NormalizedRecipe(
                recipeId.location(),
                isHandCraftable(recipe) ? CraftingPlanner.StationKind.HAND_CRAFTING : CraftingPlanner.StationKind.CRAFTING_TABLE,
                result.getItem(),
                result.getCount(),
                flattenIngredients(recipe.placementInfo().ingredients()),
                false
        ));
    }

    private static void addCookingRecipe(Map<Item, List<CraftingPlanner.NormalizedRecipe>> byResult, Level level, RegistryAccess registryAccess, ResourceKey<Recipe<?>> recipeId, AbstractCookingRecipe recipe, CraftingPlanner.StationKind station) {
        ItemStack result = recipeResult(level, registryAccess, recipe, new SingleRecipeInput(ItemStack.EMPTY));
        if (result.isEmpty()) {
            return;
        }
        List<CraftingPlanner.IngredientChoice> ingredients = flattenIngredients(recipe.placementInfo().ingredients());
        if (ingredients.isEmpty()) {
            return;
        }
        addRecipe(byResult, new CraftingPlanner.NormalizedRecipe(
                recipeId.location(),
                station,
                result.getItem(),
                result.getCount(),
                ingredients,
                true
        ));
    }

    private static <T extends net.minecraft.world.item.crafting.RecipeInput> ItemStack recipeResult(Level level, RegistryAccess registryAccess, Recipe<T> recipe, T input) {
        ItemStack displayResult = ItemStack.EMPTY;
        for (RecipeDisplay display : recipe.display()) {
            ItemStack resolved = display.result().resolveForFirstStack(SlotDisplayContext.fromLevel(level));
            if (resolved.isEmpty()) {
                continue;
            }
            displayResult = resolved;
            break;
        }
        return displayResult.isEmpty() ? recipe.assemble(input, registryAccess) : displayResult;
    }

    private static CraftingPlanner.StationKind cookingStation(RecipeType<?> type) {
        if (type == RecipeType.SMELTING) {
            return CraftingPlanner.StationKind.FURNACE;
        }
        if (type == RecipeType.BLASTING) {
            return CraftingPlanner.StationKind.BLAST_FURNACE;
        }
        if (type == RecipeType.SMOKING) {
            return CraftingPlanner.StationKind.SMOKER;
        }
        if (type == RecipeType.CAMPFIRE_COOKING) {
            return CraftingPlanner.StationKind.CAMPFIRE;
        }
        return null;
    }

    private static boolean isHandCraftable(CraftingRecipe recipe) {
        for (RecipeDisplay display : recipe.display()) {
            if (display instanceof ShapedCraftingRecipeDisplay shaped) {
                return shaped.width() <= 2 && shaped.height() <= 2;
            }
            if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
                return shapeless.ingredients().size() <= 4;
            }
        }
        return recipe.placementInfo().ingredients().size() <= 4;
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
            ingredient.items().map(holder -> holder.value()).forEach(options::add);
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

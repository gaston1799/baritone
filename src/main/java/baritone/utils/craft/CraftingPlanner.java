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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class CraftingPlanner {

    private static final String NON_BLOCK_HINT = "Likely from mobs, chest loot, villager trades, or fishing.";

    private final RecipeLookup recipeLookup;
    private final SourceLookup sourceLookup;

    public CraftingPlanner(RecipeLookup recipeLookup, SourceLookup sourceLookup) {
        this.recipeLookup = Objects.requireNonNull(recipeLookup);
        this.sourceLookup = Objects.requireNonNull(sourceLookup);
    }

    public PlanNode plan(Item item, int count, Map<Item, Integer> inventory) {
        return plan(item, count, inventory, Collections.emptySet());
    }

    public PlanNode plan(Item item, int count, Map<Item, Integer> inventory, Set<StationKind> disallowedStations) {
        return resolve(item, count, sanitizeInventory(inventory), new LinkedHashSet<>(), disallowedStations).node;
    }

    private Resolution resolve(Item item, int count, Map<Item, Integer> inventory, Set<Item> stack, Set<StationKind> disallowedStations) {
        Map<Item, Integer> available = new HashMap<>(inventory);
        int usedFromInventory = consume(available, item, count);
        int missing = count - usedFromInventory;
        if (missing <= 0) {
            PlanNode node = PlanNode.inventory(item, count, usedFromInventory);
            return new Resolution(node, available);
        }

        List<Resolution> candidates = new ArrayList<>();
        DirectSource source = sourceLookup.lookup(item);
        if (source.hasConcreteBlocks()) {
            candidates.add(new Resolution(PlanNode.source(item, count, usedFromInventory, source), available));
        }

        if (!stack.contains(item)) {
            Set<Item> nextStack = new LinkedHashSet<>(stack);
            nextStack.add(item);
            for (NormalizedRecipe recipe : recipeLookup.getRecipes(item)) {
                if (disallowedStations.contains(recipe.station)) {
                    continue;
                }
                Resolution recipeResolution = resolveRecipe(item, count, usedFromInventory, missing, recipe, available, nextStack, disallowedStations);
                if (recipeResolution != null) {
                    candidates.add(recipeResolution);
                }
            }
        }

        if (candidates.isEmpty()) {
            return new Resolution(PlanNode.unresolved(item, count, usedFromInventory, NON_BLOCK_HINT), available);
        }
        return Collections.min(candidates, Comparator.comparing(resolution -> resolution.node.metrics));
    }

    private Resolution resolveRecipe(Item item, int count, int usedFromInventory, int missing, NormalizedRecipe recipe, Map<Item, Integer> inventory, Set<Item> stack, Set<StationKind> disallowedStations) {
        int crafts = (int) Math.ceil(missing / (double) recipe.outputCount);
        Map<Item, Integer> workingInventory = new HashMap<>(inventory);
        List<IngredientChoice> sortedChoices = new ArrayList<>(recipe.ingredients);
        Map<Item, Integer> previewInventory = new HashMap<>(workingInventory);
        sortedChoices.sort(Comparator.<IngredientChoice>comparingInt(choice -> previewRank(choice, previewInventory))
                .thenComparing(choice -> choice.lexicalKey));

        List<PlanNode> children = new ArrayList<>();
        for (IngredientChoice choice : sortedChoices) {
            int required = choice.count * crafts;
            Resolution best = null;
            for (Item option : choice.options) {
                Resolution candidate = resolve(option, required, workingInventory, stack, disallowedStations);
                if (best == null || candidate.node.metrics.compareTo(best.node.metrics) < 0) {
                    best = candidate;
                }
            }
            if (best == null) {
                return null;
            }
            children.add(best.node);
            workingInventory = best.leftoverInventory;
        }

        int produced = recipe.outputCount * crafts;
        int surplus = produced - missing;
        if (surplus > 0) {
            addCount(workingInventory, item, surplus);
        }

        PlanNode node = PlanNode.recipe(item, count, usedFromInventory, recipe, crafts, surplus, children);
        return new Resolution(node, workingInventory);
    }

    private int previewRank(IngredientChoice choice, Map<Item, Integer> inventory) {
        int best = Integer.MAX_VALUE;
        for (Item option : choice.options) {
            int have = inventory.getOrDefault(option, 0);
            if (have >= choice.count) {
                best = Math.min(best, 0);
                continue;
            }
            DirectSource source = sourceLookup.lookup(option);
            if (source.hasConcreteBlocks()) {
                best = Math.min(best, source.knownInWorld ? 1 : 2);
                continue;
            }
            if (!recipeLookup.getRecipes(option).isEmpty()) {
                best = Math.min(best, 3);
                continue;
            }
            best = Math.min(best, 4);
        }
        return best == Integer.MAX_VALUE ? 4 : best;
    }

    private static int consume(Map<Item, Integer> inventory, Item item, int count) {
        int available = inventory.getOrDefault(item, 0);
        int used = Math.min(available, count);
        if (used == 0) {
            return 0;
        }
        int remaining = available - used;
        if (remaining == 0) {
            inventory.remove(item);
        } else {
            inventory.put(item, remaining);
        }
        return used;
    }

    private static void addCount(Map<Item, Integer> inventory, Item item, int count) {
        if (count <= 0) {
            return;
        }
        inventory.merge(item, count, Integer::sum);
    }

    private static Map<Item, Integer> sanitizeInventory(Map<Item, Integer> inventory) {
        Map<Item, Integer> sanitized = new HashMap<>();
        for (Map.Entry<Item, Integer> entry : inventory.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public interface RecipeLookup {
        List<NormalizedRecipe> getRecipes(Item item);
    }

    public interface SourceLookup {
        DirectSource lookup(Item item);
    }

    public static final class DirectSource {
        public final List<Block> blocks;
        public final List<BlockPos> knownPositions;
        public final boolean knownInWorld;
        public final double estimatedPathCost;

        public DirectSource(List<Block> blocks, List<BlockPos> knownPositions, double estimatedPathCost) {
            List<Block> sortedBlocks = new ArrayList<>(new LinkedHashSet<>(blocks));
            sortedBlocks.sort(Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString()));
            this.blocks = Collections.unmodifiableList(sortedBlocks);
            this.knownPositions = Collections.unmodifiableList(new ArrayList<>(knownPositions));
            this.knownInWorld = !knownPositions.isEmpty();
            this.estimatedPathCost = estimatedPathCost;
        }

        public static DirectSource none() {
            return new DirectSource(Collections.emptyList(), Collections.emptyList(), Double.POSITIVE_INFINITY);
        }

        public boolean hasConcreteBlocks() {
            return !blocks.isEmpty();
        }

        public String blockList() {
            return blocks.stream()
                    .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
                    .collect(Collectors.joining(", "));
        }
    }

    public enum StationKind {
        HAND_CRAFTING("inventory crafting"),
        CRAFTING_TABLE("crafting_table"),
        FURNACE("furnace"),
        BLAST_FURNACE("blast_furnace"),
        SMOKER("smoker"),
        CAMPFIRE("campfire");

        private final String displayName;

        StationKind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public static final class IngredientChoice {
        public final List<Item> options;
        public final int count;
        private final String lexicalKey;

        public IngredientChoice(Collection<Item> options, int count) {
            List<Item> sorted = new ArrayList<>(new HashSet<>(options));
            sorted.sort(Comparator.comparing(CraftingPlanner::itemId));
            this.options = Collections.unmodifiableList(sorted);
            this.count = count;
            this.lexicalKey = sorted.stream().map(CraftingPlanner::itemId).collect(Collectors.joining("|"));
        }

        public static IngredientChoice of(Item option, int count) {
            return new IngredientChoice(Collections.singletonList(option), count);
        }
    }

    public static final class NormalizedRecipe {
        public final ResourceLocation id;
        public final StationKind station;
        public final Item result;
        public final int outputCount;
        public final List<IngredientChoice> ingredients;
        public final boolean fuelNotPlanned;

        public NormalizedRecipe(ResourceLocation id, StationKind station, Item result, int outputCount, List<IngredientChoice> ingredients, boolean fuelNotPlanned) {
            this.id = Objects.requireNonNull(id);
            this.station = Objects.requireNonNull(station);
            this.result = Objects.requireNonNull(result);
            this.outputCount = outputCount;
            this.ingredients = Collections.unmodifiableList(new ArrayList<>(ingredients));
            this.fuelNotPlanned = fuelNotPlanned;
        }

        public static NormalizedRecipe crafting(ResourceLocation id, StationKind station, Item result, int outputCount, List<IngredientChoice> ingredients) {
            return new NormalizedRecipe(id, station, result, outputCount, ingredients, false);
        }

        public static NormalizedRecipe cooking(ResourceLocation id, StationKind station, Item result, int outputCount, IngredientChoice ingredient) {
            return new NormalizedRecipe(id, station, result, outputCount, Collections.singletonList(ingredient), true);
        }
    }

    public static final class PlanNode {
        public final Item item;
        public final int requiredCount;
        public final int usedFromInventory;
        public final Kind kind;
        public final NormalizedRecipe recipe;
        public final int crafts;
        public final int surplusProduced;
        public final List<PlanNode> children;
        public final DirectSource source;
        public final String unresolvedHint;
        public final PlanMetrics metrics;

        private PlanNode(Item item, int requiredCount, int usedFromInventory, Kind kind, NormalizedRecipe recipe, int crafts, int surplusProduced, List<PlanNode> children, DirectSource source, String unresolvedHint, PlanMetrics metrics) {
            this.item = item;
            this.requiredCount = requiredCount;
            this.usedFromInventory = usedFromInventory;
            this.kind = kind;
            this.recipe = recipe;
            this.crafts = crafts;
            this.surplusProduced = surplusProduced;
            this.children = Collections.unmodifiableList(children);
            this.source = source;
            this.unresolvedHint = unresolvedHint;
            this.metrics = metrics;
        }

        public static PlanNode inventory(Item item, int requiredCount, int usedFromInventory) {
            return new PlanNode(item, requiredCount, usedFromInventory, Kind.INVENTORY, null, 0, 0, Collections.emptyList(), DirectSource.none(), null, PlanMetrics.satisfied("inventory:" + CraftingPlanner.itemId(item)));
        }

        public static PlanNode source(Item item, int requiredCount, int usedFromInventory, DirectSource source) {
            return new PlanNode(
                    item,
                    requiredCount,
                    usedFromInventory,
                    Kind.SOURCE,
                    null,
                    0,
                    0,
                    Collections.emptyList(),
                    source,
                    null,
                    PlanMetrics.source(source, Math.max(1, requiredCount - usedFromInventory), "source:" + CraftingPlanner.itemId(item) + ":" + source.blockList())
            );
        }

        public static PlanNode unresolved(Item item, int requiredCount, int usedFromInventory, String hint) {
            return new PlanNode(item, requiredCount, usedFromInventory, Kind.UNRESOLVED, null, 0, 0, Collections.emptyList(), DirectSource.none(), hint, PlanMetrics.unresolved("unresolved:" + CraftingPlanner.itemId(item)));
        }

        public static PlanNode recipe(Item item, int requiredCount, int usedFromInventory, NormalizedRecipe recipe, int crafts, int surplusProduced, List<PlanNode> children) {
            return new PlanNode(item, requiredCount, usedFromInventory, Kind.RECIPE, recipe, crafts, surplusProduced, children, DirectSource.none(), null, PlanMetrics.recipe(recipe, children));
        }

        public int missingCount() {
            return Math.max(0, requiredCount - usedFromInventory);
        }

        public String itemId() {
            return CraftingPlanner.itemId(item);
        }

        public enum Kind {
            INVENTORY,
            RECIPE,
            SOURCE,
            UNRESOLVED
        }
    }

    public static final class PlanMetrics implements Comparable<PlanMetrics> {
        public final boolean fullySatisfied;
        public final int sourceRank;
        public final int unresolvedLeaves;
        public final double estimatedConcreteCost;
        public final String lexicalKey;

        private PlanMetrics(boolean fullySatisfied, int sourceRank, int unresolvedLeaves, double estimatedConcreteCost, String lexicalKey) {
            this.fullySatisfied = fullySatisfied;
            this.sourceRank = sourceRank;
            this.unresolvedLeaves = unresolvedLeaves;
            this.estimatedConcreteCost = estimatedConcreteCost;
            this.lexicalKey = lexicalKey;
        }

        public static PlanMetrics satisfied(String lexicalKey) {
            return new PlanMetrics(true, 0, 0, 0, lexicalKey);
        }

        public static PlanMetrics source(DirectSource source, int requiredUnits, String lexicalKey) {
            double estimatedConcreteCost = Double.POSITIVE_INFINITY;
            if (source.knownInWorld) {
                estimatedConcreteCost = source.estimatedPathCost * Math.max(1, requiredUnits);
            }
            return new PlanMetrics(false, source.knownInWorld ? 0 : 1, 0, estimatedConcreteCost, lexicalKey);
        }

        public static PlanMetrics unresolved(String lexicalKey) {
            return new PlanMetrics(false, 2, 1, Double.POSITIVE_INFINITY, lexicalKey);
        }

        public static PlanMetrics recipe(NormalizedRecipe recipe, List<PlanNode> children) {
            boolean fullySatisfied = true;
            int sourceRank = Integer.MAX_VALUE;
            int unresolvedLeaves = 0;
            double estimatedConcreteCost = 0.0D;
            boolean hasFiniteConcreteCost = false;
            String lexicalKey = recipe.id.toString();
            for (PlanNode child : children) {
                PlanMetrics metrics = child.metrics;
                unresolvedLeaves += metrics.unresolvedLeaves;
                if (Double.isFinite(metrics.estimatedConcreteCost)) {
                    estimatedConcreteCost += metrics.estimatedConcreteCost;
                    hasFiniteConcreteCost = true;
                } else if (!metrics.fullySatisfied) {
                    estimatedConcreteCost = Double.POSITIVE_INFINITY;
                }
                lexicalKey += "|" + metrics.lexicalKey;
                if (!metrics.fullySatisfied) {
                    fullySatisfied = false;
                    sourceRank = Math.min(sourceRank, metrics.sourceRank);
                }
            }
            if (fullySatisfied) {
                return satisfied("recipe:" + lexicalKey);
            }
            if (sourceRank == Integer.MAX_VALUE) {
                sourceRank = 2;
            }
            if (!hasFiniteConcreteCost && !fullySatisfied) {
                estimatedConcreteCost = Double.POSITIVE_INFINITY;
            }
            return new PlanMetrics(false, sourceRank, unresolvedLeaves, estimatedConcreteCost, "recipe:" + lexicalKey);
        }

        @Override
        public int compareTo(PlanMetrics other) {
            if (fullySatisfied != other.fullySatisfied) {
                return fullySatisfied ? -1 : 1;
            }
            int cmp = Integer.compare(sourceRank, other.sourceRank);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(unresolvedLeaves, other.unresolvedLeaves);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Double.compare(estimatedConcreteCost, other.estimatedConcreteCost);
            if (cmp != 0) {
                return cmp;
            }
            return lexicalKey.compareTo(other.lexicalKey);
        }
    }

    private static final class Resolution {
        private final PlanNode node;
        private final Map<Item, Integer> leftoverInventory;

        private Resolution(PlanNode node, Map<Item, Integer> leftoverInventory) {
            this.node = node;
            this.leftoverInventory = leftoverInventory;
        }
    }

    public static Map<Item, Integer> aggregateMissingLeaves(PlanNode root) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        accumulateMissing(root, counts);
        return counts;
    }

    private static void accumulateMissing(PlanNode node, Map<Item, Integer> counts) {
        if (node.kind == PlanNode.Kind.SOURCE || node.kind == PlanNode.Kind.UNRESOLVED) {
            counts.merge(node.item, node.missingCount(), Integer::sum);
            return;
        }
        for (PlanNode child : node.children) {
            accumulateMissing(child, counts);
        }
    }

    public static List<PlanNode> unresolvedLeaves(PlanNode root) {
        List<PlanNode> leaves = new ArrayList<>();
        collectLeaves(root, leaves, PlanNode.Kind.UNRESOLVED);
        return leaves;
    }

    public static List<PlanNode> sourceLeaves(PlanNode root) {
        List<PlanNode> leaves = new ArrayList<>();
        collectLeaves(root, leaves, PlanNode.Kind.SOURCE);
        return leaves;
    }

    private static void collectLeaves(PlanNode node, List<PlanNode> leaves, PlanNode.Kind targetKind) {
        if (node.kind == targetKind) {
            leaves.add(node);
            return;
        }
        for (PlanNode child : node.children) {
            collectLeaves(child, leaves, targetKind);
        }
    }

    public static PlanNode firstKnownSourceLeaf(PlanNode root) {
        if (root.kind == PlanNode.Kind.SOURCE && root.source.knownInWorld) {
            return root;
        }
        for (PlanNode child : root.children) {
            PlanNode found = firstKnownSourceLeaf(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public static PlanNode firstSourceLeaf(PlanNode root) {
        if (root.kind == PlanNode.Kind.SOURCE) {
            return root;
        }
        for (PlanNode child : root.children) {
            PlanNode found = firstSourceLeaf(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public static List<PlanNode> recipeNodes(PlanNode root) {
        List<PlanNode> recipes = new ArrayList<>();
        collectRecipes(root, recipes);
        return recipes;
    }

    public static PlanNode firstRecipeStep(PlanNode root) {
        if (root.kind == PlanNode.Kind.RECIPE) {
            for (PlanNode child : root.children) {
                PlanNode found = firstRecipeStep(child);
                if (found != null) {
                    return found;
                }
            }
            return root;
        }
        for (PlanNode child : root.children) {
            PlanNode found = firstRecipeStep(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void collectRecipes(PlanNode node, List<PlanNode> recipes) {
        if (node.kind == PlanNode.Kind.RECIPE) {
            recipes.add(node);
        }
        for (PlanNode child : node.children) {
            collectRecipes(child, recipes);
        }
    }

    public static List<String> formatTree(PlanNode root) {
        List<String> lines = new ArrayList<>();
        formatTree(root, 0, lines);
        return lines;
    }

    private static void formatTree(PlanNode node, int depth, List<String> lines) {
        String indent = "  ".repeat(Math.max(0, depth));
        String prefix = indent + node.itemId() + " x" + node.requiredCount;
        String inventory = node.usedFromInventory > 0 ? " [have " + node.usedFromInventory + "]" : "";
        switch (node.kind) {
            case INVENTORY:
                lines.add(prefix + inventory + " [inventory]");
                break;
            case SOURCE:
                lines.add(prefix + inventory + " [missing " + node.missingCount() + ", blocks: " + node.source.blockList() + "]");
                break;
            case UNRESOLVED:
                lines.add(prefix + inventory + " [missing " + node.missingCount() + ", unresolved]");
                break;
            case RECIPE:
                String recipeDetail = node.recipe.station.displayName() + " via " + node.recipe.id + " x" + node.crafts;
                if (node.recipe.fuelNotPlanned) {
                    recipeDetail += " (fuel not planned)";
                }
                if (node.surplusProduced > 0) {
                    recipeDetail += ", surplus " + node.surplusProduced;
                }
                lines.add(prefix + inventory + " [missing " + node.missingCount() + ", " + recipeDetail + "]");
                for (PlanNode child : node.children) {
                    formatTree(child, depth + 1, lines);
                }
                break;
            default:
                break;
        }
    }
}

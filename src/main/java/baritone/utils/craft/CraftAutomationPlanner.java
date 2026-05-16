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
import baritone.pathing.movement.CalculationContext;
import baritone.process.MineProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CraftAutomationPlanner {

    private final Baritone baritone;
    private final CraftingPlanner planner;
    private final Map<Block, Set<BlockPos>> rememberedStations;

    public CraftAutomationPlanner(Baritone baritone, CraftingPlanner planner) {
        this.baritone = Objects.requireNonNull(baritone);
        this.planner = Objects.requireNonNull(planner);
        this.rememberedStations = new HashMap<>();
    }

    public AutomationPlan plan(Item target, int count, Map<Item, Integer> inventory) {
        Map<Item, Integer> sanitized = sanitizeInventory(inventory);
        CraftingPlanner.PlanNode root = planner.plan(target, count, sanitized);
        Set<Item> stack = new LinkedHashSet<>();
        stack.add(target);
        return new AutomationPlan(root, decide(root, sanitized, stack, null));
    }

    private Action decide(CraftingPlanner.PlanNode root, Map<Item, Integer> inventory, Set<Item> stack, String context) {
        if (inventory.getOrDefault(root.item, 0) >= root.requiredCount) {
            return contextualize(Action.done("already satisfied in inventory"), context);
        }

        CraftingPlanner.PlanNode craftable = deepestCraftableRecipe(root, inventory);
        if (craftable != null) {
            Action station = ensureStation(craftable.recipe.station, inventory, stack, craftable.itemId());
            if (station != null) {
                return contextualize(station, context);
            }
            Action fuel = ensureFuel(craftable, inventory, stack);
            if (fuel != null) {
                return contextualize(fuel, context);
            }
            return contextualize(Action.craft(craftable, describeRecipe(craftable)), context);
        }

        CraftingPlanner.PlanNode nextSource = CraftingPlanner.firstKnownSourceLeaf(root);
        if (nextSource == null) {
            nextSource = CraftingPlanner.firstSourceLeaf(root);
        }
        if (nextSource != null) {
            Action tool = ensureTool(nextSource, inventory, stack);
            if (tool != null) {
                return contextualize(tool, context);
            }
        }

        CraftingPlanner.PlanNode firstRecipe = CraftingPlanner.firstRecipeStep(root);
        if (firstRecipe != null) {
            Action station = ensureStation(firstRecipe.recipe.station, inventory, stack, firstRecipe.itemId());
            if (station != null) {
                return contextualize(station, context);
            }
            Action fuel = ensureFuel(firstRecipe, inventory, stack);
            if (fuel != null) {
                return contextualize(fuel, context);
            }
        }

        if (nextSource != null) {
            Block representative = selectRepresentativeSourceBlock(nextSource, inventory);
            if (!Baritone.settings().allowBreak.value
                    && nextSource.source.blocks.stream().noneMatch(block -> Baritone.settings().allowBreakAnyway.value.contains(block))) {
                return contextualize(Action.blocked("allowBreak is false for " + nextSource.itemId()), context);
            }
            MineProcess.LegitMineMode mineMode = chooseMineMode(representative);
            return contextualize(Action.gather(nextSource, representative, mineMode, describeGather(nextSource, representative, mineMode)), context);
        }

        List<CraftingPlanner.PlanNode> unresolved = CraftingPlanner.unresolvedLeaves(root);
        if (!unresolved.isEmpty()) {
            CraftingPlanner.PlanNode blocked = unresolved.get(0);
            return contextualize(Action.blocked("unresolved " + blocked.itemId() + " x" + blocked.missingCount() + " - " + blocked.unresolvedHint), context);
        }

        if (firstRecipe != null) {
            return contextualize(Action.blocked("no actionable step found for " + firstRecipe.itemId()), context);
        }

        return contextualize(Action.blocked("no actionable craft step found"), context);
    }

    private Action ensureTool(CraftingPlanner.PlanNode sourceNode, Map<Item, Integer> inventory, Set<Item> stack) {
        Block representative = selectRepresentativeSourceBlock(sourceNode, inventory);
        BlockState state = representative.defaultBlockState();
        if (ToolRequirementHelper.hasMatchingTool(inventory, state)) {
            return null;
        }
        ItemStack requiredTool = ToolRequirementHelper.minimumRequiredTool(state);
        if (requiredTool.isEmpty()) {
            return null;
        }
        Item requiredItem = requiredTool.getItem();
        if (stack.contains(requiredItem)) {
            return Action.blocked("cyclic tool prerequisite " + itemId(requiredItem) + " for " + blockId(representative));
        }
        Set<Item> next = new LinkedHashSet<>(stack);
        next.add(requiredItem);
        CraftingPlanner.PlanNode prerequisite = planner.plan(requiredItem, 1, inventory);
        return decide(prerequisite, inventory, next,
                String.format(Locale.US, "tool prerequisite %s for %s", itemId(requiredItem), sourceNode.itemId()));
    }

    private Action ensureStation(CraftingPlanner.StationKind station, Map<Item, Integer> inventory, Set<Item> stack, String forItem) {
        if (station == CraftingPlanner.StationKind.HAND_CRAFTING) {
            return null;
        }
        if (station == CraftingPlanner.StationKind.CAMPFIRE) {
            return Action.blocked("campfire automation is unsupported for " + forItem);
        }
        if (currentMenuMatches(station)) {
            return null;
        }

        Block stationBlock = stationBlock(station);
        Item stationItem = stationItem(station);
        List<BlockPos> known = findKnownBlocks(stationBlock, 32);
        if (!known.isEmpty()) {
            return Action.openStation(station, stationBlock, stationItem, known.get(0),
                    "use nearby " + station.displayName() + " at " + formatPos(known.get(0)));
        }
        if (inventory.getOrDefault(stationItem, 0) > 0) {
            return Action.placeStation(station, stationBlock, stationItem,
                    "place carried " + itemId(stationItem));
        }
        if (!Baritone.settings().allowInventory.value) {
            return Action.blocked("allowInventory is false for station prerequisite " + itemId(stationItem));
        }
        if (stack.contains(stationItem)) {
            return Action.blocked("cyclic station prerequisite " + itemId(stationItem));
        }
        Set<Item> next = new LinkedHashSet<>(stack);
        next.add(stationItem);
        CraftingPlanner.PlanNode prerequisite = planner.plan(stationItem, 1, inventory, Collections.singleton(station));
        return decide(prerequisite, inventory, next,
                String.format(Locale.US, "station prerequisite %s", itemId(stationItem)));
    }

    private Action ensureFuel(CraftingPlanner.PlanNode recipeNode, Map<Item, Integer> inventory, Set<Item> stack) {
        if (!recipeNode.recipe.fuelNotPlanned) {
            return null;
        }
        if (recipeNode.recipe.station == CraftingPlanner.StationKind.CAMPFIRE) {
            return Action.blocked("campfire automation is unsupported for " + recipeNode.itemId());
        }
        int operationsNeeded = Math.max(1, recipeNode.crafts);
        if (hasEnoughSimpleFuel(inventory, operationsNeeded)) {
            return null;
        }
        if (!Baritone.settings().allowInventory.value) {
            return Action.blocked("allowInventory is false for furnace fuel handling");
        }
        FuelTarget fuelTarget = chooseFuelTarget(inventory, operationsNeeded, stack);
        if (fuelTarget == null) {
            return Action.blocked("no simple fuel source is currently known for " + recipeNode.itemId());
        }
        Item fuelItem = fuelTarget.item;
        if (stack.contains(fuelItem)) {
            return Action.blocked("cyclic fuel prerequisite " + itemId(fuelItem));
        }
        Set<Item> next = new LinkedHashSet<>(stack);
        next.add(fuelItem);
        CraftingPlanner.PlanNode prerequisite = planner.plan(fuelItem, fuelTarget.count, inventory);
        return decide(prerequisite, inventory, next,
                String.format(Locale.US, "fuel prerequisite %s x%d for %s", itemId(fuelItem), fuelTarget.count, recipeNode.itemId()));
    }

    private CraftingPlanner.PlanNode deepestCraftableRecipe(CraftingPlanner.PlanNode node, Map<Item, Integer> inventory) {
        if (node.kind == CraftingPlanner.PlanNode.Kind.RECIPE) {
            for (CraftingPlanner.PlanNode child : node.children) {
                CraftingPlanner.PlanNode found = deepestCraftableRecipe(child, inventory);
                if (found != null) {
                    return found;
                }
            }
            if (isRecipeCraftableNow(node, inventory)) {
                return node;
            }
        } else {
            for (CraftingPlanner.PlanNode child : node.children) {
                CraftingPlanner.PlanNode found = deepestCraftableRecipe(child, inventory);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean isRecipeCraftableNow(CraftingPlanner.PlanNode node, Map<Item, Integer> inventory) {
        if (node.kind != CraftingPlanner.PlanNode.Kind.RECIPE || node.children.size() != node.recipe.ingredients.size()) {
            return false;
        }
        Map<Item, Integer> available = new HashMap<>(inventory);
        for (int i = 0; i < node.children.size(); i++) {
            Item ingredientItem = node.children.get(i).item;
            int required = node.recipe.ingredients.get(i).count;
            int have = available.getOrDefault(ingredientItem, 0);
            if (have < required) {
                return false;
            }
            if (have == required) {
                available.remove(ingredientItem);
            } else {
                available.put(ingredientItem, have - required);
            }
        }
        return true;
    }

    private boolean currentMenuMatches(CraftingPlanner.StationKind station) {
        AbstractContainerMenu menu = baritone.getPlayerContext().player().containerMenu;
        switch (station) {
            case HAND_CRAFTING:
                return menu instanceof InventoryMenu;
            case CRAFTING_TABLE:
                return menu instanceof CraftingMenu;
            case FURNACE:
                return menu instanceof FurnaceMenu;
            case BLAST_FURNACE:
                return menu instanceof BlastFurnaceMenu;
            case SMOKER:
                return menu instanceof SmokerMenu;
            case CAMPFIRE:
            default:
                return false;
        }
    }

    private List<BlockPos> findKnownBlocks(Block block, int max) {
        LinkedHashSet<BlockPos> merged = new LinkedHashSet<>();
        rememberValidStations(block, merged);
        merged.addAll(MineProcess.searchWorld(
                new CalculationContext(baritone),
                new baritone.api.utils.BlockOptionalMetaLookup(block),
                max,
                new ArrayList<>(),
                new ArrayList<>(),
                Collections.emptyList()
        ));
        List<BlockPos> found = new ArrayList<>(merged);
        found.sort(Comparator.comparingDouble(baritone.getPlayerContext().playerFeet()::distSqr));
        if (found.size() > max) {
            return new ArrayList<>(found.subList(0, max));
        }
        return found;
    }

    public void rememberStation(Block block, BlockPos pos) {
        rememberedStations.computeIfAbsent(block, ignored -> new LinkedHashSet<>()).add(pos.immutable());
    }

    public List<BlockPos> knownStations(Block block, int max) {
        return findKnownBlocks(block, max);
    }

    private void rememberValidStations(Block block, Set<BlockPos> sink) {
        Set<BlockPos> remembered = rememberedStations.get(block);
        if (remembered == null || remembered.isEmpty()) {
            return;
        }
        if (baritone.getPlayerContext().world() == null) {
            sink.addAll(remembered);
            return;
        }
        remembered.removeIf(pos -> baritone.getPlayerContext().world().getBlockState(pos).getBlock() != block);
        sink.addAll(remembered);
    }

    private boolean hasEnoughSimpleFuel(Map<Item, Integer> inventory, int operationsNeeded) {
        double availableOperations = 0.0D;
        for (Map.Entry<Item, Integer> entry : inventory.entrySet()) {
            Integer count = entry.getValue();
            if (count == null || count <= 0) {
                continue;
            }
            availableOperations += fuelCapacity(entry.getKey()) * count;
            if (availableOperations + 1.0E-6D >= operationsNeeded) {
                return true;
            }
        }
        return false;
    }

    private FuelTarget chooseFuelTarget(Map<Item, Integer> inventory, int operationsNeeded, Set<Item> stack) {
        List<Item> candidates = new ArrayList<>();
        candidates.add(Items.COAL);
        candidates.add(Items.CHARCOAL);
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                continue;
            }
            String path = id.getPath();
            if (path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_planks")) {
                candidates.add(item);
            }
        }
        FuelTarget best = null;
        CraftingPlanner.PlanMetrics bestMetrics = null;
        for (Item candidate : candidates) {
            if (stack.contains(candidate)) {
                continue;
            }
            int count = fuelItemCount(candidate, operationsNeeded);
            if (count <= 0) {
                continue;
            }
            if (inventory.getOrDefault(candidate, 0) >= count) {
                return new FuelTarget(candidate, count);
            }
            CraftingPlanner.PlanNode plan = planner.plan(candidate, count, inventory);
            if (plan.kind == CraftingPlanner.PlanNode.Kind.UNRESOLVED) {
                continue;
            }
            if (best == null || plan.metrics.compareTo(bestMetrics) < 0) {
                best = new FuelTarget(candidate, count);
                bestMetrics = plan.metrics;
            }
        }
        return best;
    }

    private boolean isSimpleFuelItem(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL || item == Items.STICK) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_planks");
    }

    static int fuelItemCount(Item item, int operationsNeeded) {
        double capacity = fuelCapacity(item);
        if (capacity <= 0.0D) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil(operationsNeeded / capacity));
    }

    static double fuelCapacity(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL) {
            return 8.0D;
        }
        if (item == Items.STICK) {
            return 0.5D;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            return 0.0D;
        }
        String path = id.getPath();
        if (path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_planks")) {
            return 1.5D;
        }
        return 0.0D;
    }

    private Block selectRepresentativeSourceBlock(CraftingPlanner.PlanNode sourceNode, Map<Item, Integer> inventory) {
        for (Block block : sourceNode.source.blocks) {
            if (ToolRequirementHelper.hasMatchingTool(inventory, block.defaultBlockState())) {
                return block;
            }
        }
        if (!sourceNode.source.blocks.isEmpty()) {
            return sourceNode.source.blocks.get(0);
        }
        return Blocks.AIR;
    }

    private MineProcess.LegitMineMode chooseMineMode(Block block) {
        if (!Baritone.settings().legitMine.value) {
            return MineProcess.LegitMineMode.FORCE_NORMAL;
        }
        return isOreLike(block) ? MineProcess.LegitMineMode.FORCE_LEGIT : MineProcess.LegitMineMode.FORCE_NORMAL;
    }

    static boolean isOreLike(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.endsWith("_ore") || "ancient_debris".equals(path);
    }

    private Action contextualize(Action action, String context) {
        if (context == null || context.isEmpty()) {
            return action;
        }
        return action.withContext(context);
    }

    private String describeRecipe(CraftingPlanner.PlanNode recipeNode) {
        return "craft " + recipeNode.itemId() + " via " + recipeNode.recipe.station.displayName();
    }

    private String describeGather(CraftingPlanner.PlanNode sourceNode, Block block, MineProcess.LegitMineMode mineMode) {
        String mode = mineMode == MineProcess.LegitMineMode.FORCE_LEGIT ? "legit mine" : "normal mine";
        return "gather " + sourceNode.itemId() + " x" + sourceNode.missingCount() + " from " + blockId(block) + " using " + mode;
    }

    private Block stationBlock(CraftingPlanner.StationKind station) {
        switch (station) {
            case CRAFTING_TABLE:
                return Blocks.CRAFTING_TABLE;
            case FURNACE:
                return Blocks.FURNACE;
            case BLAST_FURNACE:
                return Blocks.BLAST_FURNACE;
            case SMOKER:
                return Blocks.SMOKER;
            default:
                throw new IllegalArgumentException("Unsupported station " + station);
        }
    }

    private Item stationItem(CraftingPlanner.StationKind station) {
        switch (station) {
            case CRAFTING_TABLE:
                return Items.CRAFTING_TABLE;
            case FURNACE:
                return Items.FURNACE;
            case BLAST_FURNACE:
                return Items.BLAST_FURNACE;
            case SMOKER:
                return Items.SMOKER;
            default:
                throw new IllegalArgumentException("Unsupported station " + station);
        }
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
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? item.toString() : id.toString();
    }

    private static String blockId(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? block.toString() : id.toString();
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public static final class AutomationPlan {
        public final CraftingPlanner.PlanNode root;
        public final Action action;

        private AutomationPlan(CraftingPlanner.PlanNode root, Action action) {
            this.root = root;
            this.action = action;
        }
    }

    public static final class Action {
        public final Kind kind;
        public final String description;
        public final CraftingPlanner.PlanNode recipeNode;
        public final CraftingPlanner.PlanNode sourceNode;
        public final CraftingPlanner.StationKind station;
        public final Block stationBlock;
        public final Item stationItem;
        public final BlockPos stationPos;
        public final Block representativeSourceBlock;
        public final MineProcess.LegitMineMode mineMode;

        private Action(Kind kind, String description, CraftingPlanner.PlanNode recipeNode, CraftingPlanner.PlanNode sourceNode,
                       CraftingPlanner.StationKind station, Block stationBlock, Item stationItem, BlockPos stationPos,
                       Block representativeSourceBlock, MineProcess.LegitMineMode mineMode) {
            this.kind = kind;
            this.description = description;
            this.recipeNode = recipeNode;
            this.sourceNode = sourceNode;
            this.station = station;
            this.stationBlock = stationBlock;
            this.stationItem = stationItem;
            this.stationPos = stationPos;
            this.representativeSourceBlock = representativeSourceBlock;
            this.mineMode = mineMode;
        }

        public static Action done(String description) {
            return new Action(Kind.DONE, description, null, null, null, null, null, null, null, null);
        }

        public static Action blocked(String description) {
            return new Action(Kind.BLOCKED, description, null, null, null, null, null, null, null, null);
        }

        public static Action craft(CraftingPlanner.PlanNode recipeNode, String description) {
            return new Action(Kind.CRAFT, description, recipeNode, null, recipeNode.recipe.station, null, null, null, null, null);
        }

        public static Action gather(CraftingPlanner.PlanNode sourceNode, Block representativeSourceBlock, MineProcess.LegitMineMode mineMode, String description) {
            return new Action(Kind.GATHER, description, null, sourceNode, null, null, null, null, representativeSourceBlock, mineMode);
        }

        public static Action openStation(CraftingPlanner.StationKind station, Block stationBlock, Item stationItem, BlockPos stationPos, String description) {
            return new Action(Kind.OPEN_STATION, description, null, null, station, stationBlock, stationItem, stationPos, null, null);
        }

        public static Action placeStation(CraftingPlanner.StationKind station, Block stationBlock, Item stationItem, String description) {
            return new Action(Kind.PLACE_STATION, description, null, null, station, stationBlock, stationItem, null, null, null);
        }

        public Action withContext(String context) {
            return new Action(kind, context + ": " + description, recipeNode, sourceNode, station, stationBlock, stationItem, stationPos, representativeSourceBlock, mineMode);
        }
    }

    public enum Kind {
        DONE,
        BLOCKED,
        CRAFT,
        GATHER,
        OPEN_STATION,
        PLACE_STATION
    }

    private static final class FuelTarget {
        private final Item item;
        private final int count;

        private FuelTarget(Item item, int count) {
            this.item = item;
            this.count = count;
        }
    }
}

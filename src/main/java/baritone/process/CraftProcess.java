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

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.input.Input;
import baritone.behavior.InventoryBehavior;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.craft.CraftAutomationPlanner;
import baritone.utils.craft.CraftingPlanner;
import baritone.utils.craft.MinecraftRecipeCatalog;
import baritone.utils.craft.MinecraftSourceLookup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public final class CraftProcess extends BaritoneProcessHelper {

    private Item targetItem;
    private int targetCount;
    private Level plannerWorld;
    private CraftingPlanner planner;
    private CraftAutomationPlanner automationPlanner;
    private boolean delegatingMine;
    private boolean delegatingGetTo;
    private PendingCraft pendingCraft;
    private WaitingCooking waitingCooking;
    private String lastLoggedStep;
    private List<Block> savedBlocksToDisallowBreaking;

    public CraftProcess(Baritone baritone) {
        super(baritone);
    }

    public void craft(Item item, int count) {
        this.targetItem = item;
        this.targetCount = count;
        this.delegatingMine = false;
        this.delegatingGetTo = false;
        this.pendingCraft = null;
        this.waitingCooking = null;
        this.lastLoggedStep = null;
        this.savedBlocksToDisallowBreaking = null;
    }

    public CraftAutomationPlanner.AutomationPlan analyze(Item item, int count) {
        ensurePlanner();
        return automationPlanner.plan(item, count, snapshotInventory(ctx.player()));
    }

    @Override
    public boolean isActive() {
        return targetItem != null && targetCount > 0;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!isActive()) {
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (ctx.player() == null || ctx.world() == null) {
            return stopWith("Craft automation requires a loaded player and world");
        }
        ensurePlanner();

        if (pendingCraft != null) {
            return handlePendingCraft();
        }
        if (waitingCooking != null) {
            return handleWaitingCooking();
        }
        if (delegatingMine) {
            if (baritone.getMineProcess().isActive()) {
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
            delegatingMine = false;
            restoreBlocksToDisallowBreaking();
        }
        if (delegatingGetTo) {
            if (baritone.getGetToBlockProcess().isActive()) {
                return new PathingCommand(null, PathingCommandType.DEFER);
            }
            delegatingGetTo = false;
        }

        Map<Item, Integer> inventory = snapshotInventory(ctx.player());
        CraftAutomationPlanner.AutomationPlan plan = automationPlanner.plan(targetItem, targetCount, inventory);
        switch (plan.action.kind) {
            case DONE:
                logDirect("Craft complete: " + itemId(targetItem) + " x" + targetCount);
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            case BLOCKED:
                return stopWith("Craft blocked: " + plan.action.description);
            case GATHER:
                return beginGather(plan.action);
            case OPEN_STATION:
                return openStation(plan.action);
            case PLACE_STATION:
                return placeStation(plan.action);
            case CRAFT:
                return executeCraft(plan.action);
            default:
                return stopWith("Craft blocked: unexpected action " + plan.action.kind);
        }
    }

    @Override
    public void onLostControl() {
        targetItem = null;
        targetCount = 0;
        delegatingMine = false;
        delegatingGetTo = false;
        pendingCraft = null;
        waitingCooking = null;
        lastLoggedStep = null;
        restoreBlocksToDisallowBreaking();
        savedBlocksToDisallowBreaking = null;
        plannerWorld = null;
        planner = null;
        automationPlanner = null;
    }

    @Override
    public String displayName0() {
        return "craft " + itemId(targetItem) + " x" + targetCount;
    }

    @Override
    public double priority() {
        return 2.0D;
    }

    private PathingCommand beginGather(CraftAutomationPlanner.Action action) {
        List<Block> blocks = action.sourceNode.source.blocks;
        if (blocks.isEmpty()) {
            return stopWith("Craft blocked: no source blocks for " + action.sourceNode.itemId());
        }
        int quantity = Math.max(1, action.sourceNode.missingCount());
        logStep(action.description);
        BlockOptionalMeta[] filter = blocks.stream().map(BlockOptionalMeta::new).toArray(BlockOptionalMeta[]::new);
        applyBlocksToDisallowBreakingOverride(blocks);
        baritone.getMineProcess().mine(quantity, action.mineMode, new baritone.api.utils.BlockOptionalMetaLookup(filter), action.sourceNode.item);
        delegatingMine = true;
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private PathingCommand openStation(CraftAutomationPlanner.Action action) {
        if (currentMenuMatches(action.station)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            ctx.player().closeContainer();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (action.stationPos != null && canReach(action.stationPos)) {
            if (!ensureInteractionHand()) {
                return stopWith("Craft blocked: unable to free a non-block interaction slot");
            }
            InteractionResult result = ctx.playerController().processRightClickBlock(
                    ctx.player(),
                    ctx.world(),
                    InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(action.stationPos), Direction.UP, action.stationPos, false)
            );
            if (result.consumesAction()) {
                automationPlanner.rememberStation(action.stationBlock, action.stationPos);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        logStep(action.description);
        baritone.getGetToBlockProcess().getToBlock(new BlockOptionalMeta(action.stationBlock));
        delegatingGetTo = true;
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    private PathingCommand placeStation(CraftAutomationPlanner.Action action) {
        if (!Baritone.settings().allowPlace.value) {
            return stopWith("Craft blocked: allowPlace is false for " + itemId(action.stationItem));
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            ctx.player().closeContainer();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        Optional<BlockPos> placement = findPlacementSpot(action.station);
        if (placement.isEmpty()) {
            return stopWith("Craft blocked: no safe nearby spot to place " + itemId(action.stationItem));
        }
        if (!selectHotbarItem(action.stationItem)) {
            return stopWith("Craft blocked: unable to select " + itemId(action.stationItem) + " on the hotbar");
        }
        BlockPos placeAt = placement.get();
        BlockPos support = placeAt.below();
        logStep(action.description + " at " + formatPos(placeAt));
        InteractionResult result = ctx.playerController().processRightClickBlock(
                ctx.player(),
                ctx.world(),
                InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false)
        );
        if (result.consumesAction() || ctx.world().getBlockState(placeAt).getBlock() == action.stationBlock) {
            automationPlanner.rememberStation(action.stationBlock, placeAt);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return stopWith("Craft blocked: failed to place " + itemId(action.stationItem));
    }

    private PathingCommand executeCraft(CraftAutomationPlanner.Action action) {
        if (!Baritone.settings().allowInventory.value) {
            return stopWith("Craft blocked: allowInventory is false for recipe execution");
        }
        CraftingPlanner.PlanNode recipeNode = action.recipeNode;
        if (recipeNode == null || recipeNode.recipe == null) {
            return stopWith("Craft blocked: missing recipe node");
        }
        if (!currentMenuMatches(recipeNode.recipe.station)) {
            if (recipeNode.recipe.station == CraftingPlanner.StationKind.HAND_CRAFTING) {
                if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
                    ctx.player().closeContainer();
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        RecipeDisplayId displayId = findRecipeDisplayId(recipeNode.item);
        if (displayId == null) {
            return stopWith("Craft blocked: recipe book has no recipe producing " + recipeNode.itemId());
        }
        logStep(action.description);
        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (menu instanceof AbstractFurnaceMenu furnaceMenu) {
            if (!loadFurnaceRecipe(furnaceMenu, recipeNode)) {
                return stopWith("Craft blocked: unable to load furnace ingredients or fuel for " + recipeNode.itemId());
            }
            waitingCooking = new WaitingCooking(recipeNode.recipe.station, recipeNode.item, recipeNode.recipe.id, recipeNode.missingCount());
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        ctx.playerController().handlePlaceRecipe(menu.containerId, displayId, false);
        pendingCraft = new PendingCraft(recipeNode.recipe.station, recipeNode.item, menu.containerId);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand handlePendingCraft() {
        if (!currentMenuMatches(pendingCraft.station)) {
            pendingCraft = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        AbstractContainerMenu menu = ctx.player().containerMenu;
        int resultSlotIndex = resultSlot(menu);
        ItemStack result = menu.slots.get(resultSlotIndex).getItem();
        if (!result.isEmpty() && result.getItem() == pendingCraft.resultItem) {
            ctx.playerController().windowClick(menu.containerId, resultSlotIndex, 0, ClickType.QUICK_MOVE, ctx.player());
            pendingCraft = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (pendingCraft.waitTicks++ < 10) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        pendingCraft = null;
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand handleWaitingCooking() {
        if (!currentMenuMatches(waitingCooking.station)) {
            waitingCooking = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (!(ctx.player().containerMenu instanceof AbstractFurnaceMenu furnaceMenu)) {
            waitingCooking = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        ItemStack result = furnaceMenu.slots.get(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        if (!result.isEmpty() && result.getItem() == waitingCooking.resultItem) {
            ctx.playerController().windowClick(furnaceMenu.containerId, AbstractFurnaceMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, ctx.player());
            waitingCooking.remainingCount -= result.getCount();
            if (waitingCooking.remainingCount <= 0) {
                waitingCooking = null;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        ItemStack ingredient = furnaceMenu.slots.get(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
        ItemStack fuel = furnaceMenu.slots.get(AbstractFurnaceMenu.FUEL_SLOT).getItem();
        if (!ingredient.isEmpty() || !fuel.isEmpty() || furnaceMenu.isLit()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (waitingCooking.startupGraceTicks++ < 10) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        waitingCooking = null;
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean loadFurnaceRecipe(AbstractFurnaceMenu furnaceMenu, CraftingPlanner.PlanNode recipeNode) {
        if (recipeNode.children.isEmpty()) {
            return false;
        }
        if (!ensureMenuCarryEmpty(furnaceMenu)) {
            return false;
        }
        Item ingredientItem = recipeNode.children.get(0).item;
        int ingredientCount = Math.max(1, recipeNode.recipe.ingredients.get(0).count * recipeNode.crafts);
        if (!ensureFurnaceIngredient(furnaceMenu, ingredientItem, ingredientCount)) {
            return false;
        }
        return ensureFuelInFurnace(furnaceMenu, recipeNode.crafts);
    }

    private boolean ensureFurnaceIngredient(AbstractFurnaceMenu furnaceMenu, Item ingredientItem, int ingredientCount) {
        ItemStack slot = furnaceMenu.slots.get(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
        if (!slot.isEmpty() && slot.getItem() != ingredientItem) {
            ctx.playerController().windowClick(furnaceMenu.containerId, AbstractFurnaceMenu.INGREDIENT_SLOT, 0, ClickType.QUICK_MOVE, ctx.player());
            if (!ensureMenuCarryEmpty(furnaceMenu)) {
                return false;
            }
            slot = furnaceMenu.slots.get(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
        }
        int have = slot.isEmpty() ? 0 : slot.getCount();
        int need = ingredientCount - have;
        if (need <= 0) {
            return true;
        }
        return moveInventoryItemsToMenuSlot(furnaceMenu, ingredientItem, AbstractFurnaceMenu.INGREDIENT_SLOT, need);
    }

    private boolean ensureFuelInFurnace(AbstractFurnaceMenu furnaceMenu, int operationsNeeded) {
        ItemStack fuelSlot = furnaceMenu.slots.get(AbstractFurnaceMenu.FUEL_SLOT).getItem();
        if (!fuelSlot.isEmpty() && !isSimpleFuel(fuelSlot.getItem())) {
            ctx.playerController().windowClick(furnaceMenu.containerId, AbstractFurnaceMenu.FUEL_SLOT, 0, ClickType.QUICK_MOVE, ctx.player());
            if (!ensureMenuCarryEmpty(furnaceMenu)) {
                return false;
            }
            fuelSlot = furnaceMenu.slots.get(AbstractFurnaceMenu.FUEL_SLOT).getItem();
        }
        double availableFuel = fuelSlot.isEmpty() ? 0.0D : fuelCapacity(fuelSlot.getItem()) * fuelSlot.getCount();
        if (furnaceMenu.isLit()) {
            availableFuel += 1.0D;
        }
        if (availableFuel + 1.0E-6D >= operationsNeeded) {
            return true;
        }
        for (FuelCandidate candidate : findFuelCandidates(operationsNeeded)) {
            int need = fuelItemCount(candidate.item, (int) Math.ceil(Math.max(0.0D, operationsNeeded - availableFuel)));
            if (moveInventoryItemsToMenuSlot(furnaceMenu, candidate.item, AbstractFurnaceMenu.FUEL_SLOT, need)) {
                fuelSlot = furnaceMenu.slots.get(AbstractFurnaceMenu.FUEL_SLOT).getItem();
                availableFuel = fuelSlot.isEmpty() ? 0.0D : fuelCapacity(fuelSlot.getItem()) * fuelSlot.getCount();
                if (furnaceMenu.isLit()) {
                    availableFuel += 1.0D;
                }
                if (availableFuel + 1.0E-6D >= operationsNeeded) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean moveInventoryItemsToMenuSlot(AbstractContainerMenu menu, Item item, int targetSlot, int requiredCount) {
        if (requiredCount <= 0) {
            return true;
        }
        int remaining = requiredCount;
        for (int inventorySlot = 0; inventorySlot < ctx.player().getInventory().items.size() && remaining > 0; inventorySlot++) {
            ItemStack stack = ctx.player().getInventory().items.get(inventorySlot);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int menuSlot = findPlayerInventoryMenuSlot(menu, inventorySlot);
            if (menuSlot < 0) {
                continue;
            }
            if (!moveFromMenuSlot(menu, menuSlot, targetSlot, Math.min(remaining, stack.getCount()))) {
                return false;
            }
            ItemStack target = menu.slots.get(targetSlot).getItem();
            int have = target.isEmpty() ? 0 : target.getCount();
            remaining = requiredCount - have;
        }
        return remaining <= 0;
    }

    private boolean moveFromMenuSlot(AbstractContainerMenu menu, int sourceSlot, int targetSlot, int amount) {
        if (amount <= 0 || !ensureMenuCarryEmpty(menu)) {
            return amount <= 0;
        }
        ItemStack source = menu.slots.get(sourceSlot).getItem();
        if (source.isEmpty()) {
            return false;
        }
        int transfer = Math.min(amount, source.getCount());
        ctx.playerController().windowClick(menu.containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        if (transfer >= source.getCount()) {
            ctx.playerController().windowClick(menu.containerId, targetSlot, 0, ClickType.PICKUP, ctx.player());
            if (!menu.getCarried().isEmpty()) {
                ctx.playerController().windowClick(menu.containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
            }
            return menu.getCarried().isEmpty();
        }
        for (int i = 0; i < transfer; i++) {
            ctx.playerController().windowClick(menu.containerId, targetSlot, 1, ClickType.PICKUP, ctx.player());
        }
        if (!menu.getCarried().isEmpty()) {
            ctx.playerController().windowClick(menu.containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        }
        return menu.getCarried().isEmpty();
    }

    private boolean ensureMenuCarryEmpty(AbstractContainerMenu menu) {
        if (menu.getCarried().isEmpty()) {
            return true;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != ctx.player().getInventory()) {
                continue;
            }
            ItemStack slotItem = slot.getItem();
            ItemStack carried = menu.getCarried();
            if (slotItem.isEmpty() || (slotItem.getItem() == carried.getItem() && slotItem.getCount() < slot.getMaxStackSize())) {
                ctx.playerController().windowClick(menu.containerId, i, 0, ClickType.PICKUP, ctx.player());
                return menu.getCarried().isEmpty();
            }
        }
        return false;
    }

    private int findFuelInventorySlot() {
        NonNullList<ItemStack> items = ctx.player().getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i).getItem();
            if (!items.get(i).isEmpty() && isSimpleFuel(item)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSimpleFuel(Item item) {
        if (item == net.minecraft.world.item.Items.COAL
                || item == net.minecraft.world.item.Items.CHARCOAL
                || item == net.minecraft.world.item.Items.STICK) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_planks");
    }

    private List<FuelCandidate> findFuelCandidates(int operationsNeeded) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        addStacks(counts, ctx.player().getInventory().items);
        List<FuelCandidate> candidates = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
            Item item = entry.getKey();
            if (!isSimpleFuel(item)) {
                continue;
            }
            int count = fuelItemCount(item, operationsNeeded);
            if (count <= 0) {
                continue;
            }
            candidates.add(new FuelCandidate(item, count));
        }
        candidates.sort(java.util.Comparator
                .comparingInt((FuelCandidate candidate) -> candidate.requiredCount)
                .thenComparingDouble(candidate -> -fuelCapacity(candidate.item))
                .thenComparing(candidate -> itemId(candidate.item)));
        return candidates;
    }

    private int fuelItemCount(Item item, int operationsNeeded) {
        double capacity = fuelCapacity(item);
        if (capacity <= 0.0D) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil(operationsNeeded / capacity));
    }

    private double fuelCapacity(Item item) {
        if (item == net.minecraft.world.item.Items.COAL || item == net.minecraft.world.item.Items.CHARCOAL) {
            return 8.0D;
        }
        if (item == net.minecraft.world.item.Items.STICK) {
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

    private int findPlayerInventoryMenuSlot(AbstractContainerMenu menu, int inventorySlot) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == ctx.player().getInventory() && slot.getContainerSlot() == inventorySlot) {
                return i;
            }
        }
        return -1;
    }

    private int resultSlot(AbstractContainerMenu menu) {
        if (menu instanceof CraftingMenu craftingMenu) {
            return craftingMenu.getResultSlot().index;
        }
        return 0;
    }

    private RecipeDisplayId findRecipeDisplayId(Item resultItem) {
        LocalPlayer player = ctx.minecraft().player;
        if (player == null) {
            return null;
        }
        for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                SlotDisplay result = entry.display().result();
                if (result instanceof SlotDisplay.ItemStackSlotDisplay stackDisplay) {
                    if (stackDisplay.stack().getItem() == resultItem) {
                        return entry.id();
                    }
                } else if (result instanceof SlotDisplay.ItemSlotDisplay itemDisplay) {
                    if (itemDisplay.item().value() == resultItem) {
                        return entry.id();
                    }
                }
            }
        }
        return null;
    }

    private boolean currentMenuMatches(CraftingPlanner.StationKind station) {
        AbstractContainerMenu menu = ctx.player().containerMenu;
        switch (station) {
            case HAND_CRAFTING:
                return menu instanceof InventoryMenu;
            case CRAFTING_TABLE:
                return menu instanceof CraftingMenu;
            case FURNACE:
                return menu instanceof net.minecraft.world.inventory.FurnaceMenu;
            case BLAST_FURNACE:
                return menu instanceof net.minecraft.world.inventory.BlastFurnaceMenu;
            case SMOKER:
                return menu instanceof net.minecraft.world.inventory.SmokerMenu;
            case CAMPFIRE:
            default:
                return false;
        }
    }

    private boolean ensureInteractionHand() {
        ItemStack selected = ctx.player().getInventory().getSelected();
        if (selected.isEmpty() || !(selected.getItem() instanceof BlockItem)) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                ctx.player().getInventory().selected = i;
                return true;
            }
        }
        if (!Baritone.settings().allowInventory.value) {
            return false;
        }
        for (int i = 9; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().items.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                InventoryBehavior inventoryBehavior = baritone.getInventoryBehavior();
                OptionalInt slot = inventoryBehavior.attemptToPutOnHotbarAndGetSlot(i, ignored -> false);
                if (slot.isPresent()) {
                    ctx.player().getInventory().selected = slot.getAsInt();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private boolean selectHotbarItem(Item item) {
        NonNullList<ItemStack> items = ctx.player().getInventory().items;
        for (int i = 0; i < 9; i++) {
            if (!items.get(i).isEmpty() && items.get(i).getItem() == item) {
                ctx.player().getInventory().selected = i;
                return true;
            }
        }
        if (!Baritone.settings().allowInventory.value) {
            return false;
        }
        for (int i = 9; i < 36; i++) {
            if (!items.get(i).isEmpty() && items.get(i).getItem() == item) {
                OptionalInt slot = baritone.getInventoryBehavior().attemptToPutOnHotbarAndGetSlot(i, ignored -> false);
                if (slot.isPresent()) {
                    ctx.player().getInventory().selected = slot.getAsInt();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private Optional<BlockPos> findPlacementSpot(CraftingPlanner.StationKind station) {
        List<BlockPos> anchors = new ArrayList<>();
        if (station != CraftingPlanner.StationKind.CRAFTING_TABLE) {
            anchors.addAll(automationPlanner.knownStations(Blocks.CRAFTING_TABLE, 8));
        }
        anchors.add(ctx.playerFeet());
        for (BlockPos anchor : anchors) {
            Optional<BlockPos> nearAnchor = findPlacementSpotNear(anchor);
            if (nearAnchor.isPresent()) {
                return nearAnchor;
            }
        }
        return Optional.empty();
    }

    private Optional<BlockPos> findPlacementSpotNear(BlockPos anchor) {
        for (int radius = 1; radius <= 2; radius++) {
            for (BlockPos candidate : horizontalRing(anchor, radius)) {
                Optional<BlockPos> accepted = validatePlacementSpot(candidate);
                if (accepted.isPresent()) {
                    return accepted;
                }
            }
        }
        return Optional.empty();
    }

    private List<BlockPos> horizontalRing(BlockPos anchor, int radius) {
        List<BlockPos> positions = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                    continue;
                }
                positions.add(anchor.offset(dx, 0, dz));
            }
        }
        positions.sort(java.util.Comparator.comparingDouble(ctx.playerFeet()::distSqr));
        return positions;
    }

    private Optional<BlockPos> validatePlacementSpot(BlockPos placeAt) {
        BlockPos support = placeAt.below();
        BlockState placeState = ctx.world().getBlockState(placeAt);
        BlockState supportState = ctx.world().getBlockState(support);
        if (!placeState.canBeReplaced()) {
            return Optional.empty();
        }
        if (!supportState.isFaceSturdy(ctx.world(), support, Direction.UP)) {
            return Optional.empty();
        }
        return Optional.of(placeAt);
    }

    private List<BlockPos> findKnownBlocks(Block block, int max) {
        List<BlockPos> found = MineProcess.searchWorld(
                new CalculationContext(baritone),
                new BlockOptionalMetaLookup(block),
                max,
                new ArrayList<>(),
                new ArrayList<>(),
                java.util.Collections.emptyList()
        );
        found.sort(java.util.Comparator.comparingDouble(ctx.playerFeet()::distSqr));
        return found;
    }

    private boolean canReach(BlockPos pos) {
        return ctx.player().position().distanceToSqr(Vec3.atCenterOf(pos)) <= ctx.playerController().getBlockReachDistance() * ctx.playerController().getBlockReachDistance();
    }

    private void ensurePlanner() {
        if (planner != null && plannerWorld == ctx.world()) {
            return;
        }
        plannerWorld = ctx.world();
        planner = new CraftingPlanner(MinecraftRecipeCatalog.create(ctx.world()), new MinecraftSourceLookup(baritone));
        automationPlanner = new CraftAutomationPlanner(baritone, planner);
    }

    private void applyBlocksToDisallowBreakingOverride(List<Block> requiredBlocks) {
        if (savedBlocksToDisallowBreaking != null) {
            return;
        }
        List<Block> current = Baritone.settings().blocksToDisallowBreaking.value;
        if (current == null || current.isEmpty()) {
            return;
        }
        List<Block> updated = new ArrayList<>(current);
        if (!updated.removeIf(requiredBlocks::contains)) {
            return;
        }
        savedBlocksToDisallowBreaking = new ArrayList<>(current);
        Baritone.settings().blocksToDisallowBreaking.value = updated;
    }

    private void restoreBlocksToDisallowBreaking() {
        if (savedBlocksToDisallowBreaking == null) {
            return;
        }
        Baritone.settings().blocksToDisallowBreaking.value = savedBlocksToDisallowBreaking;
        savedBlocksToDisallowBreaking = null;
    }

    private PathingCommand stopWith(String reason) {
        logDirect(reason);
        onLostControl();
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private void logStep(String description) {
        if (!description.equals(lastLoggedStep)) {
            logDirect("Craft step: " + description);
            lastLoggedStep = description;
        }
    }

    private static Map<Item, Integer> snapshotInventory(LocalPlayer player) {
        Map<Item, Integer> inventory = new LinkedHashMap<>();
        addStacks(inventory, player.getInventory().items);
        addStacks(inventory, player.getInventory().offhand);
        return inventory;
    }

    private static void addStacks(Map<Item, Integer> inventory, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                inventory.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
    }

    private static String itemId(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? String.valueOf(item) : id.toString();
    }

    private static String formatPos(BlockPos pos) {
        return String.format(Locale.US, "%d %d %d", pos.getX(), pos.getY(), pos.getZ());
    }

    private static final class WaitingCooking {
        private final CraftingPlanner.StationKind station;
        private final Item resultItem;
        private final ResourceLocation recipeId;
        private int remainingCount;
        private int startupGraceTicks;

        private WaitingCooking(CraftingPlanner.StationKind station, Item resultItem, ResourceLocation recipeId, int remainingCount) {
            this.station = Objects.requireNonNull(station);
            this.resultItem = Objects.requireNonNull(resultItem);
            this.recipeId = Objects.requireNonNull(recipeId);
            this.remainingCount = remainingCount;
        }
    }

    private static final class PendingCraft {
        private final CraftingPlanner.StationKind station;
        private final Item resultItem;
        private final int containerId;
        private int waitTicks;

        private PendingCraft(CraftingPlanner.StationKind station, Item resultItem, int containerId) {
            this.station = Objects.requireNonNull(station);
            this.resultItem = Objects.requireNonNull(resultItem);
            this.containerId = containerId;
        }
    }

    private static final class FuelCandidate {
        private final Item item;
        private final int requiredCount;

        private FuelCandidate(Item item, int requiredCount) {
            this.item = Objects.requireNonNull(item);
            this.requiredCount = requiredCount;
        }
    }
}

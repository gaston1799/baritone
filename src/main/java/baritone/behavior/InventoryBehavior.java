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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.Helper;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.ToolSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.Predicate;

public final class InventoryBehavior extends Behavior implements Helper {

    private static final EquipmentSlot[] ARMOR_EQUIPMENT_SLOTS = new EquipmentSlot[] {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    int ticksSinceLastInventoryMove;
    int[] lastTickRequestedMove; // not everything asks every tick, so remember the request while coming to a halt
    private boolean autoEatHoldingUse;
    private long deathTime = -1L;

    public InventoryBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (Baritone.settings().autoRespawn.value && event.getType() == TickEvent.Type.IN) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof DeathScreen && mc.player != null) {
                if (deathTime < 0) deathTime = System.currentTimeMillis();
                if (System.currentTimeMillis() - deathTime >= Baritone.settings().autoRespawnTimeoutMs.value) {
                    mc.player.respawn();
                    deathTime = -1;
                }
                return;
            }
            deathTime = -1;
        }
        if (!Baritone.settings().allowInventory.value) {
            stopAutoEatUse();
            return;
        }
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            // we have a crafting table or a chest or something open
            stopAutoEatUse();
            return;
        }
        ticksSinceLastInventoryMove++;
        if (lastTickRequestedMove != null) {
            logDebug("Remembering to move " + lastTickRequestedMove[0] + " " + lastTickRequestedMove[1] + " from a previous tick");
            requestSwapWithHotBar(lastTickRequestedMove[0], lastTickRequestedMove[1]);
            return;
        }
        if (autoEat()) {
            return;
        }
        if (autoTotem()) {
            return;
        }
        if (baritone.getSelfDefenceBehavior().isMaceDiveActive()) {
            // The mace controller temporarily owns the chest slot while it
            // stows/restores the Elytra. Auto-armor must not fill that slot or
            // move the saved Elytra in the middle of a dive.
            return;
        }
        if (autoArmor()) {
            return;
        }
        if (dropTrashItems()) {
            return;
        }
        if (dropExcessAcceptableThrowawayItems()) {
            return;
        }
        if (isAutoEating()) {
            return;
        }
        if (firstValidThrowaway() >= 9) { // aka there are none on the hotbar, but there are some in main inventory
            requestSwapWithHotBar(firstValidThrowaway(), 8);
        }
        int pick = bestToolAgainst(Blocks.STONE);
        if (pick >= 9) {
            requestSwapWithHotBar(pick, 0);
        }
    }

    private boolean autoEat() {
        // Self-defence takes priority, except for critical survival items: a
        // golden apple at low health is still eaten while defending.
        boolean defending = baritone.getSelfDefenceBehavior().isDefending();
        if (!Baritone.settings().autoEat.value || (defending && !emergencyGoldenAppleAvailable())) {
            stopAutoEatUse();
            return false;
        }
        LocalPlayer player = ctx.player();
        int threshold = Math.max(0, Math.min(20, Baritone.settings().autoEatAtHunger.value));
        int targetHunger = autoEatTargetHunger(player, threshold);
        if (targetHunger <= player.getFoodData().getFoodLevel() && !player.isUsingItem()) {
            stopAutoEatUse();
            return false;
        }
        if (!player.canEat(false) && !player.isUsingItem()) {
            stopAutoEatUse();
            return false;
        }
        if (!player.isUsingItem() && !selectBestFood(targetHunger)) {
            stopAutoEatUse();
            return false;
        }
        if (player.isUsingItem() && !isSelectedFood(player)) {
            stopAutoEatUse();
            return false;
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        ctx.minecraft().options.keyUse.setDown(true);
        autoEatHoldingUse = true;
        if (!player.isUsingItem()) {
            ctx.playerController().syncHeldItem();
            ctx.playerController().processRightClick(player, ctx.world(), InteractionHand.MAIN_HAND);
        }
        return true;
    }

    public boolean isAutoEating() {
        return autoEatHoldingUse || (ctx.player() != null && MovementHelper.isConsumingItem(ctx));
    }

    /**
     * Golden apples are critical survival items: if we're below
     * autoEatGoldenAppleHealth and carry one, keep eating it even while
     * self-defence is active.
     */
    private boolean emergencyGoldenAppleAvailable() {
        LocalPlayer player = ctx.player();
        if (player == null || player.getHealth() >= Baritone.settings().autoEatGoldenAppleHealth.value) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.getItem() == Items.GOLDEN_APPLE || stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE) {
                return true;
            }
        }
        return false;
    }

    public boolean isEatingGoldenApple() {
        LocalPlayer player = ctx.player();
        if (player == null || !player.isUsingItem()) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        return held.getItem() == Items.GOLDEN_APPLE || held.getItem() == Items.ENCHANTED_GOLDEN_APPLE;
    }

    private boolean autoTotem() {
        if (!Baritone.settings().autoTotem.value) {
            return false;
        }
        if (ctx.player().getHealth() > Baritone.settings().autoTotemHealth.value) {
            return false;
        }
        ItemStack offhand = ctx.player().getItemBySlot(EquipmentSlot.OFFHAND);
        if (offhand.getItem() == Items.TOTEM_OF_UNDYING) {
            return false;
        }
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            return false;
        }
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int totemSlot = -1;
        for (int i = 0; i < invy.size(); i++) {
            if (invy.get(i).getItem() == Items.TOTEM_OF_UNDYING) {
                totemSlot = i;
                break;
            }
        }
        if (totemSlot == -1) {
            return false;
        }
        int sourceSlot = totemSlot < 9 ? totemSlot + 36 : totemSlot;
        int containerId = ctx.player().inventoryMenu.containerId;
        ctx.playerController().windowClick(containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, 45, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        ticksSinceLastInventoryMove = 0;
        return true;
    }

    private boolean autoArmor() {
        if (!Baritone.settings().autoArmor.value || isAutoEating()) {
            return false;
        }
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            return false;
        }
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (EquipmentSlot slot : ARMOR_EQUIPMENT_SLOTS) {
            int bestInventorySlot = bestArmorForSlot(invy, slot);
            if (bestInventorySlot == -1) {
                continue;
            }
            ItemStack candidate = invy.get(bestInventorySlot);
            ItemStack equipped = ctx.player().getItemBySlot(slot);
            if (armorScore(candidate) > armorScore(equipped) + 0.01D) {
                equipArmorFromInventory(bestInventorySlot, slot);
                return true;
            }
        }
        return false;
    }

    private int bestArmorForSlot(NonNullList<ItemStack> invy, EquipmentSlot slot) {
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (!isArmorForSlot(stack, slot)) {
                continue;
            }
            if (Baritone.settings().itemSaver.value && stack.getMaxDamage() > 1
                    && stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value >= stack.getMaxDamage()) {
                continue;
            }
            double score = armorScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private boolean isArmorForSlot(ItemStack stack, EquipmentSlot slot) {
        net.minecraft.world.item.equipment.Equippable equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        return !stack.isEmpty()

                && equippable != null
                && equippable.slot() == slot;
    }

    private double armorScore(ItemStack stack) {
        if (stack.isEmpty() || stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE) == null) {
            return 0.0D;
        }
        int protection = EnchantmentHelper.getItemEnchantmentLevel(ctx.player().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION), stack);
        return 1.0D
                + (protection * 1.5D);
    }

    private void equipArmorFromInventory(int inventorySlot, EquipmentSlot armorSlot) {
        int sourceSlot = inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
        int targetSlot = armorMenuSlot(armorSlot);
        int containerId = ctx.player().inventoryMenu.containerId;
        ctx.playerController().windowClick(containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, targetSlot, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        ticksSinceLastInventoryMove = 0;
    }

    private int armorMenuSlot(EquipmentSlot slot) {
        switch (slot) {
            case HEAD:
                return 5;
            case CHEST:
                return 6;
            case LEGS:
                return 7;
            case FEET:
                return 8;
            default:
                throw new IllegalArgumentException("Not an armor slot: " + slot);
        }
    }

    private int autoEatTargetHunger(LocalPlayer player, int threshold) {
        int hunger = player.getFoodData().getFoodLevel();
        int target = hunger <= threshold ? 20 : hunger;
        if (Baritone.settings().autoEatForHealth.value && player.getHealth() < player.getMaxHealth()) {
            int regenHunger = Math.max(0, Math.min(20, Baritone.settings().autoEatRegenHunger.value));
            if (hunger < regenHunger) {
                target = Math.max(target, regenHunger);
            }
            if (player.canEat(false)) {
                target = Math.max(target, hunger + 1);
            }
        }
        return target;
    }

    private void stopAutoEatUse() {
        if (!autoEatHoldingUse) {
            return;
        }
        ctx.minecraft().options.keyUse.setDown(false);
        autoEatHoldingUse = false;
    }

    private boolean isSelectedFood(LocalPlayer player) {
        ItemStack selected = player.getInventory().getSelectedItem();
        return isAutoEatFood(selected);
    }

    private boolean isAutoEatFood(ItemStack stack) {
        if (stack.isEmpty() || stack.get(net.minecraft.core.component.DataComponents.FOOD) == null) {
            return false;
        }
        if ((stack.getItem() == Items.GOLDEN_APPLE || stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE)
                && ctx.player().getHealth() >= Baritone.settings().autoEatGoldenAppleHealth.value) {
            return false;
        }
        return !Baritone.settings().dropTrashItems.value || !Baritone.settings().trashItems.value.contains(stack.getItem());
    }

    private boolean selectBestFood(int targetHunger) {
        LocalPlayer player = ctx.player();
        NonNullList<ItemStack> invy = player.getInventory().getNonEquipmentItems();
        FoodChoice best = null;
        int missingHunger = Math.max(1, targetHunger - player.getFoodData().getFoodLevel());
        boolean conserve = Baritone.settings().autoEatConserveFood.value
                && player.getHealth() < player.getMaxHealth()
                && missingHunger > 0;
        int highestNutrition = 0;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (!isAutoEatFood(stack)) {
                continue;
            }
            FoodProperties food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food == null) {
                continue;
            }
            highestNutrition = Math.max(highestNutrition, food.nutrition());
        }
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (!isAutoEatFood(stack)) {
                continue;
            }
            FoodProperties food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food == null) {
                continue;
            }
            FoodChoice candidate = new FoodChoice(i, food);
            if (best == null || betterFood(candidate, best, conserve && missingHunger < highestNutrition, missingHunger)) {
                best = candidate;
            }
        }
        if (best == null) {
            return false;
        }
        if (best.slot < 9) {
            player.getInventory().setSelectedSlot(best.slot);
            ctx.playerController().syncHeldItem();
            return true;
        }
        OptionalInt hotbarSlot = attemptToPutOnHotbarAndGetSlot(best.slot, slot -> slot == 0 || slot == 8);
        if (hotbarSlot.isEmpty()) {
            return false;
        }
        player.getInventory().setSelectedSlot(hotbarSlot.getAsInt());
        ctx.playerController().syncHeldItem();
        return true;
    }

    private boolean betterFood(FoodChoice candidate, FoodChoice best, boolean conserve, int missingHunger) {
        if (conserve) {
            boolean candidateFills = candidate.nutrition >= missingHunger;
            boolean bestFills = best.nutrition >= missingHunger;
            if (candidateFills != bestFills) {
                return candidateFills;
            }
            if (candidateFills && candidate.nutrition != best.nutrition) {
                return candidate.nutrition < best.nutrition;
            }
            if (!candidateFills && candidate.nutrition != best.nutrition) {
                return candidate.nutrition > best.nutrition;
            }
        } else if (candidate.nutrition != best.nutrition) {
            return candidate.nutrition > best.nutrition;
        }
        return candidate.saturation > best.saturation;
    }

    private static final class FoodChoice {
        final int slot;
        final int nutrition;
        final float saturation;

        FoodChoice(int slot, FoodProperties food) {
            this.slot = slot;
            this.nutrition = food.nutrition();
            this.saturation = food.saturation();
        }
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        return attemptToPutOnHotbarAndGetSlot(inMainInvy, disallowedHotbar).isPresent();
    }

    public OptionalInt attemptToPutOnHotbarAndGetSlot(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (destination.isPresent()) {
            if (!requestSwapWithHotBar(inMainInvy, destination.getAsInt())) {
                return OptionalInt.empty();
            }
        }
        return destination;
    }

    public OptionalInt getTempHotbarSlot(Predicate<Integer> disallowedHotbar) {
        // we're using 0 and 8 for pickaxe and throwaway
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < 8; i++) {
            if (ctx.player().getInventory().getNonEquipmentItems().get(i).isEmpty() && !disallowedHotbar.test(i)) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            for (int i = 1; i < 8; i++) {
                if (!disallowedHotbar.test(i)) {
                    candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(candidates.get(new Random().nextInt(candidates.size())));
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar) {
        lastTickRequestedMove = new int[]{inInventory, inHotbar};
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            logDebug("Inventory move requested but delaying " + ticksSinceLastInventoryMove + " " + Baritone.settings().ticksBetweenInventoryMoves.value);
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            logDebug("Inventory move requested but delaying until stationary");
            return false;
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, inInventory < 9 ? inInventory + 36 : inInventory, inHotbar, ClickType.SWAP, ctx.player());
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;
        return true;
    }

    private boolean requestThrowFromInventory(int inventorySlot, boolean entireStack) {
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            return false;
        }
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, inventorySlot < 9 ? inventorySlot + 36 : inventorySlot, entireStack ? 1 : 0, ClickType.THROW, ctx.player());
        ticksSinceLastInventoryMove = 0;
        return true;
    }

    private boolean dropTrashItems() {
        if (!Baritone.settings().dropTrashItems.value) {
            return false;
        }
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 9; i < invy.size(); i++) {
            if (tryDropTrashItem(invy, i)) {
                return true;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (tryDropTrashItem(invy, i)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryDropTrashItem(NonNullList<ItemStack> invy, int slot) {
        ItemStack stack = invy.get(slot);
        if (stack.isEmpty() || !Baritone.settings().trashItems.value.contains(stack.getItem())) {
            return false;
        }
        return requestThrowFromInventory(slot, true);
    }

    private boolean dropExcessAcceptableThrowawayItems() {
        if (!Baritone.settings().dropExcessAcceptableThrowawayItems.value) {
            return false;
        }
        int max = Math.max(0, Baritone.settings().maxAcceptableThrowawayItems.value);
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int total = 0;
        for (ItemStack stack : invy) {
            if (Baritone.settings().acceptableThrowawayItems.value.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        int surplus = total - max;
        if (surplus <= 0) {
            return false;
        }
        for (int i = 9; i < invy.size(); i++) {
            if (tryDropThrowawaySurplus(invy, i, surplus)) {
                return true;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (tryDropThrowawaySurplus(invy, i, surplus)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryDropThrowawaySurplus(NonNullList<ItemStack> invy, int slot, int surplus) {
        ItemStack stack = invy.get(slot);
        if (stack.isEmpty() || !Baritone.settings().acceptableThrowawayItems.value.contains(stack.getItem())) {
            return false;
        }
        return requestThrowFromInventory(slot, surplus >= stack.getCount());
    }

    private int firstValidThrowaway() { // TODO offhand idk
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < invy.size(); i++) {
            if (Baritone.settings().acceptableThrowawayItems.value.contains(invy.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private int bestToolAgainst(Block against) {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int bestInd = -1;
        double bestSpeed = -1;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (Baritone.settings().itemSaver.value && (stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                continue;
            }
            if (stack.getItem().components().has(net.minecraft.core.component.DataComponents.TOOL)) {
                double speed = ToolSet.calculateSpeedVsBlock(stack, against.defaultBlockState()); // takes into account enchants
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestInd = i;
                }
            }
        }
        return bestInd;
    }

    public boolean hasGenericThrowaway() {
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (throwaway(false, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        BlockState maybe = baritone.getBuilderProcess().placeAt(x, y, z, baritone.bsi.get0(x, y, z));
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && maybe.equals(((BlockItem) stack.getItem()).getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z), Direction.UP, ctx.playerFeet(), false)) {}))))) {
            return true; // gotem
        }
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock().equals(maybe.getBlock()))) {
            return true;
        }
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (throwaway(select, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired) {
        return throwaway(select, desired, Baritone.settings().allowInventory.value);
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired, boolean allowInventory) {
        LocalPlayer p = ctx.player();
        NonNullList<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.get(i);
            // this usage of settings() is okay because it's only called once during pathing
            // (while creating the CalculationContext at the very beginning)
            // and then it's called during execution
            // since this function is never called during cost calculation, we don't need to migrate
            // acceptableThrowawayItems to the CalculationContext
            if (desired.test(item)) {
                if (select) {
                    p.getInventory().setSelectedSlot(i);
                }
                return true;
            }
        }
        if (desired.test(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND))) {
            // main hand takes precedence over off hand
            // that means that if we have block A selected in main hand and block B in off hand, right clicking places block B
            // we've already checked above ^ and the main hand can't possible have an acceptablethrowawayitem
            // so we need to select in the main hand something that doesn't right click
            // so not a shovel, not a hoe, not a block, etc
            for (int i = 0; i < 9; i++) {
                ItemStack item = inv.get(i);
                if (item.isEmpty() || item.getItem().components().has(net.minecraft.core.component.DataComponents.TOOL)) {
                    if (select) {
                        p.getInventory().setSelectedSlot(i);
                    }
                    return true;
                }
            }
        }

        if (allowInventory) {
            for (int i = 9; i < 36; i++) {
                if (desired.test(inv.get(i))) {
                    if (select) {
                        requestSwapWithHotBar(i, 7);
                        p.getInventory().setSelectedSlot(7);
                    }
                    return true;
                }
            }
        }

        return false;
    }
}

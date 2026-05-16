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

import baritone.utils.ToolSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ToolRequirementHelper {

    private ToolRequirementHelper() {
    }

    public static ToolReport describe(LocalPlayer player, BlockState state) {
        boolean requiresCorrectTool = state.requiresCorrectToolForDrops();
        ItemStack required = minimumRequiredTool(state);
        ItemStack current = bestCurrentTool(player, state);
        boolean blocked = requiresCorrectTool && !hasMatchingTool(player, state);
        String requiredTool = !required.isEmpty() ? toolName(required) : requiresCorrectTool ? "special" : "hand";
        return new ToolReport(toolName(current), requiredTool, blocked);
    }

    public static ItemStack minimumRequiredTool(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return ItemStack.EMPTY;
        }
        for (ItemStack tool : BlockDropHelper.sampleTools()) {
            if (!tool.isEmpty() && tool.isCorrectToolForDrops(state)) {
                return tool.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack bestCurrentTool(LocalPlayer player, BlockState state) {
        ItemStack best = ItemStack.EMPTY;
        double bestSpeed = ToolSet.calculateSpeedVsBlock(best, state);
        for (ItemStack stack : inventoryStacks(player)) {
            if (stack.isEmpty()) {
                continue;
            }
            double speed = ToolSet.calculateSpeedVsBlock(stack, state);
            if (speed > bestSpeed) {
                best = stack;
                bestSpeed = speed;
            }
        }
        return best.copy();
    }

    public static boolean hasMatchingTool(LocalPlayer player, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (ItemStack stack : inventoryStacks(player)) {
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasMatchingTool(Map<Item, Integer> inventory, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (Map.Entry<Item, Integer> entry : inventory.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                ItemStack stack = new ItemStack(entry.getKey());
                if (stack.isCorrectToolForDrops(state)) {
                    return true;
                }
            }
        }
        for (ItemStack tool : BlockDropHelper.sampleTools()) {
            if (!tool.isEmpty()
                    && inventory.getOrDefault(tool.getItem(), 0) > 0
                    && tool.isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    private static List<ItemStack> inventoryStacks(LocalPlayer player) {
        List<ItemStack> stacks = new ArrayList<>();
        NonNullList<ItemStack> items = player.getInventory().items;
        stacks.addAll(items);
        stacks.addAll(player.getInventory().offhand);
        return stacks;
    }

    public static String toolName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "hand";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? stack.getDisplayName().getString() : id.toString();
    }

    public static final class ToolReport {
        public final String currentTool;
        public final String requiredTool;
        public final boolean blocked;

        public ToolReport(String currentTool, String requiredTool, boolean blocked) {
            this.currentTool = currentTool;
            this.requiredTool = requiredTool;
            this.blocked = blocked;
        }
    }
}

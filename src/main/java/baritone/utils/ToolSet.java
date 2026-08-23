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

package baritone.utils;

import baritone.Baritone;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class ToolSet {

    private final Map<Block, Double> breakStrengthCache;
    private final Function<Block, Double> backendCalculation;
    private final LocalPlayer player;

    public ToolSet(LocalPlayer player) {
        breakStrengthCache = new HashMap<>();
        this.player = player;

        if (Baritone.settings().considerPotionEffects.value) {
            double amplifier = potionAmplifier();
            Function<Double, Double> amplify = x -> amplifier * x;
            backendCalculation = amplify.compose(this::getBestDestructionTime);
        } else {
            backendCalculation = this::getBestDestructionTime;
        }
    }

    public double getStrVsBlock(BlockState state) {
        return breakStrengthCache.computeIfAbsent(state.getBlock(), backendCalculation);
    }

    private int getMaterialCost(ItemStack itemStack) {
        Tool tool = itemStack.get(DataComponents.TOOL);
        return tool == null ? -1 : materialLevel(tool);
    }

    private static int materialLevel(Tool tool) {
        float speed = tool.defaultMiningSpeed();
        if (speed >= 12.0F) return 0; // gold
        if (speed >= 9.0F) return 4;  // netherite
        if (speed >= 8.0F) return 3;  // diamond
        if (speed >= 6.0F) return 2;  // iron
        if (speed >= 4.0F) return 1;  // stone
        return 0;                     // wood
    }

    public boolean hasSilkTouch(ItemStack stack) {
        return EnchantmentHelper.getItemEnchantmentLevel(player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), stack) > 0;
    }

    public int getBestSlot(Block block, boolean preferSilkTouch) {
        return getBestSlot(block, preferSilkTouch, false);
    }

    public int getBestSlot(Block block, boolean preferSilkTouch, boolean pathingCalculation) {
        if (!Baritone.settings().autoTool.value && pathingCalculation) {
            return player.getInventory().selected;
        }

        int best = 0;
        double highestSpeed = Double.NEGATIVE_INFINITY;
        int lowestCost = Integer.MIN_VALUE;
        boolean bestSilkTouch = false;
        BlockState blockState = block.defaultBlockState();

        for (int i = 0; i < 9; i++) {
            ItemStack itemStack = player.getInventory().getItem(i);
            if (!Baritone.settings().useSwordToMine.value && itemStack.getItem() instanceof SwordItem) {
                continue;
            }

            if (Baritone.settings().itemSaver.value
                    && (itemStack.getDamageValue() + Baritone.settings().itemSaverThreshold.value) >= itemStack.getMaxDamage()
                    && itemStack.getMaxDamage() > 1) {
                continue;
            }

            double speed = calculateSpeedVsBlock(itemStack, blockState);
            boolean silkTouch = hasSilkTouch(itemStack);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                best = i;
                lowestCost = getMaterialCost(itemStack);
                bestSilkTouch = silkTouch;
            } else if (speed == highestSpeed) {
                int cost = getMaterialCost(itemStack);
                if ((cost < lowestCost && (silkTouch || !bestSilkTouch))
                        || (preferSilkTouch && !bestSilkTouch && silkTouch)) {
                    highestSpeed = speed;
                    best = i;
                    lowestCost = cost;
                    bestSilkTouch = silkTouch;
                }
            }
        }
        return best;
    }

    private double getBestDestructionTime(Block block) {
        ItemStack stack = player.getInventory().getItem(getBestSlot(block, false, true));
        return calculateSpeedVsBlock(stack, block.defaultBlockState()) * avoidanceMultiplier(block);
    }

    private double avoidanceMultiplier(Block block) {
        return Baritone.settings().blocksToAvoidBreaking.value.contains(block)
                ? Baritone.settings().avoidBreakingMultiplier.value
                : 1;
    }

    public static double calculateSpeedVsBlock(ItemStack item, BlockState state) {
        float hardness;
        try {
            hardness = state.getDestroySpeed(null, null);
        } catch (NullPointerException npe) {
            return -1;
        }
        if (hardness < 0) {
            return -1;
        }

        float speed = item.getDestroySpeed(state);
        if (speed > 1) {
            int effLevel = EnchantmentHelper.getItemEnchantmentLevel(Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), item);
            if (effLevel > 0 && !item.isEmpty()) {
                speed += effLevel * effLevel + 1;
            }
        }

        speed /= hardness;
        if (!state.requiresCorrectToolForDrops() || (!item.isEmpty() && item.isCorrectToolForDrops(state))) {
            return speed / 30;
        }
        return speed / 100;
    }

    private double potionAmplifier() {
        double speed = 1;
        if (player.hasEffect(MobEffects.DIG_SPEED)) {
            speed *= 1 + (player.getEffect(MobEffects.DIG_SPEED).getAmplifier() + 1) * 0.2;
        }
        if (player.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            switch (player.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) {
                case 0:
                    speed *= 0.3;
                    break;
                case 1:
                    speed *= 0.09;
                    break;
                case 2:
                    speed *= 0.0027;
                    break;
                default:
                    speed *= 0.00081;
                    break;
            }
        }
        return speed;
    }
}

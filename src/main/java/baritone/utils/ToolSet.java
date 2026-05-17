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
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class ToolSet {

    private final Map<Block, Double> breakStrengthCache;
    private final Function<Block, Double> backendCalculation;
    private final LocalPlayer player;

    private static final List<TagKey<Item>> materialTagsPriorityList = List.of(
            ItemTags.WOODEN_TOOL_MATERIALS,
            ItemTags.STONE_TOOL_MATERIALS,
            ItemTags.IRON_TOOL_MATERIALS,
            ItemTags.GOLD_TOOL_MATERIALS,
            ItemTags.DIAMOND_TOOL_MATERIALS,
            ItemTags.NETHERITE_TOOL_MATERIALS
    );

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
        for (int i = 0; i < materialTagsPriorityList.size(); i++) {
            TagKey<Item> tag = materialTagsPriorityList.get(i);
            if (itemStack.is(tag)) {
                return i;
            }
        }
        return -1;
    }

    public boolean hasSilkTouch(ItemStack stack) {
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            if (enchantment.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchantment) > 0) {
                return true;
            }
        }
        return false;
    }

    public int getBestSlot(Block block, boolean preferSilkTouch) {
        return getBestSlot(block, preferSilkTouch, false);
    }

    public int getBestSlot(Block block, boolean preferSilkTouch, boolean pathingCalculation) {
        if (!Baritone.settings().autoTool.value && pathingCalculation) {
            return player.getInventory().getSelectedSlot();
        }

        int best = 0;
        double highestSpeed = Double.NEGATIVE_INFINITY;
        int lowestCost = Integer.MIN_VALUE;
        boolean bestSilkTouch = false;
        BlockState blockState = block.defaultBlockState();

        for (int i = 0; i < 9; i++) {
            ItemStack itemStack = player.getInventory().getItem(i);
            if (!Baritone.settings().useSwordToMine.value && itemStack.getItem().components().has(DataComponents.WEAPON)) {
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
            ItemEnchantments itemEnchantments = item.getEnchantments();
            OUTER: for (Holder<Enchantment> enchantment : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchantment.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.MINING_EFFICIENCY.unwrapKey().get())) {
                        speed += effect.amount().calculate(itemEnchantments.getLevel(enchantment));
                        break OUTER;
                    }
                }
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
        if (player.hasEffect(MobEffects.HASTE)) {
            speed *= 1 + (player.getEffect(MobEffects.HASTE).getAmplifier() + 1) * 0.2;
        }
        if (player.hasEffect(MobEffects.MINING_FATIGUE)) {
            switch (player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) {
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

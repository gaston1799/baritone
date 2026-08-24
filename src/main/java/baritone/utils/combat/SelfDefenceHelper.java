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

package baritone.utils.combat;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import com.google.common.collect.Multimap;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class SelfDefenceHelper {

    public static final double MELEE_REACH = 3.0D;
    public static final int RECENT_THREAT_TICKS = 60;
    public static final int IN_PLACE_DETECTION_DISTANCE = 4;
    private SelfDefenceHelper() {
    }

    public static Optional<WeaponChoice> chooseWeapon(NonNullList<ItemStack> inventory, Settings.AttackType requestedType) {
        WeaponFamily preferredFamily = preferredFamily(requestedType);
        List<WeaponChoice> candidates = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.get(slot);
            WeaponFamily family = classifyWeapon(stack);
            if (family == null) {
                continue;
            }
            candidates.add(new WeaponChoice(
                    slot,
                    family,
                    slot < 9,
                    attackDamage(stack),
                    attackSpeed(stack),
                    tierLevel(stack),
                    stack
            ));
        }
        if (requestedType == Settings.AttackType.SWORD_SWEEP) {
            return strongest(candidates, WeaponFamily.SWORD);
        }
        Optional<WeaponChoice> preferred = strongest(candidates, preferredFamily);
        if (preferred.isPresent()) {
            return preferred;
        }
        return strongest(candidates, preferredFamily == WeaponFamily.AXE ? WeaponFamily.SWORD : WeaponFamily.AXE);
    }

    private static Optional<WeaponChoice> strongest(List<WeaponChoice> candidates, WeaponFamily family) {
        return candidates.stream()
                .filter(choice -> choice.family == family)
                .min(Comparator
                        .comparingDouble((WeaponChoice choice) -> -choice.attackDamage)
                        .thenComparingDouble(choice -> -choice.attackSpeed)
                        .thenComparingInt(choice -> -choice.tierLevel)
                        .thenComparing(choice -> !choice.hotbar)
                        .thenComparingInt(choice -> choice.slot));
    }

    public static Rotation rotationToTarget(Vec3 from, Rotation currentRotation, Entity target) {
        return RotationUtils.calcRotationFromVec3d(from, aimPoint(target, from), currentRotation);
    }

    public static Vec3 aimPoint(Entity target, Vec3 from) {
        AABB box = target.getBoundingBox();
        return new Vec3(
                Mth.clamp(from.x, box.minX, box.maxX),
                Mth.clamp(from.y, box.minY + (target.getBbHeight() * 0.5D), box.maxY),
                Mth.clamp(from.z, box.minZ, box.maxZ)
        );
    }

    public static boolean withinMeleeReach(Vec3 from, Entity target) {
        return from.distanceToSqr(aimPoint(target, from)) <= MELEE_REACH * MELEE_REACH;
    }

    public static boolean useJumpCrit(Settings.AttackType attackType) {
        return attackType != Settings.AttackType.SWORD_SWEEP;
    }

    public static Settings.AttackType effectiveAttackType(Settings.AttackType requestedType, WeaponChoice choice) {
        if (choice == null) {
            return requestedType;
        }
        if (requestedType == Settings.AttackType.SWORD_SWEEP && choice.family != WeaponFamily.SWORD) {
            return Settings.AttackType.SWORD_SWEEP;
        }
        if (requestedType == Settings.AttackType.SWORD_JUMP && choice.family == WeaponFamily.AXE) {
            return Settings.AttackType.AXE_JUMP;
        }
        if (requestedType == Settings.AttackType.AXE_JUMP && choice.family == WeaponFamily.SWORD) {
            return Settings.AttackType.SWORD_JUMP;
        }
        return requestedType;
    }

    public static int leashDistance(Settings.SelfDefenceMode mode) {
        switch (mode) {
            case SHORT_CHASE:
                return Math.max(0, Baritone.settings().selfDefenceShortChaseDistance.value);
            case FULL_CHASE:
                return Math.max(0, Baritone.settings().selfDefenceFullChaseDistance.value);
            case IN_PLACE:
            default:
                return 0;
        }
    }

    public static int detectionDistance(Settings.SelfDefenceMode mode) {
        switch (mode) {
            case SHORT_CHASE:
                return Math.max(0, Baritone.settings().selfDefenceShortChaseDistance.value);
            case FULL_CHASE:
                return Math.max(0, Baritone.settings().selfDefenceFullChaseDistance.value);
            case IN_PLACE:
            default:
                return IN_PLACE_DETECTION_DISTANCE;
        }
    }

    static WeaponFamily preferredFamily(Settings.AttackType requestedType) {
        return requestedType == Settings.AttackType.AXE_JUMP ? WeaponFamily.AXE : WeaponFamily.SWORD;
    }

    public static WeaponFamily classifyWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item.builtInRegistryHolder().is(net.minecraft.tags.ItemTags.SWORDS)) {
            return WeaponFamily.SWORD;
        }
        if (item instanceof AxeItem) {
            return WeaponFamily.AXE;
        }
        String path = BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase(Locale.US);
        double attackDamage = attackDamage(stack);
        if (attackDamage <= 0.0D) {
            return null;
        }
        if (path.contains("sword")) {
            return WeaponFamily.SWORD;
        }
        if (!path.contains("pickaxe") && path.contains("axe")) {
            return WeaponFamily.AXE;
        }
        return null;
    }

    public static double attackDamage(ItemStack stack) {
        return attributeValue(stack, Attributes.ATTACK_DAMAGE);
    }

    public static double attackSpeed(ItemStack stack) {
        return attributeValue(stack, Attributes.ATTACK_SPEED);
    }

    public static int tierLevel(ItemStack stack) {
        Tool tool = stack.get(DataComponents.TOOL);
        if (tool == null) {
            return -1;
        }
        float speed = tool.defaultMiningSpeed();
        if (speed >= 12.0F) return 0; // gold
        if (speed >= 9.0F) return 4;  // netherite
        if (speed >= 8.0F) return 3;  // diamond
        if (speed >= 6.0F) return 2;  // iron
        if (speed >= 4.0F) return 1;  // stone
        return 0;                     // wood
    }

    private static double attributeValue(ItemStack stack, Holder<Attribute> attribute) {
        double[] total = {0.0D};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (holder, modifier) -> {
            if (holder.equals(attribute)) {
                total[0] += modifier.amount();
            }
        });
        return total[0];
    }

    public enum WeaponFamily {
        SWORD,
        AXE
    }

    public static final class WeaponChoice {
        public final int slot;
        public final WeaponFamily family;
        public final boolean hotbar;
        public final double attackDamage;
        public final double attackSpeed;
        public final int tierLevel;
        public final ItemStack stack;

        public WeaponChoice(int slot, WeaponFamily family, boolean hotbar, double attackDamage, double attackSpeed, int tierLevel, ItemStack stack) {
            this.slot = slot;
            this.family = family;
            this.hotbar = hotbar;
            this.attackDamage = attackDamage;
            this.attackSpeed = attackSpeed;
            this.tierLevel = tierLevel;
            this.stack = stack;
        }
    }
}

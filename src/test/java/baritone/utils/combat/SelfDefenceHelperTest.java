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

import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import baritone.utils.craft.MinecraftTestBootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelfDefenceHelperTest {

    @BeforeClass
    public static void bootstrapMinecraft() {
        MinecraftTestBootstrap.ensureInitialized();
    }

    @Test
    public void parsesAndSerializesSelfDefenceEnumsCaseInsensitively() {
        Settings settings = newSettings();
        boolean oldSelfDefence = settings.selfDefence.value;
        Settings.AttackType oldAttackType = settings.attackType.value;
        Settings.SelfDefenceMode oldMode = settings.selfDefenceMode.value;
        try {
            SettingsUtil.parseAndApply(settings, "selfdefence", "true");
            SettingsUtil.parseAndApply(settings, "attacktype", "SwordJump");
            SettingsUtil.parseAndApply(settings, "selfdefencemode", "fullchase");

            assertTrue(settings.selfDefence.value);
            assertEquals(Settings.AttackType.SWORD_JUMP, settings.attackType.value);
            assertEquals(Settings.SelfDefenceMode.FULL_CHASE, settings.selfDefenceMode.value);
            assertEquals("swordJump", SettingsUtil.settingValueToString(settings.attackType));
            assertEquals("fullChase", SettingsUtil.settingValueToString(settings.selfDefenceMode));
        } finally {
            settings.selfDefence.value = oldSelfDefence;
            settings.attackType.value = oldAttackType;
            settings.selfDefenceMode.value = oldMode;
        }
    }

    @Test
    public void choosesHighestDamageSwordForSweep() {
        Optional<SelfDefenceHelper.WeaponChoice> choice = SelfDefenceHelper.chooseWeapon(
                inventory(
                        new ItemStack(Items.STONE_SWORD),
                        ItemStack.EMPTY,
                        ItemStack.EMPTY,
                        ItemStack.EMPTY,
                        ItemStack.EMPTY,
                        ItemStack.EMPTY,
                        ItemStack.EMPTY,
                        ItemStack.EMPTY,
                        ItemStack.EMPTY,
                        new ItemStack(Items.DIAMOND_SWORD)
                ),
                Settings.AttackType.SWORD_SWEEP
        );

        assertTrue(choice.isPresent());
        assertEquals(9, choice.get().slot);
        assertEquals(SelfDefenceHelper.WeaponFamily.SWORD, choice.get().family);
    }

    @Test
    public void sweepDoesNotUseAxeWhenSwordMissing() {
        Optional<SelfDefenceHelper.WeaponChoice> choice = SelfDefenceHelper.chooseWeapon(
                inventory(new ItemStack(Items.DIAMOND_AXE)),
                Settings.AttackType.SWORD_SWEEP
        );

        assertFalse(choice.isPresent());
    }

    @Test
    public void jumpPrefersBestAxeAndFallsBackToBestSword() {
        Optional<SelfDefenceHelper.WeaponChoice> axeChoice = SelfDefenceHelper.chooseWeapon(
                inventory(new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.DIAMOND_AXE)),
                Settings.AttackType.AXE_JUMP
        );

        assertTrue(axeChoice.isPresent());
        assertEquals(2, axeChoice.get().slot);
        assertEquals(SelfDefenceHelper.WeaponFamily.AXE, axeChoice.get().family);
        assertEquals(Settings.AttackType.AXE_JUMP, SelfDefenceHelper.effectiveAttackType(Settings.AttackType.AXE_JUMP, axeChoice.get()));

        Optional<SelfDefenceHelper.WeaponChoice> swordChoice = SelfDefenceHelper.chooseWeapon(
                inventory(new ItemStack(Items.STONE_SWORD), new ItemStack(Items.DIAMOND_SWORD)),
                Settings.AttackType.AXE_JUMP
        );

        assertTrue(swordChoice.isPresent());
        assertEquals(1, swordChoice.get().slot);
        assertEquals(SelfDefenceHelper.WeaponFamily.SWORD, swordChoice.get().family);
        assertEquals(Settings.AttackType.SWORD_JUMP, SelfDefenceHelper.effectiveAttackType(Settings.AttackType.AXE_JUMP, swordChoice.get()));
    }

    @Test
    public void swordJumpFallsBackToAxeWhenSwordMissing() {
        Optional<SelfDefenceHelper.WeaponChoice> choice = SelfDefenceHelper.chooseWeapon(
                inventory(new ItemStack(Items.DIAMOND_AXE)),
                Settings.AttackType.SWORD_JUMP
        );

        assertTrue(choice.isPresent());
        assertEquals(SelfDefenceHelper.WeaponFamily.AXE, choice.get().family);
        assertEquals(Settings.AttackType.AXE_JUMP, SelfDefenceHelper.effectiveAttackType(Settings.AttackType.SWORD_JUMP, choice.get()));
    }

    @Test
    public void doesNotMisclassifyPickaxeAsAxe() {
        assertEquals(SelfDefenceHelper.WeaponFamily.SWORD, SelfDefenceHelper.classifyWeapon(new ItemStack(Items.IRON_SWORD)));
        assertEquals(SelfDefenceHelper.WeaponFamily.AXE, SelfDefenceHelper.classifyWeapon(new ItemStack(Items.IRON_AXE)));
        assertEquals(null, SelfDefenceHelper.classifyWeapon(new ItemStack(Items.DIAMOND_PICKAXE)));
    }

    private static net.minecraft.core.NonNullList<ItemStack> inventory(ItemStack... stacks) {
        net.minecraft.core.NonNullList<ItemStack> inventory = net.minecraft.core.NonNullList.withSize(36, ItemStack.EMPTY);
        for (int i = 0; i < stacks.length; i++) {
            inventory.set(i, stacks[i]);
        }
        return inventory;
    }

    private static Settings newSettings() {
        try {
            Constructor<Settings> constructor = Settings.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}

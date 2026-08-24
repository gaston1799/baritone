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
import baritone.api.Settings;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.utils.combat.SelfDefenceHelper;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public final class SelfDefenceBehavior extends Behavior {

    private final Map<UUID, Integer> recentThreatExpiry = new HashMap<>();

    private int tickCounter;
    private Mob currentTarget;
    private BetterBlockPos combatAnchor;
    private boolean jumpAttackQueued;
    private boolean jumpInputOwned;
    private int jumpQueuedAtTick;
    private float lastKnownHealth = Float.NaN;
    private int currentTargetLastSeenTick;
    private Settings.AttackType currentAttackType;
    private PendingAttack pendingAttack;
    private int strafeDirection = 1;
    private int strafeDirSwitchTick;
    private boolean shieldOwned;
    private boolean strafeOwned;
    private int lastTotemSwapTick = -100;
    private int lastShieldSwapTick = -100;
    private int maceDivePhase; // 0 idle, 1 launch (jump), 2 glide/swoop, 3 dive (attack window), 4 rocket escape
    private int maceDiveTick;

    public SelfDefenceBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            clearCombatState();
            return;
        }
        tickCounter++;
        checkPendingAttackResult();
        if (ctx.player() == null || ctx.world() == null || !Baritone.settings().selfDefence.value) {
            clearCombatState();
            return;
        }
        if (Float.isNaN(lastKnownHealth)) {
            lastKnownHealth = ctx.player().getHealth();
        }
        updateThreatMemory();
        if (Baritone.settings().selfDefenceAvoidCreepers.value && avoidNearbyCreepers()) {
            return;
        }
        Mob nextTarget = selectTarget();
        if (nextTarget == null) {
            clearCombatState();
            return;
        }
        if (currentTarget == null || currentTarget != nextTarget) {
            combatAnchor = ctx.playerFeet();
        }
        currentTarget = nextTarget;
        if (ctx.player().hasLineOfSight(currentTarget)) {
            currentTargetLastSeenTick = tickCounter;
        }
        if (combatAnchor == null) {
            combatAnchor = ctx.playerFeet();
        }
        if (shouldDisengageForLeash(currentTarget)) {
            clearCombatState();
            return;
        }

        if (!ctx.player().hasLineOfSight(currentTarget) || !SelfDefenceHelper.withinMeleeReach(ctx.playerHead(), currentTarget)) {
            if (maceDivePhase == 4) {
                handleMaceSmash(); // keep the rocket escape going even if the target left reach
            } else {
                resetJumpState();
                releaseCombatInputs();
            }
            return;
        }
        Rotation look = SelfDefenceHelper.rotationToTarget(ctx.playerHead(), ctx.playerRotations(), currentTarget);
        baritone.getLookBehavior().updateTarget(look, false);

        // A golden apple being eaten is critical survival: finish the eat
        // before engaging (attacking would cancel it).
        if (baritone.getInventoryBehavior().isEatingGoldenApple()) {
            releaseCombatInputs();
            return;
        }

        SelfDefenceHelper.WeaponChoice weapon = equipWeapon();
        if (weapon == null) {
            resetJumpState();
            releaseCombatInputs();
            return;
        }
        Settings.AttackType attackType = SelfDefenceHelper.effectiveAttackType(Baritone.settings().attackType.value, weapon);
        currentAttackType = attackType;
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        ctx.player().setSprinting(false);
        handleTotemHotswap();
        if (SelfDefenceHelper.withinMeleeReach(ctx.playerHead(), currentTarget)) {
            handleShield();
            if (attackType != Settings.AttackType.MACE_SMASH) {
                handleStrafe();
            } else {
                releaseStrafe(); // sideways drift would wreck the dive line
            }
        } else {
            releaseCombatInputs();
        }
        if (SelfDefenceHelper.useJumpCrit(attackType)) {
            releaseCombatInputs();
            handleJumpCrit();
            return;
        }
        resetJumpState();
        if (!weaponReady()) {
            kiteIfNeeded();
            return;
        }
        if (!ctx.player().onGround()) {
            return;
        }
        attemptAttack(currentTarget, false, attackType, true);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        clearCombatState();
    }

    @Override
    public void onPlayerDeath() {
        clearCombatState();
    }

    public boolean isDefending() {
        return currentTarget != null && currentTarget.isAlive() && Baritone.settings().selfDefence.value;
    }

    public boolean shouldPauseForCombat() {
        if (!isDefending()) {
            return false;
        }
        if (Baritone.settings().selfDefenceMode.value == Settings.SelfDefenceMode.IN_PLACE) {
            return true;
        }
        return !needsChase();
    }

    public Goal chaseGoal() {
        if (!needsChase()) {
            return null;
        }
        return new GoalNear(currentTarget.blockPosition(), 1);
    }

    public Mob getCurrentTarget() {
        return currentTarget;
    }

    /**
     * Back away from any creeper inside selfDefenceCreeperSafeDistance, even if it
     * isn't the current target. Faces away from the creeper (looking at it would
     * ignite it) and walks away; no attacks happen while one is that close.
     */
    private boolean avoidNearbyCreepers() {
        if (canCreeperDive()) {
            // we have the smash + rocket-escape kit: let combat proceed and
            // dive-bomb the creeper instead of walking away
            return false;
        }
        double safeDist = Baritone.settings().selfDefenceCreeperSafeDistance.value;
        double safeDistSq = safeDist * safeDist;
        Creeper nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        Vec3 head = ctx.playerHead();
        for (Entity entity : ctx.entitiesStream().toList()) {
            if (entity instanceof Creeper creeper && creeper.isAlive()) {
                double distSq = head.distanceToSqr(creeper.getEyePosition());
                if (distSq < safeDistSq && distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearest = creeper;
                }
            }
        }
        if (nearest == null) {
            return false;
        }
        Vec3 away = head.subtract(nearest.getEyePosition()).normalize();
        float awayYaw = (float) Math.toDegrees(Math.atan2(-away.x, -away.z));
        baritone.getLookBehavior().updateTarget(new Rotation(awayYaw, ctx.playerRotations().getPitch()), false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        resetJumpState();
        releaseCombatInputs();
        return true;
    }

    private boolean needsChase() {
        return isDefending()
                && Baritone.settings().selfDefenceMode.value != Settings.SelfDefenceMode.IN_PLACE
                && !SelfDefenceHelper.withinMeleeReach(ctx.playerHead(), currentTarget);
    }

    private void kiteIfNeeded() {
        if (!Baritone.settings().selfDefenceKiteOnCooldown.value || currentTarget == null) {
            return;
        }
        double kiteDistSq = Baritone.settings().selfDefenceKiteDistance.value;
        kiteDistSq *= kiteDistSq;
        Vec3 aimPoint = SelfDefenceHelper.aimPoint(currentTarget, ctx.playerHead());
        if (ctx.playerHead().distanceToSqr(aimPoint) < kiteDistSq) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
        }
    }

    private void handleTotemHotswap() {
        double threshold = Baritone.settings().selfDefenceTotemHealth.value;
        if (threshold <= 0.0D || ctx.player().getHealth() > (float) threshold) {
            return;
        }
        if (ctx.player().getItemBySlot(EquipmentSlot.OFFHAND).getItem() == Items.TOTEM_OF_UNDYING) {
            return;
        }
        if (tickCounter - lastTotemSwapTick < 20) {
            return; // don't spam container clicks every tick
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
            return;
        }
        lastTotemSwapTick = tickCounter;
        int sourceSlot = totemSlot < 9 ? totemSlot + 36 : totemSlot;
        int containerId = ctx.player().inventoryMenu.containerId;
        ctx.playerController().windowClick(containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, 45, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        Helper.HELPER.logDirect("[SelfDefence] Totem moved to offhand (hp " + ctx.player().getHealth() + ")");
    }

    /**
     * Raise the shield during weapon cooldown so melee hits are blocked. The
     * attack itself is issued via the player controller, so holding USE only
     * blocks and never interrupts the swing.
     */
    private void handleShield() {
        equipShieldToOffhand();
        boolean wantShield = Baritone.settings().selfDefenceUseShield.value
                && ctx.player().getItemBySlot(EquipmentSlot.OFFHAND).getItem() == Items.SHIELD
                && !weaponReady()
                && !jumpAttackQueued;
        if (wantShield) {
            shieldOwned = true;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        } else {
            releaseShield();
        }
    }

    /**
     * Move a shield from the inventory to the offhand so blocking actually works.
     * Never overwrites a totem (totem has offhand priority).
     */
    private void equipShieldToOffhand() {
        if (!Baritone.settings().selfDefenceUseShield.value) {
            return;
        }
        ItemStack offhand = ctx.player().getItemBySlot(EquipmentSlot.OFFHAND);
        if (offhand.getItem() == Items.SHIELD || offhand.getItem() == Items.TOTEM_OF_UNDYING) {
            return;
        }
        if (tickCounter - lastShieldSwapTick < 40) {
            return;
        }
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int shieldSlot = -1;
        for (int i = 0; i < invy.size(); i++) {
            if (invy.get(i).getItem() == Items.SHIELD) {
                shieldSlot = i;
                break;
            }
        }
        if (shieldSlot == -1) {
            return;
        }
        lastShieldSwapTick = tickCounter;
        int sourceSlot = shieldSlot < 9 ? shieldSlot + 36 : shieldSlot;
        int containerId = ctx.player().inventoryMenu.containerId;
        ctx.playerController().windowClick(containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, 45, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        Helper.HELPER.logDirect("[SelfDefence] Shield moved to offhand");
    }

    private void releaseShield() {
        if (shieldOwned) {
            shieldOwned = false;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        }
    }

    /**
     * Circle the target: hold a sideways input perpendicular to the aim line,
     * switching direction every 40 ticks so the bot orbits instead of walking
     * into terrain.
     */
    private void handleStrafe() {
        if (!Baritone.settings().selfDefenceStrafe.value || !ctx.player().onGround()) {
            releaseStrafe();
            return;
        }
        if (tickCounter >= strafeDirSwitchTick) {
            strafeDirection = -strafeDirection;
            strafeDirSwitchTick = tickCounter + 40;
        }
        strafeOwned = true;
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, strafeDirection == 1);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, strafeDirection == -1);
    }

    private void releaseStrafe() {
        if (strafeOwned) {
            strafeOwned = false;
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        }
    }

    private void releaseCombatInputs() {
        releaseShield();
        releaseStrafe();
    }

    /**
     * Mace + elytra dive-bomb. The mace's smash attack needs &gt;1.5 blocks of fall
     * for bonus damage (no cap); a plain jump only gives ~1.25, so the bot uses
     * the elytra to gain altitude, then dives and swings at the bottom of the
     * fall. Without elytra it just jump-crits (base mace damage only).
     */
    /**
     * True when the bot can safely dive-bomb a creeper: mace smash requested,
     * elytra equipped and a firework rocket available for the escape boost.
     */
    private boolean canCreeperDive() {
        return Baritone.settings().selfDefenceCreeperDive.value
                && Baritone.settings().attackType.value == Settings.AttackType.MACE_SMASH
                && ctx.player() != null
                && ctx.player().getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA
                && hasFireworkRocket();
    }

    private boolean hasFireworkRocket() {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (ItemStack stack : invy) {
            if (stack.getItem() == Items.FIREWORK_ROCKET) {
                return true;
            }
        }
        return false;
    }

    /**
     * Put a firework rocket in the selected hotbar slot (hotbar first, else
     * swap one in from the inventory).
     */
    private void equipFireworkRocket() {
        for (int i = 0; i < 9; i++) {
            if (ctx.player().getInventory().getItem(i).getItem() == Items.FIREWORK_ROCKET) {
                ctx.player().getInventory().setSelectedSlot(i);
                ctx.playerController().syncHeldItem();
                return;
            }
        }
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 9; i < invy.size(); i++) {
            if (invy.get(i).getItem() == Items.FIREWORK_ROCKET) {
                OptionalInt slot = baritone.getInventoryBehavior().attemptToPutOnHotbarAndGetSlot(i, s -> s == 0);
                if (slot.isPresent()) {
                    ctx.player().getInventory().setSelectedSlot(slot.getAsInt());
                    ctx.playerController().syncHeldItem();
                }
                return;
            }
        }
    }

    private void handleMaceSmash() {
        boolean hasElytra = ctx.player().getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
        switch (maceDivePhase) {
            case 0: // idle - start the dive from the ground
                if (!ctx.player().onGround()) {
                    return;
                }
                if (!weaponReady()) {
                    kiteIfNeeded();
                    resetJumpState();
                    return;
                }
                // on the ground right next to a creeper: its fuse ignites at ~3
                // blocks and the ground-launch dive is too slow - back away first
                // to break the fuse, then dive from a safe distance
                if (currentTarget instanceof Creeper
                        && ctx.playerHead().distanceToSqr(currentTarget.getEyePosition()) < 12.25D) {
                    Vec3 away = ctx.playerHead().subtract(currentTarget.getEyePosition()).normalize();
                    float awayYaw = (float) Math.toDegrees(Math.atan2(-away.x, -away.z));
                    baritone.getLookBehavior().updateTarget(new Rotation(awayYaw, ctx.playerRotations().getPitch()), false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                    resetJumpState();
                    return;
                }
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                maceDivePhase = 1;
                maceDiveTick = tickCounter;
                jumpAttackQueued = true;
                jumpInputOwned = true;
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                return;
            case 1: // launch - leave the ground, deploy elytra
                if (ctx.player().onGround()) {
                    resetMaceDive(); // didn't get airborne (head bonk) - try again next tick
                    return;
                }
                if (hasElytra && tickCounter - maceDiveTick > 4) {
                    maceDivePhase = 2; // keep holding jump - elytra will deploy and we swoop
                    maceDiveTick = tickCounter;
                } else if (!hasElytra && tickCounter - maceDiveTick > 2) {
                    releaseJumpInput(); // no elytra: just fall, attack once past 1.5
                    maceDivePhase = 3;
                    maceDiveTick = tickCounter;
                }
                return;
            case 2: // glide - gain a bit of altitude, then cut the elytra and dive
                if (hasElytra) {
                    // keep gliding (jump held) for a short swoop, then nose down
                    if (tickCounter - maceDiveTick > 12) {
                        releaseJumpInput(); // cut elytra -> start falling
                        maceDivePhase = 3;
                        maceDiveTick = tickCounter;
                    }
                } else {
                    maceDivePhase = 3;
                }
                return;
            case 3: // dive - swing at the bottom of the fall
                if (!ctx.player().onGround()
                        && ctx.player().fallDistance > 1.5F
                        && ctx.player().getDeltaMovement().y < 0.0D
                        && !ctx.player().isInWater()
                        && !ctx.player().onClimbable()
                        && SelfDefenceHelper.withinMeleeReach(ctx.playerHead(), currentTarget)
                        && weaponReady()) {
                    attemptAttack(currentTarget, true, Settings.AttackType.MACE_SMASH, true);
                    if (hasFireworkRocket()) {
                        // smash -> rocket straight up -> re-dive. The boost also
                        // escapes a creeper's blast radius before its fuse pops.
                        maceDivePhase = 4;
                        maceDiveTick = tickCounter;
                    } else {
                        resetMaceDive();
                    }
                    return;
                }
                // crater guard: missed the swing and falling too far - glide it out
                if (hasElytra && !ctx.player().onGround() && ctx.player().fallDistance > 10.0F) {
                    if (!jumpInputOwned) {
                        jumpInputOwned = true;
                        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    }
                    maceDivePhase = 2;
                    maceDiveTick = tickCounter;
                    return;
                }
                if (ctx.player().onGround()) {
                    resetMaceDive();
                }
                return;
            case 4: // rocket escape - boost up, then swoop and dive again
                if (ctx.player().onGround()) {
                    resetMaceDive();
                    return;
                }
                if (!ctx.player().isFallFlying()) {
                    // re-deploy the elytra (we cut it for the dive)
                    if (!jumpInputOwned) {
                        jumpInputOwned = true;
                        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    }
                    return;
                }
                if (jumpInputOwned) {
                    releaseJumpInput(); // gliding - stop trying to deploy
                }
                equipFireworkRocket();
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true); // boost
                if (tickCounter - maceDiveTick > 10 || ctx.player().getDeltaMovement().y > 0.5D) {
                    // gained enough height - cut everything and re-dive
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                    maceDivePhase = 2; // swoop -> dive again
                    maceDiveTick = tickCounter;
                }
                return;
            default:
                resetMaceDive();
        }
    }

    private void resetMaceDive() {
        maceDivePhase = 0;
        jumpAttackQueued = false;
        releaseJumpInput();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
    }

    private void handleJumpCrit() {
        if (currentAttackType == Settings.AttackType.MACE_SMASH) {
            handleMaceSmash();
            return;
        }
        if (ctx.player().onGround()) {
            if (!weaponReady()) {
                kiteIfNeeded();
                resetJumpState();
                return;
            }
            jumpAttackQueued = true;
            jumpInputOwned = true;
            jumpQueuedAtTick = tickCounter;
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            return;
        }
        releaseJumpInput();
        if (!jumpAttackQueued) {
            return;
        }
        if (tickCounter - jumpQueuedAtTick > 12) {
            resetJumpState();
            return;
        }
        if (ctx.player().fallDistance > 0.0F && ctx.player().getDeltaMovement().y < 0.0D && !ctx.player().isInWater() && !ctx.player().onClimbable()) {
            if (!weaponReady()) {
                return;
            }
            attemptAttack(currentTarget, true, currentAttackType, weaponReady());
            resetJumpState();
        }
    }

    /**
     * Records the attack so a miss (no damage applied) can be detected and logged
     * over the following ticks.
     */
    private void attemptAttack(Mob target, boolean jumped, Settings.AttackType attackType, boolean weaponReadyState) {
        Settings.AttackType type = attackType != null ? attackType : Baritone.settings().attackType.value;
        Vec3 head = ctx.playerHead();
        double distance = head.distanceTo(target.getEyePosition());
        pendingAttack = new PendingAttack(
                target,
                target.getHealth(),
                jumped,
                type,
                weaponReadyState,
                distance,
                ctx.player().position(),
                tickCounter
        );
        ctx.playerController().attackEntity(ctx.player(), target);
    }

    private void checkPendingAttackResult() {
        if (pendingAttack == null) {
            return;
        }
        PendingAttack p = pendingAttack;
        if (!p.target().isAlive()) {
            pendingAttack = null; // killed (or died) - the hit landed
            return;
        }
        if (p.target().getHealth() < p.healthBefore()) {
            pendingAttack = null; // damage applied - the hit landed
            return;
        }
        if (tickCounter - p.tick() > 4) {
            pendingAttack = null;
            logMiss(p);
            if (p.attackType() == Settings.AttackType.MACE_SMASH && hasFireworkRocket() && !ctx.player().onGround()) {
                // missed smash: rocket-escape immediately (no cratering, no creeper
                // blast - the creeper is ignited and we're still falling next to it),
                // then re-dive
                jumpAttackQueued = false;
                maceDivePhase = 4;
                maceDiveTick = tickCounter;
            }
        }
    }

    private void logMiss(PendingAttack p) {
        Vec3 pos = p.playerPos();
        net.minecraft.resources.ResourceLocation targetKey = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(p.target().getType());
        String line = String.format(
                "MISS type=%s jumped=%s weaponReady=%s dist=%.2f pos=(%.1f,%.1f,%.1f) target=%s hp=%.1f->%.1f",
                p.attackType(), p.jumped(), p.weaponReady(), p.distance(),
                pos.x, pos.y, pos.z,
                targetKey, p.healthBefore(), p.target().getHealth()
        );
        Helper.HELPER.logDirect("[SelfDefence] " + line);
        try {
            java.nio.file.Path logFile = ctx.minecraft().gameDirectory.toPath()
                    .resolve("baritone").resolve("selfdefence-miss.log");
            java.nio.file.Files.createDirectories(logFile.getParent());
            java.nio.file.Files.writeString(logFile,
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Helper.HELPER.logDirect("[SelfDefence] failed to write miss log: " + e.getMessage());
        }
    }

    private record PendingAttack(
            Mob target,
            float healthBefore,
            boolean jumped,
            Settings.AttackType attackType,
            boolean weaponReady,
            double distance,
            Vec3 playerPos,
            int tick
    ) {}

    private boolean weaponReady() {
        return ctx.player().getAttackStrengthScale(0.5F) >= 0.98F;
    }

    private SelfDefenceHelper.WeaponChoice equipWeapon() {
        NonNullList<ItemStack> inventory = ctx.player().getInventory().getNonEquipmentItems();
        Optional<SelfDefenceHelper.WeaponChoice> choice = SelfDefenceHelper.chooseWeapon(inventory, Baritone.settings().attackType.value);
        if (!choice.isPresent()) {
            return null;
        }
        SelfDefenceHelper.WeaponChoice selected = choice.get();
        if (selected.slot < 9) {
            ctx.player().getInventory().setSelectedSlot(selected.slot);
            ctx.playerController().syncHeldItem();
            return selected;
        }
        OptionalInt swappedTo = baritone.getInventoryBehavior().attemptToPutOnHotbarAndGetSlot(selected.slot, slot -> slot == 0 || slot == 8);
        if (!swappedTo.isPresent()) {
            return null;
        }
        ctx.player().getInventory().setSelectedSlot(swappedTo.getAsInt());
        ctx.playerController().syncHeldItem();
        ItemStack equipped = inventory.get(swappedTo.getAsInt());
        return new SelfDefenceHelper.WeaponChoice(
                swappedTo.getAsInt(),
                selected.family,
                true,
                SelfDefenceHelper.attackDamage(equipped),
                SelfDefenceHelper.attackSpeed(equipped),
                SelfDefenceHelper.tierLevel(equipped),
                equipped
        );
    }

    private Mob selectTarget() {
        if (isCurrentTargetStillValid()) {
            return currentTarget;
        }
        return ctx.entitiesStream()
                .filter(entity -> entity instanceof Mob)
                .map(entity -> (Mob) entity)
                .filter(this::isCandidateTarget)
                .min(Comparator.comparingDouble(mob -> mob.distanceToSqr(ctx.player())))
                .orElse(null);
    }

    private boolean isCandidateTarget(Mob mob) {
        if (mob == null || !mob.isAlive() || !ctx.entitiesStream().anyMatch(mob::equals)) {
            return false;
        }
        if (!isHostile(mob)) {
            return false;
        }
        int detectionDistance = SelfDefenceHelper.detectionDistance(Baritone.settings().selfDefenceMode.value);
        return mob.distanceToSqr(ctx.player()) <= detectionDistance * detectionDistance
                && ctx.player().hasLineOfSight(mob);
    }

    private boolean isCurrentTargetStillValid() {
        if (currentTarget == null || !currentTarget.isAlive() || !ctx.entitiesStream().anyMatch(currentTarget::equals)) {
            return false;
        }
        if (!isHostile(currentTarget)) {
            return false;
        }
        int leashDistance = SelfDefenceHelper.leashDistance(Baritone.settings().selfDefenceMode.value);
        int detectionDistance = SelfDefenceHelper.detectionDistance(Baritone.settings().selfDefenceMode.value);
        int allowedDistance = leashDistance > 0 ? leashDistance : detectionDistance;
        if (allowedDistance > 0 && currentTarget.distanceToSqr(ctx.player()) > allowedDistance * allowedDistance) {
            return false;
        }
        if (ctx.player().hasLineOfSight(currentTarget)) {
            currentTargetLastSeenTick = tickCounter;
            return true;
        }
        int wallTicks = Math.max(0, Baritone.settings().selfDefenceWallTargetTicks.value);
        return tickCounter - currentTargetLastSeenTick <= wallTicks;
    }

    private boolean isHostile(Mob mob) {
        return mob instanceof Enemy || isDirectThreat(mob) || wasRecentThreat(mob);
    }

    private boolean isDirectThreat(Mob mob) {
        return mob.getTarget() == ctx.player() || ctx.player().getLastHurtByMob() == mob;
    }

    private boolean wasRecentThreat(Entity entity) {
        Integer expiry = recentThreatExpiry.get(entity.getUUID());
        return expiry != null && expiry >= tickCounter;
    }

    private void updateThreatMemory() {
        recentThreatExpiry.entrySet().removeIf(entry -> entry.getValue() < tickCounter);
        if (ctx.player().getHealth() < lastKnownHealth && ctx.player().getLastHurtByMob() != null) {
            recentThreatExpiry.put(ctx.player().getLastHurtByMob().getUUID(), tickCounter + SelfDefenceHelper.RECENT_THREAT_TICKS);
        }
        lastKnownHealth = ctx.player().getHealth();
        ctx.entitiesStream()
                .filter(entity -> entity instanceof Mob)
                .map(entity -> (Mob) entity)
                .filter(this::isDirectThreat)
                .forEach(mob -> recentThreatExpiry.put(mob.getUUID(), tickCounter + SelfDefenceHelper.RECENT_THREAT_TICKS));
    }

    private boolean shouldDisengageForLeash(Mob target) {
        int leashDistance = SelfDefenceHelper.leashDistance(Baritone.settings().selfDefenceMode.value);
        if (leashDistance <= 0 || combatAnchor == null) {
            return false;
        }
        return target.distanceToSqr(Vec3.atCenterOf(combatAnchor)) > leashDistance * leashDistance;
    }

    private void clearCombatState() {
        currentTarget = null;
        combatAnchor = null;
        currentTargetLastSeenTick = 0;
        recentThreatExpiry.clear();
        pendingAttack = null;
        lastKnownHealth = Float.NaN;
        releaseCombatInputs();
        resetJumpState();
    }

    private void resetJumpState() {
        jumpAttackQueued = false;
        maceDivePhase = 0;
        releaseJumpInput();
    }

    private void releaseJumpInput() {
        if (!jumpInputOwned) {
            return;
        }
        jumpInputOwned = false;
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
    }
}

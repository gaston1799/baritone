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
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.utils.combat.SelfDefenceHelper;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
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
        if (ctx.player() == null || ctx.world() == null || !Baritone.settings().selfDefence.value) {
            clearCombatState();
            return;
        }
        if (Float.isNaN(lastKnownHealth)) {
            lastKnownHealth = ctx.player().getHealth();
        }
        updateThreatMemory();
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
            resetJumpState();
            return;
        }
        Rotation look = SelfDefenceHelper.rotationToTarget(ctx.playerHead(), ctx.playerRotations(), currentTarget);
        baritone.getLookBehavior().updateTarget(look, false);

        SelfDefenceHelper.WeaponChoice weapon = equipWeapon();
        if (weapon == null) {
            resetJumpState();
            return;
        }
        Settings.AttackType attackType = SelfDefenceHelper.effectiveAttackType(Baritone.settings().attackType.value, weapon);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        ctx.player().setSprinting(false);
        if (SelfDefenceHelper.useJumpCrit(attackType)) {
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
        ctx.playerController().attackEntity(ctx.player(), currentTarget);
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

    private void handleJumpCrit() {
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
            ctx.playerController().attackEntity(ctx.player(), currentTarget);
            resetJumpState();
        }
    }

    private boolean weaponReady() {
        return ctx.player().getAttackStrengthScale(0.5F) >= 0.98F;
    }

    private SelfDefenceHelper.WeaponChoice equipWeapon() {
        NonNullList<ItemStack> inventory = ctx.player().getInventory().items;
        Optional<SelfDefenceHelper.WeaponChoice> choice = SelfDefenceHelper.chooseWeapon(inventory, Baritone.settings().attackType.value);
        if (!choice.isPresent()) {
            return null;
        }
        SelfDefenceHelper.WeaponChoice selected = choice.get();
        if (selected.slot < 9) {
            ctx.player().getInventory().selected = selected.slot;
            ctx.playerController().syncHeldItem();
            return selected;
        }
        OptionalInt swappedTo = baritone.getInventoryBehavior().attemptToPutOnHotbarAndGetSlot(selected.slot, slot -> slot == 0 || slot == 8);
        if (!swappedTo.isPresent()) {
            return null;
        }
        ctx.player().getInventory().selected = swappedTo.getAsInt();
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
        lastKnownHealth = Float.NaN;
        resetJumpState();
    }

    private void resetJumpState() {
        jumpAttackQueued = false;
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

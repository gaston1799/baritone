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
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.utils.IRenderer;
import baritone.utils.combat.CombatFailureLogger;
import baritone.utils.combat.CombatMovementPlanner;
import baritone.utils.combat.SelfDefenceHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public final class SelfDefenceBehavior extends Behavior {

    private static final int MACE_IDLE = 0;
    private static final int MACE_LAUNCH = 1;
    private static final int MACE_DEPLOY = 2;
    private static final int MACE_AIM_CLIMB = 3;
    private static final int MACE_POWERED_CLIMB = 4;
    private static final int MACE_STOW_ELYTRA = 5;
    private static final int MACE_DIVE = 6;
    private static final int MACE_RESTORE_ELYTRA = 7;
    private static final int MACE_ESCAPE_DEPLOY = 8;
    private static final int MACE_PLAIN_JUMP = 9;
    private static final int CHEST_CONTAINER_SLOT = 6;
    /** Walk-only planner limits: stepping up needs a jump, drops beyond this are rejected. */
    private static final double MAX_COMBAT_STEP_UP = 0.6D;
    private static final double MAX_COMBAT_STEP_DOWN = 1.25D;

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
    private int maceDivePhase;
    private int maceDiveTick;
    private double maceClimbStartY;
    private int maceLaunchRetries;
    private double macePeakFallDistance;
    private int stowedElytraSlot = -1;
    private final CombatFailureLogger combatFailureLogger = new CombatFailureLogger(80, 10);
    private CombatMovementPlanner.Move activeCombatMove = CombatMovementPlanner.Move.HOLD;
    private CombatMovementPlanner.Decision lastCombatDecision;
    private final Map<CombatMovementPlanner.Move, String> combatCandidateRejections =
            new EnumMap<>(CombatMovementPlanner.Move.class);
    private final Map<CombatMovementPlanner.Move, Integer> combatCandidateRejectionSamples =
            new EnumMap<>(CombatMovementPlanner.Move.class);
    private int lastCombatMoveBlockedTick = -1000;
    private BlockPos lastChaseGoalBlock;
    private int lastChaseGoalTick = -1000;
    private Goal lastChaseGoal;
    private boolean wasChasing;
    private String lastNarrativeDecisionKey = "";
    private int lastCombatHorizon;
    private double lastCombatAttackDistance;
    private double lastCombatCooldownDistance;
    private double combatDecisionY;
    private boolean combatMovementOwned;
    private String lastCombatAction = "idle";
    private int lastCapturedFrameTick = -1;
    private int lastFailureTick = -1000;
    private String lastFailureReason = "";
    private int renderFailureUntilTick;

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
            if (maceDivePhase != MACE_IDLE) {
                abortMaceDiveSafely("target lost during mace sequence");
                return;
            }
            if (currentTarget != null && currentTarget.isAlive()) {
                recordCombatFailure("TARGET_LOST", "target left detection, line-of-sight, or leash range");
            }
            clearCombatState();
            return;
        }
        if (currentTarget == null || currentTarget != nextTarget) {
            combatAnchor = ctx.playerFeet();
            combatFailureLogger.beginTrace();
            lastCapturedFrameTick = -1;
            lastCombatAction = "engage";
        }
        currentTarget = nextTarget;
        if (ctx.player().hasLineOfSight(currentTarget)) {
            currentTargetLastSeenTick = tickCounter;
        }
        if (combatAnchor == null) {
            combatAnchor = ctx.playerFeet();
        }
        captureCombatFrame();
        if (shouldDisengageForLeash(currentTarget)) {
            recordCombatFailure("LEASH_DISENGAGE", "target exceeded the configured combat leash");
            clearCombatState();
            return;
        }

        boolean hasLineOfSight = ctx.player().hasLineOfSight(currentTarget);
        boolean withinMeleeReach = SelfDefenceHelper.withinMeleeReach(ctx.playerHead(), currentTarget);
        if (!hasLineOfSight || (!withinMeleeReach && !canUseDirectCombatMovement(currentTarget))) {
            if (maceDivePhase != MACE_IDLE && currentAttackType == Settings.AttackType.MACE_SMASH) {
                // A powered climb intentionally leaves melee range. Keep the
                // committed state machine alive until the dive returns to the
                // target instead of resetting it at the top of the arc.
                handleMaceSmash();
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
            recordCombatFailure("NO_USABLE_WEAPON", "requested combat loadout could not be equipped");
            resetJumpState();
            releaseCombatInputs();
            return;
        }
        Settings.AttackType attackType = SelfDefenceHelper.effectiveAttackType(Baritone.settings().attackType.value, weapon);
        currentAttackType = attackType;
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        ctx.player().setSprinting(false);
        handleTotemHotswap();
        if (withinMeleeReach) {
            handleShield();
            if (attackType != Settings.AttackType.MACE_SMASH) {
                handleCombatMovement(weaponReady());
            } else {
                releaseMovementInputs(); // sideways drift would wreck the dive line
            }
        } else {
            releaseShield();
            if (attackType == Settings.AttackType.MACE_SMASH) {
                releaseMovementInputs();
                return;
            }
            handleCombatMovement(weaponReady());
            return;
        }
        if (SelfDefenceHelper.useJumpCrit(attackType)) {
            releaseShield();
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
        if (currentTarget != null) {
            recordCombatFailure("PLAYER_DIED", "player died while self defence was active");
        }
        clearCombatState();
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        renderCombatDecision(event);
        renderLastFailure(event);
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
        boolean chasing = needsChase();
        if (chasing && !wasChasing) {
            narrativeLog("chase=start target=" + currentTarget.getType().getDescription().getString()
                    + " direct=" + canUseDirectCombatMovement(currentTarget)
                    + " stuck=" + combatMovementStuck());
        } else if (!chasing && wasChasing) {
            narrativeLog("chase=end");
        }
        wasChasing = chasing;
        if (!chasing || currentTarget == null) {
            return null;
        }
        BlockPos targetBlock = currentTarget.blockPosition();
        if (!targetBlock.equals(lastChaseGoalBlock) || tickCounter - lastChaseGoalTick >= 15) {
            if (lastChaseGoalBlock == null || !targetBlock.equals(lastChaseGoalBlock)) {
                narrativeLog("chase=refresh goal=" + targetBlock.getX() + "," + targetBlock.getY() + "," + targetBlock.getZ());
            }
            lastChaseGoalBlock = targetBlock;
            lastChaseGoalTick = tickCounter;
            lastChaseGoal = new GoalNear(targetBlock, 1);
        }
        return lastChaseGoal;
    }

    public Mob getCurrentTarget() {
        return currentTarget;
    }

    public boolean isMaceDiveActive() {
        return maceDivePhase != MACE_IDLE;
    }

    public Optional<CombatFailureLogger.Failure> getLatestCombatFailure() {
        return combatFailureLogger.latest();
    }

    public Path getCombatFailureLogPath() {
        if (ctx.minecraft() == null) {
            return null;
        }
        return ctx.minecraft().gameDirectory.toPath().resolve("baritone").resolve("combat-failures.jsonl");
    }

    public void clearCombatFailures() {
        combatFailureLogger.clearFailures();
        renderFailureUntilTick = 0;
    }

    public boolean renderLatestCombatFailure(int ticks) {
        if (combatFailureLogger.latest().isEmpty()) {
            return false;
        }
        renderFailureUntilTick = tickCounter + Math.max(20, ticks);
        return true;
    }

    /**
     * Back away from any creeper inside selfDefenceCreeperSafeDistance, even if it
     * isn't the current target. Faces away from the creeper (looking at it would
     * ignite it) and walks away; no attacks happen while one is that close.
     */
    private boolean avoidNearbyCreepers() {
        if (maceDivePhase != MACE_IDLE) {
            return false; // never interrupt a committed climb/dive/escape cycle
        }
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
                && !SelfDefenceHelper.withinMeleeReach(ctx.playerHead(), currentTarget)
                && (!canUseDirectCombatMovement(currentTarget) || combatMovementStuck());
    }

    /**
     * True for a short window after the input-override combat movement got
     * completely blocked. Lets the baritone chase take over so the bot paths
     * around whatever the walk-only planner can't cross (pit edges, walls).
     */
    private boolean combatMovementStuck() {
        return tickCounter - lastCombatMoveBlockedTick >= 5
                && tickCounter - lastCombatMoveBlockedTick < 40;
    }

    private boolean canUseDirectCombatMovement(Mob target) {
        if (!Baritone.settings().selfDefenceCombatMovement.value
                || Baritone.settings().attackType.value == Settings.AttackType.MACE_SMASH
                || target == null
                || !ctx.player().hasLineOfSight(target)
                || !ctx.player().onGround()) {
            return false;
        }
        double controlDistance = Math.max(SelfDefenceHelper.MELEE_REACH,
                Baritone.settings().selfDefenceCombatControlDistance.value);
        return ctx.playerHead().distanceToSqr(SelfDefenceHelper.aimPoint(target, ctx.playerHead()))
                <= controlDistance * controlDistance;
    }

    private void kiteIfNeeded() {
        if (!Baritone.settings().selfDefenceKiteOnCooldown.value || currentTarget == null) {
            return;
        }
        if (Baritone.settings().selfDefenceCombatMovement.value) {
            handleCombatMovement(false);
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
        releaseMovementInputs();
    }

    private void handleCombatMovement(boolean attackReady) {
        if (!Baritone.settings().selfDefenceCombatMovement.value || currentTarget == null || !ctx.player().onGround()) {
            releaseMovementInputs();
            handleStrafe();
            return;
        }

        int horizon = Math.max(2, Math.min(20, Baritone.settings().selfDefenceCombatPredictionTicks.value));
        double attackDistance = Math.max(1.25D, Math.min(2.8D,
                Baritone.settings().selfDefenceCombatAttackDistance.value));
        double cooldownDistance = Baritone.settings().selfDefenceKiteOnCooldown.value
                ? Math.max(attackDistance + 0.55D, Baritone.settings().selfDefenceKiteDistance.value)
                : attackDistance;
        lastCombatHorizon = horizon;
        lastCombatAttackDistance = attackDistance;
        lastCombatCooldownDistance = cooldownDistance;
        Vec3 playerPos = ctx.player().position();
        Vec3 playerVelocity = ctx.player().getDeltaMovement();
        Vec3 targetPos = currentTarget.position();
        Vec3 targetVelocity = currentTarget.getDeltaMovement();

        CombatMovementPlanner.State state = new CombatMovementPlanner.State(
                playerPos.x, playerPos.z,
                playerVelocity.x, playerVelocity.z,
                targetPos.x, targetPos.z,
                targetVelocity.x, targetVelocity.z,
                attackReady,
                Baritone.settings().selfDefenceStrafe.value,
                attackDistance,
                cooldownDistance,
                horizon,
                activeCombatMove
        );
        combatCandidateRejections.clear();
        combatCandidateRejectionSamples.clear();
        lastCombatDecision = CombatMovementPlanner.choose(state, this::isSafeCombatCandidate);
        combatDecisionY = playerPos.y + 0.1D;
        CombatMovementPlanner.Move move = lastCombatDecision.move();
        if (lastCombatDecision.selected() != null && !lastCombatDecision.selected().safe()) {
            // Least-bad fallback: keep the candidate that makes the most
            // progress before being rejected, so a blocked spot doesn't freeze
            // the bot in place. Only a fully stuck spot falls back to HOLD.
            move = leastBadCombatMove(lastCombatDecision.candidates());
            if (move == CombatMovementPlanner.Move.HOLD) {
                lastCombatMoveBlockedTick = tickCounter;
                String reasons = combatCandidateRejections.entrySet().stream()
                        .limit(5)
                        .map(entry -> entry.getKey().name().toLowerCase(java.util.Locale.US) + '=' + entry.getValue())
                        .collect(java.util.stream.Collectors.joining(", "));
                recordCombatFailure("MOVEMENT_BLOCKED", "all predicted combat movement candidates were unsafe"
                        + (reasons.isEmpty() ? "" : ": " + reasons));
            }
        } else {
            lastCombatMoveBlockedTick = -1000;
        }
        applyCombatMove(move);
        lastCombatAction = "move:" + move.name().toLowerCase(java.util.Locale.US);
        logCombatDecision(move, attackReady, playerPos);
    }

    /**
     * Appends a selfdefence.log line when the applied move or its safety state
     * changes, so a fight can be replayed as a short sequence of decisions.
     */
    private void logCombatDecision(CombatMovementPlanner.Move move, boolean attackReady, Vec3 playerPos) {
        boolean fallbackUsed = lastCombatDecision.selected() != null && !lastCombatDecision.selected().safe();
        String key = move.name() + "|" + fallbackUsed;
        if (key.equals(lastNarrativeDecisionKey)) {
            return;
        }
        lastNarrativeDecisionKey = key;
        CombatMovementPlanner.Candidate chosen = null;
        for (CombatMovementPlanner.Candidate candidate : lastCombatDecision.candidates()) {
            if (candidate.move() == move) {
                chosen = candidate;
                break;
            }
        }
        String score = chosen == null ? "n/a" : String.format(java.util.Locale.US, "%.2f", chosen.score());
        narrativeLog("move=" + move.name().toLowerCase(java.util.Locale.US)
                + " score=" + score
                + " dist=" + String.format(java.util.Locale.US, "%.2f", playerPos.distanceTo(currentTarget.position()))
                + " attackReady=" + attackReady
                + (fallbackUsed ? " fallback=leastBad" : ""));
    }

    private CombatMovementPlanner.Move leastBadCombatMove(List<CombatMovementPlanner.Candidate> candidates) {
        return candidates.stream()
                .filter(candidate -> !candidate.safe())
                .min(Comparator.comparingInt((CombatMovementPlanner.Candidate candidate) ->
                                -combatCandidateRejectionSamples.getOrDefault(candidate.move(), 1))
                        .thenComparing(CombatMovementPlanner.Candidate::score))
                .map(CombatMovementPlanner.Candidate::move)
                .orElse(CombatMovementPlanner.Move.HOLD);
    }

    private boolean isSafeCombatCandidate(CombatMovementPlanner.Candidate candidate) {
        Vec3 origin = ctx.player().position();
        AABB box = ctx.player().getBoundingBox();
        double feetY = origin.y;
        List<CombatMovementPlanner.Point> path = candidate.path();
        for (int i = 1; i < path.size(); i++) {
            CombatMovementPlanner.Point point = path.get(i);
            double dx = point.x() - origin.x;
            double dz = point.z() - origin.z;
            CombatSupport support = findCombatSupport(point.x(), point.z(), feetY);
            if (support == null) {
                return rejectCombatCandidate(candidate, "no supported floor at sample " + i, i);
            }
            double step = support.feetY() - feetY;
            if (step > MAX_COMBAT_STEP_UP || step < -MAX_COMBAT_STEP_DOWN) {
                return rejectCombatCandidate(candidate, String.format(java.util.Locale.US,
                        "terrain step %.2f at sample %d", step, i), i);
            }
            feetY = support.feetY();
            // A sample that stays inside the player's current footprint cannot
            // collide: the player is demonstrably standing there. Guards against
            // false "collision at sample N" rejects on HOLD and on velocity
            // decay, where only the vertical support crawl moves the box.
            if (Math.abs(dx) < 0.05D && Math.abs(dz) < 0.05D) {
                continue;
            }
            if (!ctx.world().noCollision(ctx.player(), box.move(dx, feetY - origin.y, dz))) {
                return rejectCombatCandidate(candidate, "collision at sample " + i, i);
            }
            if (isCombatHazard(support.state().getBlock())) {
                return rejectCombatCandidate(candidate, "hazard " + support.state().getBlock() + " at sample " + i, i);
            }
        }
        return true;
    }

    /**
     * Finds the highest walkable surface under the player's 0.6-wide footprint
     * (center + both edges in x and z), scanning only downward. The old
     * single-column, above-first scan produced false "no supported floor" at a
     * pit edge and false "collision" rejects when the support resolved to a
     * block above the feet. Step-up is capped at half a block because this
     * walk-only planner never jumps.
     */
    private CombatSupport findCombatSupport(double x, double z, double expectedFeetY) {
        double bestTop = Double.NEGATIVE_INFINITY;
        BlockState bestState = null;
        double[] edge = {-0.3D, 0.0D, 0.3D};
        int[] offsets = {0, -1, -2, -3};
        for (double ox : edge) {
            for (double oz : edge) {
                BlockPos expectedFloor = BlockPos.containing(x + ox, expectedFeetY - 0.2D, z + oz);
                for (int offset : offsets) {
                    BlockPos floorPos = expectedFloor.offset(0, offset, 0);
                    BlockState floor = ctx.world().getBlockState(floorPos);
                    VoxelShape shape = floor.getCollisionShape(ctx.world(), floorPos);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    double top = floorPos.getY() + shape.max(Direction.Axis.Y);
                    if (top > expectedFeetY + MAX_COMBAT_STEP_UP) {
                        continue; // too tall to walk up without jumping
                    }
                    if (top < expectedFeetY - MAX_COMBAT_STEP_DOWN) {
                        break; // nothing reachable left in this column
                    }
                    if (top > bestTop) {
                        bestTop = top;
                        bestState = floor;
                    }
                    break; // highest surface in this column wins
                }
            }
        }
        return bestState == null ? null : new CombatSupport(bestTop, bestState);
    }

    private boolean rejectCombatCandidate(CombatMovementPlanner.Candidate candidate, String reason) {
        return rejectCombatCandidate(candidate, reason, 1);
    }

    private boolean rejectCombatCandidate(CombatMovementPlanner.Candidate candidate, String reason, int sample) {
        combatCandidateRejections.put(candidate.move(), reason);
        combatCandidateRejectionSamples.put(candidate.move(), sample);
        return false;
    }

    private static boolean isCombatHazard(Block block) {
        return block == Blocks.LAVA
                || block == Blocks.FIRE
                || block == Blocks.SOUL_FIRE
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.POWDER_SNOW
                || block == Blocks.MAGMA_BLOCK;
    }

    private void applyCombatMove(CombatMovementPlanner.Move move) {
        clearOwnedMovementInputs();
        activeCombatMove = move == null ? CombatMovementPlanner.Move.HOLD : move;
        if (activeCombatMove == CombatMovementPlanner.Move.HOLD) {
            return;
        }
        combatMovementOwned = true;
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, activeCombatMove.forward() > 0);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, activeCombatMove.forward() < 0);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, activeCombatMove.strafe() < 0);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, activeCombatMove.strafe() > 0);
    }

    private void releaseMovementInputs() {
        clearOwnedMovementInputs();
        activeCombatMove = CombatMovementPlanner.Move.HOLD;
        lastCombatDecision = null;
    }

    private void clearOwnedMovementInputs() {
        if (!combatMovementOwned) {
            return;
        }
        combatMovementOwned = false;
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
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
    private boolean equipFireworkRocket() {
        for (int i = 0; i < 9; i++) {
            if (ctx.player().getInventory().getItem(i).getItem() == Items.FIREWORK_ROCKET) {
                ctx.player().getInventory().setSelectedSlot(i);
                ctx.playerController().syncHeldItem();
                return true;
            }
        }
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 9; i < invy.size(); i++) {
            if (invy.get(i).getItem() == Items.FIREWORK_ROCKET) {
                OptionalInt slot = baritone.getInventoryBehavior().attemptToPutOnHotbarAndGetSlot(i, s -> s == 0);
                if (slot.isPresent()) {
                    ctx.player().getInventory().setSelectedSlot(slot.getAsInt());
                    ctx.playerController().syncHeldItem();
                    return ctx.player().getMainHandItem().getItem() == Items.FIREWORK_ROCKET;
                }
                return false;
            }
        }
        return false;
    }

    private void handleMaceSmash() {
        boolean hasElytra = ctx.player().getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
        switch (maceDivePhase) {
            case MACE_IDLE:
                if (!ctx.player().onGround()) {
                    return;
                }
                if (!weaponReady()) {
                    kiteIfNeeded();
                    resetJumpState();
                    return;
                }
                if (!hasElytra || !hasFireworkRocket() || findEmptyMaceDiveSlot() == -1) {
                    startPlainMaceJump();
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
                    jumpAttackQueued = false;
                    releaseJumpInput();
                    return;
                }
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                maceLaunchRetries = 0;
                macePeakFallDistance = 0.0F;
                maceDivePhase = MACE_LAUNCH;
                maceDiveTick = tickCounter;
                jumpAttackQueued = true;
                jumpInputOwned = true;
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                return;
            case MACE_LAUNCH:
                if (tickCounter - maceDiveTick >= 1) {
                    releaseJumpInput(); // deployment needs a new jump press while falling
                }
                if (!ctx.player().onGround() && ctx.player().getDeltaMovement().y < -0.02D) {
                    pulseMaceJump();
                    maceDivePhase = MACE_DEPLOY;
                    maceDiveTick = tickCounter;
                    return;
                }
                if (tickCounter - maceDiveTick > 12) {
                    recordCombatFailure("MACE_LAUNCH_FAILED", "jump never reached the Elytra deployment window");
                    resetMaceDive();
                }
                return;
            case MACE_DEPLOY:
                if (tickCounter - maceDiveTick >= 1) {
                    releaseJumpInput();
                }
                if (ctx.player().isFallFlying()) {
                    maceDivePhase = MACE_AIM_CLIMB;
                    maceDiveTick = tickCounter;
                    aimForMaceClimb();
                    return;
                }
                if (!ctx.player().onGround()
                        && ctx.player().getDeltaMovement().y < -0.02D
                        && (tickCounter - maceDiveTick) % 3 == 2) {
                    pulseMaceJump();
                }
                if (ctx.player().onGround() || tickCounter - maceDiveTick > 12) {
                    recordCombatFailure("MACE_DEPLOY_FAILED", "Elytra did not enter fall-flying state");
                    resetMaceDive();
                }
                return;
            case MACE_AIM_CLIMB:
                releaseJumpInput();
                aimForMaceClimb();
                if (!ctx.player().isFallFlying()) {
                    if (ctx.player().onGround()) {
                        resetMaceDive();
                    } else {
                        pulseMaceJump();
                    }
                    return;
                }
                float climbPitch = maceClimbPitch();
                if (Math.abs(ctx.playerRotations().getPitch() - climbPitch) <= 12.0F
                        || tickCounter - maceDiveTick >= 5) {
                    if (!useFireworkRocket()) {
                        abortMaceDiveSafely("firework could not be equipped or used");
                        return;
                    }
                    maceClimbStartY = ctx.player().position().y;
                    maceDivePhase = MACE_POWERED_CLIMB;
                    maceDiveTick = tickCounter;
                }
                return;
            case MACE_POWERED_CLIMB:
                aimForMaceClimb();
                if (!ctx.player().isFallFlying()) {
                    if (ctx.player().onGround() && maceLaunchRetries < 1 && hasFireworkRocket()) {
                        maceLaunchRetries++;
                        maceDivePhase = MACE_LAUNCH;
                        maceDiveTick = tickCounter;
                        pulseMaceJump();
                        return;
                    }
                    abortMaceDiveSafely("elytra flight ended during powered climb");
                    return;
                }
                int climbElapsed = tickCounter - maceDiveTick;
                boolean climbComplete = SelfDefenceHelper.shouldFinishMaceClimb(
                        maceClimbStartY,
                        ctx.player().position().y,
                        ctx.player().getDeltaMovement().y,
                        climbElapsed,
                        Baritone.settings().selfDefenceMaceClimbHeight.value,
                        Baritone.settings().selfDefenceMaceClimbMaxTicks.value
                );
                if (climbComplete && weaponReady()) {
                    macePeakFallDistance = 0.0F;
                    maceDivePhase = MACE_STOW_ELYTRA;
                    maceDiveTick = tickCounter;
                    aimAtMaceTarget();
                } else if (climbComplete
                        && climbElapsed >= Math.max(10, Baritone.settings().selfDefenceMaceClimbMaxTicks.value) + 20) {
                    abortMaceDiveSafely(String.format(java.util.Locale.US,
                            "mace cooldown did not recover during powered climb (cooldown=%.2f)",
                            ctx.player().getAttackStrengthScale(0.5F)));
                }
                return;
            case MACE_STOW_ELYTRA:
                aimAtMaceTarget();
                if (hasElytra && !stowElytraForDive()) {
                    abortMaceDiveSafely("elytra could not be stowed for normal-fall smash damage");
                    return;
                }
                if (ctx.player().isFallFlying()) {
                    ctx.player().stopFallFlying();
                    if (tickCounter - maceDiveTick <= 6) {
                        return;
                    }
                }
                maceDivePhase = MACE_DIVE;
                maceDiveTick = tickCounter;
                return;
            case MACE_DIVE:
                aimAtMaceTarget();
                macePeakFallDistance = Math.max(macePeakFallDistance, ctx.player().fallDistance);
                if (ctx.player().isFallFlying()) {
                    ctx.player().stopFallFlying();
                    return;
                }
                if (!ctx.player().onGround()
                        && ctx.player().fallDistance > 1.5F
                        && ctx.player().getDeltaMovement().y < 0.0D
                        && !ctx.player().isInWater()
                        && !ctx.player().onClimbable()
                        && SelfDefenceHelper.withinMeleeReach(ctx.playerHead(), currentTarget)
                        && equipMaceForDive()
                        && weaponReady()) {
                    attemptAttack(currentTarget, true, Settings.AttackType.MACE_SMASH, true);
                    if (hasFireworkRocket() && stowedElytraSlot >= 0) {
                        maceDivePhase = MACE_RESTORE_ELYTRA;
                        maceDiveTick = tickCounter;
                    } else {
                        resetMaceDive();
                    }
                    return;
                }
                if (!ctx.player().onGround() && ctx.player().fallDistance > 12.0F && stowedElytraSlot >= 0) {
                    maceDivePhase = MACE_RESTORE_ELYTRA; // crater guard
                    maceDiveTick = tickCounter;
                    return;
                }
                if (ctx.player().onGround()) {
                    recordCombatFailure("MACE_LANDED_NO_HIT",
                            String.format(java.util.Locale.US, "peakFallDistance=%.2f targetDistance=%.2f cooldown=%.2f",
                                    macePeakFallDistance,
                                    currentTarget == null ? Double.NaN : ctx.playerHead().distanceTo(currentTarget.getEyePosition()),
                                    ctx.player().getAttackStrengthScale(0.5F)));
                    restoreStowedElytra();
                    resetMaceDive();
                }
                return;
            case MACE_RESTORE_ELYTRA:
                if (!restoreStowedElytra()) {
                    if (ctx.player().onGround()) {
                        resetMaceDive();
                    }
                    return;
                }
                if (ctx.player().onGround()) {
                    resetMaceDive();
                    return;
                }
                maceDivePhase = MACE_ESCAPE_DEPLOY;
                maceDiveTick = tickCounter;
                pulseMaceJump();
                return;
            case MACE_ESCAPE_DEPLOY:
                if (ctx.player().onGround()) {
                    recordCombatFailure("MACE_ESCAPE_FAILED", "landed before Elytra escape deployment");
                    resetMaceDive();
                    return;
                }
                if (tickCounter - maceDiveTick >= 1) {
                    releaseJumpInput();
                }
                if (ctx.player().isFallFlying()) {
                    maceDivePhase = MACE_AIM_CLIMB;
                    maceDiveTick = tickCounter;
                    return;
                }
                if (ctx.player().getDeltaMovement().y < -0.02D
                        && (tickCounter - maceDiveTick) % 3 == 2) {
                    pulseMaceJump();
                }
                if (tickCounter - maceDiveTick > 12) {
                    recordCombatFailure("MACE_ESCAPE_FAILED", "Elytra escape deployment timed out");
                    restoreStowedElytra();
                    resetMaceDive();
                }
                return;
            case MACE_PLAIN_JUMP:
                handlePlainMaceJump();
                return;
            default:
                resetMaceDive();
        }
    }

    private void startPlainMaceJump() {
        maceDivePhase = MACE_PLAIN_JUMP;
        maceDiveTick = tickCounter;
        jumpAttackQueued = true;
        pulseMaceJump();
    }

    private void handlePlainMaceJump() {
        if (tickCounter - maceDiveTick >= 1) {
            releaseJumpInput();
        }
        if (ctx.player().onGround()) {
            if (tickCounter - maceDiveTick > 5) {
                recordCombatFailure("MACE_PLAIN_JUMP_FAILED", "plain jump landed without a valid mace hit");
                resetMaceDive();
            }
            return;
        }
        if (ctx.player().getDeltaMovement().y < 0.0D
                && ctx.player().fallDistance > 0.0F
                && SelfDefenceHelper.withinMeleeReach(ctx.playerHead(), currentTarget)
                && equipMaceForDive()
                && weaponReady()) {
            attemptAttack(currentTarget, true, Settings.AttackType.MACE_SMASH, true);
            resetMaceDive();
        }
    }

    private void pulseMaceJump() {
        jumpInputOwned = true;
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
    }

    private void aimForMaceClimb() {
        float yaw = currentTarget == null
                ? ctx.playerRotations().getYaw()
                : SelfDefenceHelper.rotationToTarget(ctx.playerHead(), ctx.playerRotations(), currentTarget).getYaw();
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, maceClimbPitch()), false);
    }

    private float maceClimbPitch() {
        return (float) Math.max(-89.0D, Math.min(-20.0D, Baritone.settings().selfDefenceMaceClimbPitch.value));
    }

    private void aimAtMaceTarget() {
        if (currentTarget != null) {
            baritone.getLookBehavior().updateTarget(
                    SelfDefenceHelper.rotationToTarget(ctx.playerHead(), ctx.playerRotations(), currentTarget),
                    false
            );
        }
    }

    private boolean useFireworkRocket() {
        if (!equipFireworkRocket()) {
            return false;
        }
        ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean equipMaceForDive() {
        SelfDefenceHelper.WeaponChoice weapon = equipWeapon();
        return weapon != null && weapon.family == SelfDefenceHelper.WeaponFamily.MACE;
    }

    private int findEmptyMaceDiveSlot() {
        NonNullList<ItemStack> inventory = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private boolean stowElytraForDive() {
        if (ctx.player().getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            return stowedElytraSlot >= 0;
        }
        if (!ctx.player().inventoryMenu.getCarried().isEmpty()) {
            return false;
        }
        int emptySlot = findEmptyMaceDiveSlot();
        if (emptySlot < 0) {
            return false;
        }
        int containerId = ctx.player().inventoryMenu.containerId;
        int destinationSlot = inventoryContainerSlot(emptySlot);
        ctx.playerController().windowClick(containerId, CHEST_CONTAINER_SLOT, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, destinationSlot, 0, ClickType.PICKUP, ctx.player());
        stowedElytraSlot = emptySlot;
        ctx.player().stopFallFlying();
        return true;
    }

    private boolean restoreStowedElytra() {
        if (ctx.player() == null) {
            return false;
        }
        if (ctx.player().getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
            stowedElytraSlot = -1;
            return true;
        }
        if (stowedElytraSlot < 0 || !ctx.player().inventoryMenu.getCarried().isEmpty()) {
            return false;
        }
        NonNullList<ItemStack> inventory = ctx.player().getInventory().getNonEquipmentItems();
        if (stowedElytraSlot >= inventory.size() || inventory.get(stowedElytraSlot).getItem() != Items.ELYTRA) {
            return false;
        }
        int containerId = ctx.player().inventoryMenu.containerId;
        int sourceSlot = inventoryContainerSlot(stowedElytraSlot);
        ctx.playerController().windowClick(containerId, sourceSlot, 0, ClickType.PICKUP, ctx.player());
        ctx.playerController().windowClick(containerId, CHEST_CONTAINER_SLOT, 0, ClickType.PICKUP, ctx.player());
        stowedElytraSlot = -1;
        return ctx.player().getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    private static int inventoryContainerSlot(int inventorySlot) {
        return inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
    }

    private void abortMaceDiveSafely(String reason) {
        recordCombatFailure("MACE_ABORT", reason);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        restoreStowedElytra();
        if (ctx.player().onGround() || ctx.player().isFallFlying()) {
            resetMaceDive();
            return;
        }
        if (maceDivePhase != MACE_ESCAPE_DEPLOY) {
            maceDivePhase = MACE_ESCAPE_DEPLOY;
            maceDiveTick = tickCounter;
            releaseJumpInput();
        }
        int elapsed = tickCounter - maceDiveTick;
        if (elapsed >= 1) {
            releaseJumpInput();
        }
        if (ctx.player().getDeltaMovement().y < 0.0D && elapsed % 3 == 0) {
            pulseMaceJump();
        }
        if (elapsed > 12) {
            resetMaceDive();
        }
    }

    private void resetMaceDive() {
        if (ctx.player() != null) {
            restoreStowedElytra();
        }
        maceDivePhase = MACE_IDLE;
        maceLaunchRetries = 0;
        macePeakFallDistance = 0.0F;
        jumpAttackQueued = false;
        releaseJumpInput();
        // Do not clear CLICK_RIGHT here. resetMaceDive() also runs from the
        // inactive self-defence cleanup path, and CLICK_RIGHT may belong to
        // builder/movement placement rather than this behavior. Shield input
        // is released by releaseShield(), which tracks its ownership.
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
            recordCombatFailure("JUMP_ATTACK_TIMEOUT", "jump attack did not reach a valid descending attack window");
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
        lastCombatAction = "attack:" + type.name().toLowerCase(java.util.Locale.US);
        ctx.playerController().attackEntity(ctx.player(), target);
    }

    private void checkPendingAttackResult() {
        if (pendingAttack == null) {
            return;
        }
        PendingAttack p = pendingAttack;
        if (!p.target().isAlive()) {
            pendingAttack = null; // killed (or died) - the hit landed
            narrativeLog("attack=" + p.attackType().name().toLowerCase(java.util.Locale.US)
                    + " hit=true killed=true dist=" + String.format(java.util.Locale.US, "%.2f", p.distance())
                    + " target=" + entityKey(p.target()));
            return;
        }
        if (p.target().getHealth() < p.healthBefore()) {
            double damage = p.healthBefore() - p.target().getHealth();
            pendingAttack = null; // damage applied - the hit landed
            narrativeLog("attack=" + p.attackType().name().toLowerCase(java.util.Locale.US)
                    + " hit=true dmg=" + String.format(java.util.Locale.US, "%.2f", damage)
                    + " dist=" + String.format(java.util.Locale.US, "%.2f", p.distance())
                    + " target=" + entityKey(p.target())
                    + " hp=" + String.format(java.util.Locale.US, "%.1f->%.1f", p.healthBefore(), p.target().getHealth()));
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
                maceDivePhase = MACE_RESTORE_ELYTRA;
                maceDiveTick = tickCounter;
            }
        }
    }

    /**
     * Human-readable narrative log (baritone/selfdefence.log): combat decision
     * changes, chase transitions, and every attack outcome. One line per event;
     * gated by the selfDefenceNarrativeLog setting.
     */
    private void narrativeLog(String line) {
        if (!Baritone.settings().selfDefenceNarrativeLog.value || ctx.minecraft() == null) {
            return;
        }
        try {
            java.nio.file.Path logFile = ctx.minecraft().gameDirectory.toPath()
                    .resolve("baritone").resolve("selfdefence.log");
            java.nio.file.Files.createDirectories(logFile.getParent());
            java.nio.file.Files.writeString(logFile,
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            + " tick=" + tickCounter + " " + line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Helper.HELPER.logDirect("[SelfDefence] failed to write selfdefence.log: " + e.getMessage());
        }
    }

    private String entityKey(Mob entity) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
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
        recordCombatFailure("ATTACK_MISSED", line);
        narrativeLog("attack=" + p.attackType().name().toLowerCase(java.util.Locale.US)
                + " hit=false dist=" + String.format(java.util.Locale.US, "%.2f", p.distance())
                + " target=" + entityKey(p.target())
                + " hp=" + String.format(java.util.Locale.US, "%.1f->%.1f", p.healthBefore(), p.target().getHealth()));
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

    private void captureCombatFrame() {
        if (!Baritone.settings().selfDefenceFailureLog.value
                || ctx.player() == null
                || currentTarget == null
                || lastCapturedFrameTick == tickCounter) {
            return;
        }
        lastCapturedFrameTick = tickCounter;
        combatFailureLogger.setMaxFrames(Baritone.settings().selfDefenceFailureFrames.value);
        Vec3 player = ctx.player().position();
        Vec3 playerVelocity = ctx.player().getDeltaMovement();
        Vec3 target = currentTarget.position();
        Vec3 targetVelocity = currentTarget.getDeltaMovement();
        combatFailureLogger.capture(new CombatFailureLogger.Frame(
                tickCounter,
                currentCombatAction(),
                player.x, player.y, player.z,
                playerVelocity.x, playerVelocity.y, playerVelocity.z,
                target.x, target.y, target.z,
                targetVelocity.x, targetVelocity.y, targetVelocity.z,
                player.distanceTo(target),
                ctx.player().getHealth(),
                ctx.player().getAttackStrengthScale(0.5F),
                ctx.playerRotations().getYaw(),
                ctx.playerRotations().getPitch(),
                ctx.player().fallDistance,
                ctx.player().onGround(),
                ctx.player().isFallFlying(),
                weaponReady(),
                ctx.player().hasLineOfSight(currentTarget),
                currentTarget.getHealth()
        ));
    }

    private String currentCombatAction() {
        if (maceDivePhase != MACE_IDLE) {
            return switch (maceDivePhase) {
                case MACE_LAUNCH -> "mace:launch";
                case MACE_DEPLOY -> "mace:deploy";
                case MACE_AIM_CLIMB -> "mace:aim_climb";
                case MACE_POWERED_CLIMB -> "mace:powered_climb";
                case MACE_STOW_ELYTRA -> "mace:stow_elytra";
                case MACE_DIVE -> "mace:dive";
                case MACE_RESTORE_ELYTRA -> "mace:restore_elytra";
                case MACE_ESCAPE_DEPLOY -> "mace:escape_deploy";
                case MACE_PLAIN_JUMP -> "mace:plain_jump";
                default -> "mace:unknown";
            };
        }
        return lastCombatAction;
    }

    private void recordCombatFailure(String reason, String detail) {
        if (!Baritone.settings().selfDefenceFailureLog.value || ctx.minecraft() == null) {
            return;
        }
        if (reason.equals(lastFailureReason) && tickCounter - lastFailureTick < 20) {
            return;
        }
        lastFailureReason = reason;
        lastFailureTick = tickCounter;
        captureCombatFrame();
        CombatFailureLogger.Failure failure;
        if ("MOVEMENT_BLOCKED".equals(reason) && lastCombatDecision != null) {
            // Attach the full candidate breakdown + settings snapshot so the
            // JSONL says exactly why every move was rejected and which one won.
            List<CombatFailureLogger.CandidateInfo> candidates = new ArrayList<>();
            for (CombatMovementPlanner.Candidate candidate : lastCombatDecision.candidates()) {
                candidates.add(new CombatFailureLogger.CandidateInfo(
                        candidate.move().name().toLowerCase(java.util.Locale.US),
                        candidate.score(),
                        candidate.safe(),
                        combatCandidateRejections.get(candidate.move())));
            }
            String context = String.format(java.util.Locale.US,
                    "horizon=%d attackDist=%.2f cooldownDist=%.2f strafe=%s selected=%s",
                    lastCombatHorizon, lastCombatAttackDistance, lastCombatCooldownDistance,
                    Baritone.settings().selfDefenceStrafe.value,
                    lastCombatDecision.move().name().toLowerCase(java.util.Locale.US));
            failure = combatFailureLogger.record(reason, detail, context, candidates);
        } else {
            failure = combatFailureLogger.record(reason, detail);
        }
        Helper.HELPER.logDirect(String.format(
                "[SelfDefence] FAIL %s: %s (%d trace frames; #lastfail for details)",
                reason, detail, failure.frames().size()));
        Path logFile = getCombatFailureLogPath();
        if (logFile == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(logFile.getParent());
            java.nio.file.Files.writeString(logFile,
                    CombatFailureLogger.toJson(failure) + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            Helper.HELPER.logDirect("[SelfDefence] failed to write combat failure trace: " + e.getMessage());
        }
    }

    private void renderCombatDecision(RenderEvent event) {
        if (!Baritone.settings().selfDefenceRenderCombatMovement.value || lastCombatDecision == null) {
            return;
        }
        for (CombatMovementPlanner.Candidate candidate : lastCombatDecision.candidates()) {
            Color color = !candidate.safe()
                    ? new Color(255, 55, 55)
                    : candidate.move() == lastCombatDecision.move()
                    ? new Color(45, 255, 90)
                    : new Color(255, 210, 45);
            IRenderer.startLines(color, 0.75F, Baritone.settings().pathRenderLineWidthPixels.value, true);
            List<CombatMovementPlanner.Point> path = candidate.path();
            for (int i = 1; i < path.size(); i++) {
                CombatMovementPlanner.Point previous = path.get(i - 1);
                CombatMovementPlanner.Point current = path.get(i);
                if (Math.hypot(current.x() - previous.x(), current.z() - previous.z()) > 1.0E-5D) {
                    IRenderer.emitLine(event.getModelViewStack(),
                            new Vec3(previous.x(), combatDecisionY, previous.z()),
                            new Vec3(current.x(), combatDecisionY, current.z()));
                }
            }
            IRenderer.endLines(true);
        }
        CombatMovementPlanner.Candidate selected = lastCombatDecision.selected();
        if (selected != null) {
            IRenderer.startLines(new Color(40, 220, 255), 0.9F,
                    Baritone.settings().pathRenderLineWidthPixels.value, true);
            IRenderer.emitAABB(event.getModelViewStack(), new AABB(
                    selected.predictedTargetX() - 0.2D, combatDecisionY - 0.2D, selected.predictedTargetZ() - 0.2D,
                    selected.predictedTargetX() + 0.2D, combatDecisionY + 0.2D, selected.predictedTargetZ() + 0.2D));
            IRenderer.endLines(true);
        }
    }

    private void renderLastFailure(RenderEvent event) {
        if (tickCounter > renderFailureUntilTick) {
            return;
        }
        Optional<CombatFailureLogger.Failure> latest = combatFailureLogger.latest();
        if (latest.isEmpty() || latest.get().frames().size() < 2) {
            return;
        }
        List<CombatFailureLogger.Frame> frames = latest.get().frames();
        IRenderer.startLines(Color.WHITE, 0.9F, Baritone.settings().pathRenderLineWidthPixels.value, true);
        for (int i = 1; i < frames.size(); i++) {
            CombatFailureLogger.Frame previous = frames.get(i - 1);
            CombatFailureLogger.Frame current = frames.get(i);
            emitNonZeroLine(event,
                    new Vec3(previous.playerX(), previous.playerY() + 0.1D, previous.playerZ()),
                    new Vec3(current.playerX(), current.playerY() + 0.1D, current.playerZ()));
        }
        IRenderer.endLines(true);

        IRenderer.startLines(new Color(40, 220, 255), 0.8F,
                Baritone.settings().pathRenderLineWidthPixels.value, true);
        for (int i = 1; i < frames.size(); i++) {
            CombatFailureLogger.Frame previous = frames.get(i - 1);
            CombatFailureLogger.Frame current = frames.get(i);
            emitNonZeroLine(event,
                    new Vec3(previous.targetX(), previous.targetY() + 0.1D, previous.targetZ()),
                    new Vec3(current.targetX(), current.targetY() + 0.1D, current.targetZ()));
        }
        IRenderer.endLines(true);
    }

    private static void emitNonZeroLine(RenderEvent event, Vec3 start, Vec3 end) {
        if (start.distanceToSqr(end) > 1.0E-8D) {
            IRenderer.emitLine(event.getModelViewStack(), start, end);
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

    private record CombatSupport(double feetY, BlockState state) {
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
        if (maceDivePhase == MACE_IDLE
                && allowedDistance > 0
                && currentTarget.distanceToSqr(ctx.player()) > allowedDistance * allowedDistance) {
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
        lastCombatAction = "idle";
        releaseCombatInputs();
        resetJumpState();
    }

    private void resetJumpState() {
        resetMaceDive();
    }

    private void releaseJumpInput() {
        if (!jumpInputOwned) {
            return;
        }
        jumpInputOwned = false;
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
    }
}

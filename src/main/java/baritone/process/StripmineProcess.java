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
import baritone.api.pathing.goals.*;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.cache.WorldData;
import baritone.pathing.movement.CalculationContext;
import baritone.process.MineProcess;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.multiplayer.ClientLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.stream.Collectors;

public final class StripmineProcess extends BaritoneProcessHelper {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class SavedConfig {
        int[] deposit; // [x, y, z], null if unset
        int[] junk;    // [x, y, z], null if unset
    }

    private static final Set<Item> ORE_DROPS;
    static {
        Set<Item> s = new HashSet<>();
        // raw/gem/dust drops
        s.add(Items.COAL);
        s.add(Items.RAW_IRON);
        s.add(Items.RAW_COPPER);
        s.add(Items.RAW_GOLD);
        s.add(Items.DIAMOND);
        s.add(Items.EMERALD);
        s.add(Items.LAPIS_LAZULI);
        s.add(Items.REDSTONE);
        // silk-touch ore blocks (overworld)
        s.add(Items.COAL_ORE);        s.add(Items.DEEPSLATE_COAL_ORE);
        s.add(Items.IRON_ORE);        s.add(Items.DEEPSLATE_IRON_ORE);
        s.add(Items.COPPER_ORE);      s.add(Items.DEEPSLATE_COPPER_ORE);
        s.add(Items.GOLD_ORE);        s.add(Items.DEEPSLATE_GOLD_ORE);
        s.add(Items.DIAMOND_ORE);     s.add(Items.DEEPSLATE_DIAMOND_ORE);
        s.add(Items.EMERALD_ORE);     s.add(Items.DEEPSLATE_EMERALD_ORE);
        s.add(Items.LAPIS_ORE);       s.add(Items.DEEPSLATE_LAPIS_ORE);
        s.add(Items.REDSTONE_ORE);    s.add(Items.DEEPSLATE_REDSTONE_ORE);
        ORE_DROPS = Collections.unmodifiableSet(s);
    }

    private enum Phase { IDLE, MINING, RETURNING, DEPOSIT_APPROACH, DEPOSIT_OPEN, DEPOSIT_TRANSFER }

    private Phase phase = Phase.IDLE;
    private List<BlockPos> targets;
    private BlockPos depositPos;

    // deposit state
    private List<BlockPos> pendingChests;   // chests still to visit this deposit run
    private BlockPos currentChest;
    private int depositTimer;               // ticks since last action in deposit phases

    private BlockPos junkChestPos;          // chest to dump non-ore block items
    private boolean dumpingJunk;
    private boolean overflowDump;           // true when doing the orphan-ore overflow pass
    private boolean completionDeposit;      // true when depositing after all corridors are done
    private Set<Item> seenInOreChests;      // item types seen across all ore chests this run
    private long chestOpenTime;             // wall-clock ms when the current chest GUI was confirmed open

    // chest cache — persists across deposit runs; cleared when deposit location changes
    private final Map<BlockPos, Set<Item>> chestCache = new HashMap<>();

    // vein expansion
    private Set<BlockPos> corridorSet;      // original planned positions (never modified after start)
    private Set<BlockPos> veinScanned;      // corridor positions whose neighbors have been checked

    // safety — blocks adjacent to lava/water that should not be mined
    private final Set<BlockPos> unsafeTargets = new HashSet<>();

    public StripmineProcess(Baritone baritone) {
        super(baritone);
    }

    public Set<BlockPos> getUnsafeTargets() {
        return Collections.unmodifiableSet(unsafeTargets);
    }

    public void start(BetterBlockPos origin, Direction facing, int length, int depth) {
        loadConfig(); // restore deposit/junk positions from disk if not set this session
        targets = buildTargets(origin, facing, length, depth);
        corridorSet  = new HashSet<>(targets);
        veinScanned  = new HashSet<>();
        unsafeTargets.clear();
        phase = Phase.MINING;
        logDirect(String.format("Strip mine started: %d positions, heading %s, %d corridor(s)",
                targets.size(), facing.getName(), depth));
    }

    public void setDeposit(BlockPos pos) {
        this.depositPos = pos;
        this.chestCache.clear();
        saveConfig();
        logDirect("Strip mine deposit set to " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    public BlockPos getDepositPos() {
        return depositPos;
    }

    public void setJunk(BlockPos pos) {
        this.junkChestPos = pos;
        saveConfig();
        logDirect("Strip mine junk chest set to " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    // ── persistence ───────────────────────────────────────────────────────────

    private Path getSaveFile() {
        try {
            WorldData world = baritone.getWorldProvider().getCurrentWorld();
            if (world != null) return world.directory.resolve("stripmine.json");
        } catch (Exception ignored) {}
        return baritone.getDirectory().resolve("stripmine.json");
    }

    private void saveConfig() {
        SavedConfig cfg = new SavedConfig();
        if (depositPos  != null) cfg.deposit = new int[]{depositPos.getX(),  depositPos.getY(),  depositPos.getZ()};
        if (junkChestPos != null) cfg.junk   = new int[]{junkChestPos.getX(), junkChestPos.getY(), junkChestPos.getZ()};
        try {
            Files.writeString(getSaveFile(), GSON.toJson(cfg));
        } catch (IOException e) {
            logDirect("Failed to save stripmine config: " + e.getMessage());
        }
    }

    /** Fills null fields from disk; never overwrites values already set this session. */
    private void loadConfig() {
        Path file = getSaveFile();
        if (!Files.exists(file)) return;
        try {
            SavedConfig cfg = GSON.fromJson(Files.readString(file), SavedConfig.class);
            if (cfg == null) return;
            if (depositPos == null && cfg.deposit != null && cfg.deposit.length == 3) {
                depositPos = new BlockPos(cfg.deposit[0], cfg.deposit[1], cfg.deposit[2]);
                logDirect("Deposit loaded from disk: " + depositPos.getX() + " " + depositPos.getY() + " " + depositPos.getZ());
            }
            if (junkChestPos == null && cfg.junk != null && cfg.junk.length == 3) {
                junkChestPos = new BlockPos(cfg.junk[0], cfg.junk[1], cfg.junk[2]);
                logDirect("Junk chest loaded from disk: " + junkChestPos.getX() + " " + junkChestPos.getY() + " " + junkChestPos.getZ());
            }
        } catch (Exception e) {
            logDirect("Failed to load stripmine config: " + e.getMessage());
        }
    }

    public BlockPos getJunkChestPos() {
        return junkChestPos;
    }

    @Override
    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (phase) {
            case MINING:           return tickMining(isSafeToCancel);
            case RETURNING:        return tickReturning();
            case DEPOSIT_APPROACH: return tickApproach(isSafeToCancel);
            case DEPOSIT_OPEN:     return tickOpen(isSafeToCancel);
            case DEPOSIT_TRANSFER: return tickTransfer(isSafeToCancel);
            default:               return new PathingCommand(null, PathingCommandType.DEFER);
        }
    }

    // ── MINING ───────────────────────────────────────────────────────────────

    private PathingCommand tickMining(boolean isSafeToCancel) {
        // Classify targets: remove mined ones, move lava/water-adjacent ones to unsafeTargets
        targets.removeIf(pos -> {
            if (MovementHelper.canWalkThrough(ctx, new BetterBlockPos(pos))) {
                unsafeTargets.remove(pos);
                return true; // already mined
            }
            if (!isSafeToMine(pos)) {
                unsafeTargets.add(pos);
                return true; // dangerous — park in unsafeTargets, render in orange
            }
            return false;
        });
        // Re-promote unsafe targets that have become safe (e.g. lava flowed away), or drop if mined
        unsafeTargets.removeIf(pos -> {
            if (MovementHelper.canWalkThrough(ctx, new BetterBlockPos(pos))) return true;
            if (isSafeToMine(pos)) { targets.add(pos); return true; }
            return false;
        });

        if (Baritone.settings().veinMine.value && corridorSet != null) {
            expandVeins();
        }
        if (Baritone.settings().legitMine.value) {
            scanNearbyOres();
        }

        if (targets.isEmpty()) {
            if (depositPos != null && !completionDeposit) {
                logDirect("Strip mine complete — depositing before exit");
                completionDeposit = true;
                phase = Phase.RETURNING;
                return tickReturning();
            }
            logDirect("Strip mine complete");
            onLostControl();
            return null;
        }

        if (depositPos != null && inventoryTooFull()) {
            logDirect("Inventory full — heading to deposit at "
                    + depositPos.getX() + " " + depositPos.getY() + " " + depositPos.getZ());
            phase = Phase.RETURNING;
            return tickReturning();
        }

        // Break a block directly in-shaft above the player
        BetterBlockPos feet = ctx.playerFeet();
        Optional<BlockPos> shaft = targets.stream()
                .filter(pos -> pos.getX() == feet.getX() && pos.getZ() == feet.getZ()
                        && pos.getY() >= feet.getY()
                        && !MovementHelper.canWalkThrough(ctx, new BetterBlockPos(pos)))
                .min(Comparator.comparingDouble(pos -> feet.above().distSqr(pos)));

        if (shaft.isPresent() && ctx.player().onGround() && isSafeToCancel) {
            BlockPos pos = shaft.get();
            BlockState state = baritone.bsi.get0(pos);
            if (!MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
                Optional<baritone.api.utils.Rotation> rot = RotationUtils.reachable(ctx, pos);
                if (rot.isPresent()) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                    if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        int cap = Math.min(targets.size(), Baritone.settings().mineMaxOreLocationsCount.value);
        List<BlockPos> nearest = new ArrayList<>(targets);
        nearest.sort(Comparator.comparingDouble(pos -> feet.distSqr(pos)));
        nearest = nearest.subList(0, cap);

        List<Goal> goals = new ArrayList<>();
        nearest.forEach(pos -> goals.add(new GoalTwoBlocks(pos)));
        if (Baritone.settings().mineScanDroppedItems.value) {
            scanDrops().forEach(pos -> goals.add(new GoalTwoBlocks(pos)));
        }
        return new PathingCommand(new GoalComposite(goals.toArray(new Goal[0])), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    // ── RETURNING ────────────────────────────────────────────────────────────

    private PathingCommand tickReturning() {
        if (ctx.playerFeet().distSqr(depositPos) <= 16) {
            seenInOreChests = new HashSet<>();
            dumpingJunk = false;

            // Build set of item types the player is currently carrying
            Set<Item> carrying = new HashSet<>();
            for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
                if (!s.isEmpty()) carrying.add(s.getItem());
            }

            List<BlockPos> allNearby = findNearbyStorage(depositPos, 4);
            pendingChests = new ArrayList<>();
            for (BlockPos pos : allNearby) {
                if (pos.equals(junkChestPos)) continue; // handled separately in finishDeposit
                if (!chestCache.containsKey(pos)) {
                    pendingChests.add(pos); // unknown chest — visit to learn contents
                } else {
                    Set<Item> cached = chestCache.get(pos);
                    if (!cached.isEmpty() && !Collections.disjoint(cached, carrying)) {
                        pendingChests.add(pos); // cache says it has matching items
                    }
                    // else: known to have nothing useful — skip
                }
            }

            if (pendingChests.isEmpty()) {
                logDirect("No matching storage found at deposit — resuming mining");
                phase = Phase.MINING;
                return tickMining(true);
            }
            logDirect("Depositing to " + pendingChests.size() + " chest(s)");
            advanceToNextChest();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return new PathingCommand(new GoalGetToBlock(depositPos), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    // ── DEPOSIT_APPROACH ─────────────────────────────────────────────────────

    private PathingCommand tickApproach(boolean isSafeToCancel) {
        if (currentChest == null) {
            finishDeposit();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        BetterBlockPos bbp = new BetterBlockPos(currentChest);
        Optional<baritone.api.utils.Rotation> rot = RotationUtils.reachable(ctx, bbp);
        if (rot.isEmpty()) {
            return new PathingCommand(new GoalNear(currentChest, 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        if (!isSafeToCancel) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        baritone.getLookBehavior().updateTarget(rot.get(), true);
        if (ctx.isLookingAt(bbp) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            phase = Phase.DEPOSIT_OPEN;
            depositTimer = 0;
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    // ── DEPOSIT_OPEN ─────────────────────────────────────────────────────────

    private PathingCommand tickOpen(boolean isSafeToCancel) {
        depositTimer++;

        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (menu != ctx.player().inventoryMenu) {
            if (!isValidChestOpen(menu)) {
                // Server-forced open or mis-click — dismiss and keep waiting
                ctx.minecraft().setScreen(null);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            phase = Phase.DEPOSIT_TRANSFER;
            depositTimer = 0;
            chestOpenTime = System.currentTimeMillis();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Give up after ~2 seconds (40 ticks)
        if (depositTimer >= 40) {
            logDirect("Could not open chest at "
                    + currentChest.getX() + " " + currentChest.getY() + " " + currentChest.getZ() + ", skipping");
            advanceToNextChest();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Retry the right-click every 8 ticks — start at 8 so we don't double-click immediately after approach
        if (depositTimer >= 8 && depositTimer % 8 == 0 && isSafeToCancel) {
            BetterBlockPos bbp = new BetterBlockPos(currentChest);
            Optional<baritone.api.utils.Rotation> rot = RotationUtils.reachable(ctx, bbp);
            if (rot.isPresent()) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                if (ctx.isLookingAt(bbp) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                }
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * Returns true only when the open container looks like the chest we intended to open.
     * Rejects crafting tables, furnaces, etc. (fewer than 63 total slots) and containers
     * opened by the server while the player was not actually looking at currentChest.
     */
    private boolean isValidChestOpen(AbstractContainerMenu menu) {
        // Chests/barrels/shulkers have 27 or 54 storage slots + 36 player slots = 63 or 90.
        // Crafting tables (46), furnaces (3+1+36=40), etc. all fall below 63.
        if (menu.slots.size() < 63) return false;

        // The player must currently be looking at currentChest.
        // For a double chest the other half is exactly 1 block away — allow that too.
        Optional<BlockPos> aimed = ctx.getSelectedBlock();
        if (!aimed.isPresent()) return false;
        BlockPos actual = aimed.get();
        if (actual.equals(currentChest)) return true;

        // Accept the adjacent half of a double chest (distSqr == 1, same block type)
        if (actual.distSqr(currentChest) <= 1) {
            Block b = ctx.world().getBlockState(actual).getBlock();
            return b instanceof ChestBlock || b instanceof TrappedChestBlock
                    || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock;
        }
        return false;
    }

    // ── DEPOSIT_TRANSFER ─────────────────────────────────────────────────────

    private PathingCommand tickTransfer(boolean isSafeToCancel) {
        depositTimer++;
        // Wait for the configured delay after the chest opened before touching slots.
        // This gives the server time to send chest contents over the network.
        if (System.currentTimeMillis() - chestOpenTime < Baritone.settings().stripmineChestOpenDelayMs.value) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        // One transfer action per 3 ticks to avoid packet flooding
        if (depositTimer < 3) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (menu == ctx.player().inventoryMenu) {
            // Container closed unexpectedly
            advanceToNextChest();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        int totalSlots = menu.slots.size();
        int playerSlotStart = totalSlots - 36; // 27 inv + 9 hotbar
        int hotbarStart     = totalSlots - 9;

        if (dumpingJunk) {
            // Dump all block-item junk — include hotbar (junk is never a tool so safe to grab)
            for (int i = playerSlotStart; i < totalSlots; i++) {
                ItemStack s = menu.slots.get(i).getItem();
                if (!s.isEmpty() && isJunk(s)) {
                    ctx.playerController().windowClick(
                            menu.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                    depositTimer = 0;
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            // Done — close and wait for the server to confirm before advancing
            ctx.minecraft().setScreen(null);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (overflowDump) {
            Set<Item> itemsWithHome = new HashSet<>();
            for (Map.Entry<BlockPos, Set<Item>> e : chestCache.entrySet()) {
                if (!e.getKey().equals(currentChest)) itemsWithHome.addAll(e.getValue());
            }
            // Include hotbar — only deposit ORE_DROPS items (never tools)
            for (int i = playerSlotStart; i < totalSlots; i++) {
                ItemStack s = menu.slots.get(i).getItem();
                if (!s.isEmpty() && ORE_DROPS.contains(s.getItem()) && !itemsWithHome.contains(s.getItem())) {
                    ctx.playerController().windowClick(
                            menu.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                    depositTimer = 0;
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            // Done — close and wait for the server to confirm before advancing
            ctx.minecraft().setScreen(null);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Normal ore-chest pass: collect item types already in the chest
        Set<Item> chestItems = new HashSet<>();
        for (int i = 0; i < playerSlotStart; i++) {
            ItemStack s = menu.slots.get(i).getItem();
            if (!s.isEmpty()) chestItems.add(s.getItem());
        }

        // Always update cache with the live snapshot we just read
        chestCache.put(currentChest, new HashSet<>(chestItems));

        if (chestItems.isEmpty()) {
            // Empty chest — close and wait for the server to confirm before advancing
            ctx.minecraft().setScreen(null);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Remember which item types live in ore chests (used later for junk detection)
        seenInOreChests.addAll(chestItems);

        // Scan all 36 player slots; for hotbar only deposit ore items (never tools)
        for (int i = playerSlotStart; i < totalSlots; i++) {
            ItemStack s = menu.slots.get(i).getItem();
            if (s.isEmpty()) continue;
            boolean hotbar = i >= hotbarStart;
            if (hotbar && !ORE_DROPS.contains(s.getItem())) continue;
            if (chestItems.contains(s.getItem())) {
                ctx.playerController().windowClick(
                        menu.containerId, i, 0, ClickType.QUICK_MOVE, ctx.player());
                depositTimer = 0;
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }

        // Nothing left to deposit — close and wait for the server to confirm before advancing
        ctx.minecraft().setScreen(null);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private void advanceToNextChest() {
        if (pendingChests == null || pendingChests.isEmpty()) {
            finishDeposit();
            return;
        }
        currentChest = pendingChests.remove(0);
        phase = Phase.DEPOSIT_APPROACH;
        depositTimer = 0;
    }

    private void finishDeposit() {
        // Overflow pass: ores in inventory with no matching chest → dump into any ore chest
        if (!overflowDump && !dumpingJunk) {
            Set<Item> itemsWithHome = new HashSet<>();
            for (Set<Item> cached : chestCache.values()) itemsWithHome.addAll(cached);

            boolean hasOrphans = false;
            for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
                if (!s.isEmpty() && ORE_DROPS.contains(s.getItem()) && !itemsWithHome.contains(s.getItem())) {
                    hasOrphans = true;
                    break;
                }
            }
            if (hasOrphans) {
                List<BlockPos> overflow = new ArrayList<>();
                for (Map.Entry<BlockPos, Set<Item>> e : chestCache.entrySet()) {
                    if (!e.getValue().isEmpty() && !e.getKey().equals(junkChestPos)) overflow.add(e.getKey());
                }
                if (!overflow.isEmpty()) {
                    logDirect("Depositing overflow ores into available chests");
                    overflowDump = true;
                    pendingChests = overflow;
                    advanceToNextChest();
                    return;
                }
            }
        }

        if (!dumpingJunk && junkChestPos != null && hasJunk()) {
            logDirect("Ore deposit complete — dumping junk to junk chest");
            dumpingJunk = true;
            pendingChests = new ArrayList<>(Collections.singletonList(junkChestPos));
            advanceToNextChest();
            return;
        }
        currentChest = null;
        pendingChests = null;
        dumpingJunk = false;
        if (completionDeposit) {
            logDirect("Strip mine complete — deposit done");
            onLostControl();
            return;
        }
        logDirect("Deposit complete — resuming strip mine");
        phase = Phase.MINING;
    }

    // ── VEIN EXPANSION ────────────────────────────────────────────────────────

    private boolean isSafeToMine(BlockPos pos) {
        BlockState state = baritone.bsi.get0(pos);
        return state != null && !MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state);
    }

    private static boolean isOreBlock(BlockState state) {
        return state.is(BlockTags.COAL_ORES)
            || state.is(BlockTags.COPPER_ORES)
            || state.is(BlockTags.DIAMOND_ORES)
            || state.is(BlockTags.EMERALD_ORES)
            || state.is(BlockTags.GOLD_ORES)
            || state.is(BlockTags.IRON_ORES)
            || state.is(BlockTags.LAPIS_ORES)
            || state.is(BlockTags.REDSTONE_ORES);
    }

    private void expandVeins() {
        // For each cleared corridor block not yet scanned, check its 6 neighbors for ores
        List<BlockPos> toScan = new ArrayList<>();
        for (BlockPos pos : corridorSet) {
            if (!veinScanned.contains(pos)
                    && MovementHelper.canWalkThrough(ctx, new BetterBlockPos(pos))) {
                toScan.add(pos);
            }
        }
        for (BlockPos pos : toScan) {
            veinScanned.add(pos);
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (corridorSet.contains(neighbor) || targets.contains(neighbor)) continue;
                BlockState state = baritone.bsi.get0(neighbor);
                if (state == null || state.isAir()) continue;
                if (isOreBlock(state)) {
                    bfsExpandOre(neighbor, state.getBlock());
                }
            }
        }
    }

    private void bfsExpandOre(BlockPos seed, Block oreType) {
        int maxExpand = Math.max(0, Baritone.settings().veinMineMaxExpand.value);
        Set<BlockPos> found = new LinkedHashSet<>();
        found.add(seed);
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(seed);
        while (!frontier.isEmpty() && found.size() < maxExpand) {
            BlockPos pos = frontier.poll();
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (found.contains(neighbor) || corridorSet.contains(neighbor)) continue;
                BlockState state = baritone.bsi.get0(neighbor);
                if (state != null && state.getBlock() == oreType) {
                    found.add(neighbor);
                    frontier.add(neighbor);
                }
            }
        }
        for (BlockPos p : found) {
            if (!targets.contains(p) && !unsafeTargets.contains(p)) {
                if (isSafeToMine(p)) {
                    targets.add(p);
                } else {
                    unsafeTargets.add(p);
                }
            }
        }
    }

    private void scanNearbyOres() {
        CalculationContext calcCtx = new CalculationContext(baritone);
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        BetterBlockPos feet = ctx.playerFeet();
        int r = 10;
        double reach = 20.0; // same faked reach distance as MineProcess.addNearby()

        // Separate set of ore-only positions for the diagonal adjacency check —
        // targets also contains planned corridor blocks which shouldn't seed diagonals
        Set<BlockPos> oreTargets = new HashSet<>();
        for (BlockPos t : targets) {
            if (corridorSet != null && !corridorSet.contains(t)) oreTargets.add(t);
        }

        for (int x = feet.getX() - r; x <= feet.getX() + r; x++) {
            for (int y = feet.getY() - r; y <= feet.getY() + r; y++) {
                for (int z = feet.getZ() - r; z <= feet.getZ() + r; z++) {
                    BlockState state = bsi.get0(x, y, z);
                    if (state == null || !isOreBlock(state)) continue;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (targets.contains(pos)) continue;
                    if (!MineProcess.plausibleToBreak(calcCtx, pos)) continue;

                    boolean adjacent = Baritone.settings().legitMineIncludeDiagonals.value
                            && oreTargets.stream().anyMatch(t -> t.distSqr(pos) <= 2);
                    if (adjacent || RotationUtils.reachable(ctx, pos, reach).isPresent()) {
                        if (isSafeToMine(pos)) {
                            targets.add(pos);
                            oreTargets.add(pos);
                        } else {
                            unsafeTargets.add(pos);
                        }
                    }
                }
            }
        }
    }

    private List<BlockPos> scanDrops() {
        List<BlockPos> drops = new ArrayList<>();
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof ItemEntity ie && ORE_DROPS.contains(ie.getItem().getItem())) {
                drops.add(entity.blockPosition());
            }
        }
        return drops;
    }

    private boolean isJunk(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem)) return false;
        if (seenInOreChests == null || seenInOreChests.contains(stack.getItem())) return false;
        if (Baritone.settings().acceptableThrowawayItems.value.contains(stack.getItem())) {
            // Keep at least 30 throwaway blocks — only dump this slot if we'd still have ≥30 after
            return countInInventory(stack.getItem()) - stack.getCount() >= 30;
        }
        return true;
    }

    private int countInInventory(Item item) {
        int total = 0;
        for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
        }
        return total;
    }

    private boolean hasJunk() {
        for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
            if (!s.isEmpty() && isJunk(s)) return true;
        }
        return false;
    }

    private boolean inventoryTooFull() {
        int empty = 0;
        for (ItemStack s : ctx.player().getInventory().getNonEquipmentItems()) {
            if (s.isEmpty()) empty++;
        }
        return empty < Baritone.settings().stripmineInventoryFreeSlots.value;
    }

    private List<BlockPos> findNearbyStorage(BlockPos center, int radius) {
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Block b = ctx.world().getBlockState(pos).getBlock();
                    if (b instanceof ChestBlock || b instanceof TrappedChestBlock
                            || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock) {
                        found.add(pos);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(p -> center.distSqr(p)));
        return found;
    }

    private static List<BlockPos> buildTargets(BetterBlockPos origin, Direction facing,
                                               int length, int corridors) {
        int spacing = Baritone.settings().stripMineSpacing.value;
        int height  = Math.max(1, Baritone.settings().playerHeight.value);
        Direction perp = facing.getClockWise();
        List<BlockPos> out = new ArrayList<>();

        int halfC = corridors / 2;
        for (int c = -halfC; c <= halfC; c++) {
            int perpOff = c * spacing;
            for (int d = 0; d < length; d++) {
                int wx = origin.getX() + facing.getStepX() * d + perp.getStepX() * perpOff;
                int wz = origin.getZ() + facing.getStepZ() * d + perp.getStepZ() * perpOff;
                for (int h = 0; h < height; h++) {
                    out.add(new BlockPos(wx, origin.getY() + h, wz));
                }
            }
        }
        return out;
    }

    @Override
    public void onLostControl() {
        phase = Phase.IDLE;
        targets = null;
        pendingChests = null;
        currentChest = null;
        seenInOreChests = null;
        dumpingJunk = false;
        overflowDump = false;
        completionDeposit = false;
        corridorSet = null;
        unsafeTargets.clear();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        veinScanned = null;
    }

    @Override
    public String displayName0() {
        if (phase == Phase.MINING) {
            return "Strip Mine (" + (targets == null ? 0 : targets.size()) + " blocks remaining)";
        }
        if (phase == Phase.RETURNING || phase == Phase.DEPOSIT_APPROACH
                || phase == Phase.DEPOSIT_OPEN || phase == Phase.DEPOSIT_TRANSFER) {
            return "Strip Mine (depositing)";
        }
        return "Strip Mine";
    }
}

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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.ItemById;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.utils.craft.CraftPathProbe;
import baritone.utils.craft.CraftAutomationPlanner;
import baritone.utils.craft.CraftingPlanner;
import baritone.utils.craft.MinecraftRecipeCatalog;
import baritone.utils.craft.MinecraftSourceLookup;
import baritone.utils.craft.ToolRequirementHelper;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CraftCommand extends Command {

    public CraftCommand(IBaritone baritone) {
        super(baritone, "craft");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        args.requireMax(2);
        Item target = args.getDatatypeFor(ItemById.INSTANCE);
        int count = args.hasAny() ? args.getAs(Integer.class) : 1;
        if (count <= 0) {
            throw new CommandInvalidStateException("Craft count must be positive");
        }
        if (ctx.player() == null || ctx.world() == null) {
            throw new CommandInvalidStateException("No player or world is loaded");
        }
        if (!(baritone instanceof Baritone)) {
            throw new CommandInvalidStateException("Craft analysis is unavailable on this Baritone instance");
        }

        BaritoneAPI.getProvider().getWorldScanner().repack(ctx);

        LocalPlayer player = ctx.player();
        Baritone internal = (Baritone) baritone;
        CraftingPlanner planner = new CraftingPlanner(
                MinecraftRecipeCatalog.create(ctx.world()),
                new MinecraftSourceLookup(internal)
        );
        Map<Item, Integer> inventory = snapshotInventory(player);
        CraftingPlanner.PlanNode root = planner.plan(target, count, inventory);
        CraftAutomationPlanner.AutomationPlan automationPlan = internal.getCraftProcess().analyze(target, count);

        logDirect("Target: " + itemId(target) + " x" + count);
        logDirect("Dependency tree:");
        for (String line : CraftingPlanner.formatTree(root)) {
            logDirect("  " + line);
        }

        Map<Item, Integer> missingLeaves = CraftingPlanner.aggregateMissingLeaves(root);
        if (missingLeaves.isEmpty()) {
            logDirect("Missing leaves: none");
        } else {
            logDirect("Missing leaves:");
            missingLeaves.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(CraftCommand::itemId)))
                    .forEach(entry -> logDirect("  " + itemId(entry.getKey()) + " x" + entry.getValue()));
        }

        List<CraftingPlanner.PlanNode> unresolved = CraftingPlanner.unresolvedLeaves(root);
        if (!unresolved.isEmpty()) {
            logDirect("Unresolved leaves:");
            for (CraftingPlanner.PlanNode node : unresolved) {
                logDirect("  " + node.itemId() + " x" + node.missingCount() + " - " + node.unresolvedHint);
            }
        }

        List<CraftingPlanner.PlanNode> recipes = CraftingPlanner.recipeNodes(root);
        if (!recipes.isEmpty()) {
            logDirect("Recipe steps:");
            for (CraftingPlanner.PlanNode recipe : recipes) {
                String step = "  " + recipe.itemId() + ": " + recipe.recipe.station.displayName() + " x" + recipe.crafts;
                if (recipe.recipe.fuelNotPlanned) {
                    step += " (fuel not planned)";
                }
                logDirect(step);
            }
        }

        CraftingPlanner.PlanNode knownSource = CraftingPlanner.firstKnownSourceLeaf(root);
        CraftingPlanner.PlanNode nextSource = knownSource != null ? knownSource : CraftingPlanner.firstSourceLeaf(root);
        if (nextSource != null) {
            logDirect("Next step: " + describeSourceStep(nextSource));
            logDirect("  Source blocks: " + describeBlocks(nextSource.source.blocks));
            if (nextSource.source.knownInWorld && !nextSource.source.knownPositions.isEmpty()) {
                BlockPos nearest = nextSource.source.knownPositions.get(0);
                logDirect("  Nearest cached/scanned source: " + nearest.getX() + " " + nearest.getY() + " " + nearest.getZ());
                CraftPathProbe.probe((Baritone) baritone, nextSource.source).ifPresentOrElse(
                        result -> {
                            String suffix = result.reachesGoal ? "" : " (partial segment)";
                            logDirect(String.format(Locale.US, "Path probe: %.1fs (%.0f ticks), %d movements to %s%s",
                                    result.ticks / 20.0,
                                    result.ticks,
                                    result.movementCount,
                                    result.destination,
                                    suffix));
                            if (result.blocksToBreak.isEmpty()) {
                                logDirect("Blocks to break: none");
                            } else {
                                logDirect("Blocks to break:");
                                result.blocksToBreak.entrySet().stream()
                                        .sorted(Map.Entry.comparingByKey(Comparator.comparing(CraftCommand::blockId)))
                                        .forEach(entry -> logDirect("  " + blockId(entry.getKey()) + " x" + entry.getValue()));
                            }
                            logDirect("Blocks to place: " + (result.placeCount == 0 ? "none" : "throwaway x" + result.placeCount));
                            logToolSection(player, nextSource.source.blocks, result.blocksToBreak.keySet());
                        },
                        () -> {
                            logDirect("Path probe: no path segment found to the current source leaf");
                            logToolSection(player, nextSource.source.blocks, java.util.Collections.emptySet());
                        }
                );
            } else {
                logDirect("Path probe: no cached or scanned source block is currently known");
                logToolSection(player, nextSource.source.blocks, java.util.Collections.emptySet());
            }
        } else if (root.kind == CraftingPlanner.PlanNode.Kind.INVENTORY) {
            logDirect("Next step: already in inventory");
        } else if (!unresolved.isEmpty()) {
            CraftingPlanner.PlanNode blocked = unresolved.get(0);
            logDirect("Next step: unresolved " + blocked.itemId() + " x" + blocked.missingCount() + " - " + blocked.unresolvedHint);
        } else if (!recipes.isEmpty()) {
            CraftingPlanner.PlanNode firstRecipe = CraftingPlanner.firstRecipeStep(root);
            if (firstRecipe == null) {
                firstRecipe = recipes.get(0);
            }
            logDirect("Next step: complete " + firstRecipe.recipe.station.displayName() + " for " + firstRecipe.itemId());
        } else {
            logDirect("Next step: no concrete source is currently known");
        }

        logDirect("Automation preflight:");
        logDirect("  Next action: " + automationPlan.action.description);
        if (automationPlan.action.kind == CraftAutomationPlanner.Kind.DONE) {
            logDirect("  Status: target already satisfied, automation not started");
            return;
        }
        if (automationPlan.action.kind == CraftAutomationPlanner.Kind.BLOCKED) {
            logDirect("  Status: blocked, automation not started");
            return;
        }
        internal.getCraftProcess().craft(target, count);
        logDirect("  Status: automation started");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny() || args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append(args.tabCompleteDatatype(ItemById.INSTANCE))
                    .sortAlphabetically()
                    .stream();
        }
        if (args.peekDatatypeOrNull(ItemById.INSTANCE) == null) {
            return Stream.empty();
        }
        args.get();
        return args.hasExactlyOne()
                ? CustomCommandCompleter.suggest(args, "1", "16", "32", "64")
                : Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Analyze and automate a crafting path";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The craft command expands one deterministic recipe path for an item,",
                "shows what is already satisfied by your inventory, reports the current",
                "Baritone path segment to the next reachable concrete source leaf, and",
                "then starts automation for the next actionable craft step.",
                "",
                "Supported recipe types: crafting, smelting, smoking, blasting, and campfire cooking.",
                "Campfire recipes stay analysis-only in v1 and will block automation.",
                "",
                "Usage:",
                "> craft <item> - Analyze the crafting tree and automate one target item",
                "> craft <item> <count> - Analyze and automate a target count"
        );
    }

    private void logToolSection(LocalPlayer player, List<Block> sourceBlocks, Set<Block> pathBlocks) {
        Set<Block> ordered = new LinkedHashSet<>();
        ordered.addAll(sourceBlocks);
        ordered.addAll(pathBlocks);
        if (ordered.isEmpty()) {
            return;
        }
        logDirect("Required tools:");
        ordered.stream()
                .sorted(Comparator.comparing(CraftCommand::blockId))
                .forEach(block -> {
                    ToolRequirementHelper.ToolReport report = ToolRequirementHelper.describe(player, block.defaultBlockState());
                    String suffix = report.blocked ? " [blocked]" : "";
                    logDirect("  " + blockId(block) + ": current " + report.currentTool + ", required " + report.requiredTool + suffix);
                });
    }

    private static Map<Item, Integer> snapshotInventory(LocalPlayer player) {
        Map<Item, Integer> inventory = new LinkedHashMap<>();
        addStacks(inventory, player.getInventory().getNonEquipmentItems());
        addStacks(inventory, java.util.Collections.singletonList(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND)));
        return inventory;
    }

    private static void addStacks(Map<Item, Integer> inventory, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                inventory.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
    }

    private static String describeSourceStep(CraftingPlanner.PlanNode node) {
        StringBuilder builder = new StringBuilder(node.itemId())
                .append(" x")
                .append(node.missingCount())
                .append(" from ")
                .append(describeBlocks(node.source.blocks))
                .append(" (assumes 1 item per block break)");
        if (!node.source.knownInWorld) {
            builder.append(", no concrete location known yet");
        }
        return builder.toString();
    }

    private static String describeBlocks(List<Block> blocks) {
        List<String> names = blocks.stream()
                .map(CraftCommand::blockId)
                .sorted()
                .collect(Collectors.toList());
        if (names.isEmpty()) {
            return "none";
        }
        if (names.size() <= 4) {
            return String.join(", ", names);
        }
        return String.join(", ", new ArrayList<>(names.subList(0, 4))) + " +" + (names.size() - 4) + " more";
    }

    private static String itemId(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? item.toString() : id.toString();
    }

    private static String blockId(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? block.toString() : id.toString();
    }
}

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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.pathing.precompute.Ternary;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemUseAnimation;

import java.util.*;

import static baritone.api.utils.RotationUtils.DEG_TO_RAD_F;
import static baritone.pathing.movement.Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;
import static baritone.pathing.precompute.Ternary.*;

/**
 * Static helpers for cost calculation
 *
 * @author leijurv
 */
public interface MovementHelper extends ActionCosts, Helper {

    Map<Player, Boolean> WATER_SUBMERGE_LATCH = Collections.synchronizedMap(new WeakHashMap<>());
    Map<Player, Boolean> WATER_SURFACE_TRAVEL_LATCH = Collections.synchronizedMap(new WeakHashMap<>());
    Map<Player, Boolean> WATER_AIR_RECOVERY_LATCH = Collections.synchronizedMap(new WeakHashMap<>());

    static int pathingPlayerHeight() {
        return Math.max(1, Baritone.settings().playerHeight.value);
    }

    static boolean hasVerticalClearance(BlockStateInterface bsi, int x, int y, int z, int height) {
        for (int offset = 0; offset < height; offset++) {
            if (!canWalkThrough(bsi, x, y + offset, z)) {
                return false;
            }
        }
        return true;
    }

    static boolean hasVerticalClearance(CalculationContext context, int x, int y, int z, int height) {
        for (int offset = 0; offset < height; offset++) {
            if (!canWalkThrough(context, x, y + offset, z)) {
                return false;
            }
        }
        return true;
    }

    static boolean hasVerticalClearance(IPlayerContext ctx, BlockPos pos, int height) {
        for (int offset = 0; offset < height; offset++) {
            if (!canWalkThrough(ctx, new BetterBlockPos(pos.above(offset)))) {
                return false;
            }
        }
        return true;
    }

    static boolean hasPlayerClearance(CalculationContext context, int x, int y, int z) {
        return hasVerticalClearance(context, x, y, z, context.playerHeight);
    }

    static boolean hasPlayerClearance(IPlayerContext ctx, BlockPos pos) {
        return hasVerticalClearance(ctx, pos, pathingPlayerHeight());
    }

    static boolean hasFullyPassableClearance(CalculationContext context, int x, int y, int z, int height) {
        for (int offset = 0; offset < height; offset++) {
            if (!fullyPassable(context, x, y + offset, z)) {
                return false;
            }
        }
        return true;
    }

    static double getMiningDurationTicksForColumn(CalculationContext context, int x, int y, int z, int height) {
        double totalCost = 0;
        for (int offset = 0; offset < height; offset++) {
            double blockCost = getMiningDurationTicks(context, x, y + offset, z, offset == height - 1);
            if (blockCost >= COST_INF) {
                return COST_INF;
            }
            totalCost += blockCost;
        }
        return totalCost;
    }

    static boolean avoidWalkingInto(BlockStateInterface bsi, int x, int y, int z, int height, boolean allowWaterAtFeet) {
        for (int offset = 0; offset < height; offset++) {
            BlockState state = bsi.get0(x, y + offset, z);
            if (avoidWalkingInto(state) && !(allowWaterAtFeet && offset == 0 && isWater(state))) {
                return true;
            }
        }
        return false;
    }

    static boolean avoidWalkingInto(IPlayerContext ctx, BlockPos pos, int height, boolean allowWaterAtFeet) {
        return avoidWalkingInto(new BlockStateInterface(ctx), pos.getX(), pos.getY(), pos.getZ(), height, allowWaterAtFeet);
    }

    static boolean isConsumingItem(IPlayerContext ctx) {
        if (ctx.player() == null || !ctx.player().isUsingItem()) {
            return false;
        }
        ItemUseAnimation animation = ctx.player().getUseItem().getUseAnimation();
        return animation == ItemUseAnimation.EAT || animation == ItemUseAnimation.DRINK;
    }

    static boolean avoidBreaking(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return true;
        }
        Block b = state.getBlock();
        return Baritone.settings().blocksToDisallowBreaking.value.contains(b)
                || b == Blocks.ICE // ice becomes water, and water can mess up the path
                || b instanceof InfestedBlock // obvious reasons
                // call context.get directly with x,y,z. no need to make 5 new BlockPos for no reason
                || avoidAdjacentBreaking(bsi, x, y + 1, z, true)
                || avoidAdjacentBreaking(bsi, x + 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x - 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x, y, z + 1, false)
                || avoidAdjacentBreaking(bsi, x, y, z - 1, false);
    }

    static boolean avoidAdjacentBreaking(BlockStateInterface bsi, int x, int y, int z, boolean directlyAbove) {
        // returns true if you should avoid breaking a block that's adjacent to this one (e.g. lava that will start flowing if you give it a path)
        // this is only called for north, south, east, west, and up. this is NOT called for down.
        // we assume that it's ALWAYS okay to break the block thats ABOVE liquid
        BlockState state = bsi.get0(x, y, z);
        Block block = state.getBlock();
        if (!directlyAbove // it is fine to mine a block that has a falling block directly above, this (the cost of breaking the stacked fallings) is included in cost calculations
                // therefore if directlyAbove is true, we will actually ignore if this is falling
                && block instanceof FallingBlock // obviously, this check is only valid for falling blocks
                && Baritone.settings().avoidUpdatingFallingBlocks.value // and if the setting is enabled
                && FallingBlock.isFree(bsi.get0(x, y - 1, z))) { // and if it would fall (i.e. it's unsupported)
            return true; // dont break a block that is adjacent to unsupported gravel because it can cause really weird stuff
        }
        // only pure liquids for now
        // waterlogged blocks can have closed bottom sides and such
        if (block instanceof LiquidBlock) {
            if (directlyAbove || Baritone.settings().strictLiquidCheck.value) {
                return true;
            }
            int level = state.getValue(LiquidBlock.LEVEL);
            if (level == 0) {
                return true; // source blocks like to flow horizontally
            }
            // everything else will prefer flowing down
            return !(bsi.get0(x, y - 1, z).getBlock() instanceof LiquidBlock); // assume everything is in a static state
        }
        return !state.getFluidState().isEmpty();
    }

    static boolean canWalkThrough(IPlayerContext ctx, BetterBlockPos pos) {
        return canWalkThrough(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
    }

    static boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z) {
        return canWalkThrough(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canWalkThrough(CalculationContext context, int x, int y, int z, BlockState state) {
        return context.precomputedData.canWalkThrough(context.bsi, x, y, z, state);
    }

    static boolean canWalkThrough(CalculationContext context, int x, int y, int z) {
        return context.precomputedData.canWalkThrough(context.bsi, x, y, z, context.get(x, y, z));
    }

    static boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Ternary canWalkThrough = canWalkThroughBlockState(state);
        if (canWalkThrough == YES) {
            return true;
        }
        if (canWalkThrough == NO) {
            return false;
        }
        return canWalkThroughPosition(bsi, x, y, z, state);
    }

    static Ternary canWalkThroughBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return YES;
        }
        if (block instanceof BaseFireBlock || block == Blocks.COBWEB || block == Blocks.END_PORTAL || block == Blocks.COCOA || block instanceof AbstractSkullBlock || block == Blocks.BUBBLE_COLUMN || block instanceof ShulkerBoxBlock || block instanceof SlabBlock || block instanceof TrapDoorBlock || block == Blocks.HONEY_BLOCK || block == Blocks.END_ROD || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POINTED_DRIPSTONE || block instanceof AmethystClusterBlock || block instanceof AzaleaBlock) {
            return NO;
        }
        if (block == Blocks.BIG_DRIPLEAF) {
            return NO;
        }
        if (block == Blocks.POWDER_SNOW) {
            return NO;
        }
        if (Baritone.settings().blocksToAvoid.value.contains(block)) {
            return NO;
        }
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
            // TODO this assumes that all doors in all mods are openable
            if (block == Blocks.IRON_DOOR) {
                return NO;
            }
            return YES;
        }
        if (block instanceof CarpetBlock) {
            return MAYBE;
        }
        if (block instanceof SnowLayerBlock) {
            // snow layers cached as the top layer of a packed chunk have no metadata, we can't make a decision based on their depth here
            // it would otherwise make long distance pathing through snowy biomes impossible
            return MAYBE;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (fluidState.getType().getAmount(fluidState) != 8) {
                return NO;
            } else {
                return MAYBE;
            }
        }
        if (block instanceof CauldronBlock) {
            return NO;
        }
        try { // A dodgy catch-all at the end, for most blocks with default behaviour this will work, however where blocks are special this will error out, and we can handle it when we have this information
            if (state.isPathfindable(PathComputationType.LAND)) {
                return YES;
            } else {
                return NO;
            }
        } catch (Throwable exception) {
            System.out.println("The block " + state.getBlock().getName().getString() + " requires a special case due to the exception " + exception.getMessage());
            return MAYBE;
        }
    }

    static boolean canWalkThroughPosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();

        if (block instanceof CarpetBlock) {
            return canWalkOn(bsi, x, y - 1, z);
        }

        if (block instanceof SnowLayerBlock) {
            // if they're cached as a top block, we don't know their metadata
            // default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            // the check in BlockSnow.isPassable is layers < 5
            // while actually, we want < 3 because 3 or greater makes it impassable in a 2 high ceiling
            if (state.getValue(SnowLayerBlock.LAYERS) >= 3) {
                return false;
            }
            // ok, it's low enough we could walk through it, but is it supported?
            return canWalkOn(bsi, x, y - 1, z);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (isFlowing(x, y, z, state, bsi)) {
                return false;
            }
            // Everything after this point has to be a special case as it relies on the water not being flowing.
            if (Baritone.settings().assumeWalkOnWater.value) {
                return false;
            }
            BlockState up = bsi.get0(x, y + 1, z);
            if (!up.getFluidState().isEmpty() || up.getBlock() instanceof WaterlilyBlock) {
                return false;
            }
            return fluidState.getType() instanceof WaterFluid;
        }

        // every block that overrides isPassable with anything more complicated than a "return true;" or "return false;"
        // has already been accounted for above
        // therefore it's safe to not construct a blockpos from our x, y, z ints and instead just pass null
        return state.isPathfindable(PathComputationType.LAND); // workaround for future compatibility =P
    }

    static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) { // early return for most common case
            return YES;
        }
        // exceptions - blocks that are isPassable true, but we can't actually jump through
        if (block instanceof BaseFireBlock
                || block == Blocks.TRIPWIRE
                || block == Blocks.COBWEB
                || block == Blocks.VINE
                || block == Blocks.LADDER
                || block == Blocks.COCOA
                || block instanceof AzaleaBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof SnowLayerBlock
                || !state.getFluidState().isEmpty()
                || block instanceof TrapDoorBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                || block instanceof ShulkerBoxBlock) {
            return NO;
        }
        // door, fence gate, liquid, trapdoor have been accounted for, nothing else uses the world or pos parameters
        // at least in 1.12.2 vanilla, that is.....
        try { // A dodgy catch-all at the end, for most blocks with default behaviour this will work, however where blocks are special this will error out, and we can handle it when we have this information
            if (state.isPathfindable(PathComputationType.LAND)) {
                return YES;
            } else {
                return NO;
            }
        } catch (Throwable exception) {
            // see PR #1087 for why
            System.out.println("The block " + state.getBlock().getName().getString() + " requires a special case due to the exception " + exception.getMessage());
            return MAYBE;
        }
    }

    /**
     * canWalkThrough but also won't impede movement at all. so not including doors or fence gates (we'd have to right click),
     * not including water, and not including ladders or vines or cobwebs (they slow us down)
     */
    static boolean fullyPassable(CalculationContext context, int x, int y, int z) {
        return fullyPassable(context, x, y, z, context.get(x, y, z));
    }

    static boolean fullyPassable(CalculationContext context, int x, int y, int z, BlockState state) {
        return context.precomputedData.fullyPassable(context.bsi, x, y, z, state);
    }

    static boolean fullyPassable(IPlayerContext ctx, BlockPos pos) {
        BlockState state = ctx.world().getBlockState(pos);
        Ternary fullyPassable = fullyPassableBlockState(state);
        if (fullyPassable == YES) {
            return true;
        }
        if (fullyPassable == NO) {
            return false;
        }
        return fullyPassablePosition(new BlockStateInterface(ctx), pos.getX(), pos.getY(), pos.getZ(), state); // meh
    }

    static boolean fullyPassablePosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        return state.isPathfindable(PathComputationType.LAND);
    }

    static boolean isReplaceable(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        // for MovementTraverse and MovementAscend
        // block double plant defaults to true when the block doesn't match, so don't need to check that case
        // all other overrides just return true or false
        // the only case to deal with is snow
        /*
         *  public boolean isReplaceable(IBlockAccess worldIn, BlockPos pos)
         *     {
         *         return ((Integer)worldIn.getBlockState(pos).getValue(LAYERS)).intValue() == 1;
         *     }
         */
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            // early return for common cases hehe
            return true;
        }
        if (block instanceof SnowLayerBlock) {
            // as before, default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            return state.getValue(SnowLayerBlock.LAYERS) == 1;
        }
        if (block == Blocks.LARGE_FERN || block == Blocks.TALL_GRASS) {
            return true;
        }
        return state.canBeReplaced();
    }

    @Deprecated
    static boolean isReplacable(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        return isReplaceable(x, y, z, state, bsi);
    }

    static boolean isDoorPassable(IPlayerContext ctx, BlockPos doorPos, BlockPos playerPos) {
        BlockState state = BlockStateInterface.get(ctx, doorPos);
        if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            doorPos = doorPos.below();
            state = BlockStateInterface.get(ctx, doorPos);
        }

        if (playerPos.equals(doorPos)) {
            return false;
        }

        if (!(state.getBlock() instanceof DoorBlock)) {
            return true;
        }

        return isHorizontalBlockPassable(doorPos, state, playerPos, DoorBlock.OPEN);
    }

    static boolean isGatePassable(IPlayerContext ctx, BlockPos gatePos, BlockPos playerPos) {
        if (playerPos.equals(gatePos)) {
            return false;
        }

        BlockState state = BlockStateInterface.get(ctx, gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return true;
        }

        return state.getValue(FenceGateBlock.OPEN);
    }

    static boolean isHorizontalBlockPassable(BlockPos blockPos, BlockState blockState, BlockPos playerPos, BooleanProperty propertyOpen) {
        if (playerPos.equals(blockPos)) {
            return false;
        }

        Direction.Axis facing = blockState.getValue(HorizontalDirectionalBlock.FACING).getAxis();
        boolean open = blockState.getValue(propertyOpen);

        Direction.Axis playerFacing;
        if (playerPos.north().equals(blockPos) || playerPos.south().equals(blockPos)) {
            playerFacing = Direction.Axis.Z;
        } else if (playerPos.east().equals(blockPos) || playerPos.west().equals(blockPos)) {
            playerFacing = Direction.Axis.X;
        } else {
            return true;
        }

        return (facing == playerFacing) == open;
    }

    static boolean avoidWalkingInto(BlockState state) {
        Block block = state.getBlock();
        return !state.getFluidState().isEmpty()
                || (block == Blocks.MAGMA_BLOCK && !Baritone.settings().allowWalkOnMagmaBlocks.value)
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block instanceof BaseFireBlock
                || block == Blocks.END_PORTAL
                || block == Blocks.COBWEB
                || block == Blocks.BUBBLE_COLUMN;
    }

    /**
     * Can I walk on this block without anything weird happening like me falling
     * through? Includes water because we know that we automatically jump on
     * water
     * <p>
     * If changing something in this function remember to also change it in precomputed data
     *
     * @param bsi   Block state provider
     * @param x     The block's x position
     * @param y     The block's y position
     * @param z     The block's z position
     * @param state The state of the block at the specified location
     * @return Whether or not the specified block can be walked on
     */
    static boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Ternary canWalkOn = canWalkOnBlockState(state);
        if (canWalkOn == YES) {
            return true;
        }
        if (canWalkOn == NO) {
            return false;
        }
        return canWalkOnPosition(bsi, x, y, z, state);
    }

    static Ternary canWalkOnBlockState(BlockState state) {
        Block block = state.getBlock();
        if (isBlockNormalCube(state) && (block != Blocks.MAGMA_BLOCK || Baritone.settings().allowWalkOnMagmaBlocks.value) && block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) {
            return YES;
        }
        if (block instanceof AzaleaBlock) {
            return YES;
        }
        if (block == Blocks.LADDER || (block == Blocks.VINE && Baritone.settings().allowVines.value)) { // TODO reconsider this
            return YES;
        }
        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH || block == Blocks.SOUL_SAND) {
            return YES;
        }
        if (block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return YES;
        }
        if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
            return YES;
        }
        if (block instanceof StairBlock) {
            return YES;
        }
        if (isWater(state)) {
            return MAYBE;
        }
        if (MovementHelper.isLava(state) && Baritone.settings().assumeWalkOnLava.value) {
            return MAYBE;
        }
        if (block instanceof SlabBlock) {
            if (!Baritone.settings().allowWalkOnBottomSlab.value) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return YES;
                }
                return NO;
            }
            return YES;
        }
        return NO;
    }

    static boolean canWalkOnPosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (isWater(state)) {
            // since this is called literally millions of times per second, the benefit of not allocating millions of useless "pos.up()"
            // BlockPos s that we'd just garbage collect immediately is actually noticeable. I don't even think its a decrease in readability
            BlockState upState = bsi.get0(x, y + 1, z);
            Block up = upState.getBlock();
            if (up == Blocks.LILY_PAD || up instanceof CarpetBlock) {
                return true;
            }
            if (MovementHelper.isFlowing(x, y, z, state, bsi) || upState.getFluidState().getType() == Fluids.FLOWING_WATER) {
                // the only scenario in which we can walk on flowing water is if it's under still water with jesus off
                return hasSwimmableWaterColumn(bsi, x, y, z) && !Baritone.settings().assumeWalkOnWater.value;
            }
            // if assumeWalkOnWater is on, we can only walk on water if there isn't water above it
            // if assumeWalkOnWater is off, water is usable when the player can be in the water above it
            return Baritone.settings().assumeWalkOnWater.value ? !isWater(upState) : hasSwimmableWaterColumn(bsi, x, y, z);
        }

        if (MovementHelper.isLava(state) && !MovementHelper.isFlowing(x, y, z, state, bsi) && Baritone.settings().assumeWalkOnLava.value) { // if we get here it means that assumeWalkOnLava must be true, so put it last
            return true;
        }

        return false; // If we don't recognise it then we want to just return false to be safe.
    }

    static boolean hasSwimmableWaterColumn(BlockStateInterface bsi, int x, int y, int z) {
        return isWater(bsi.get0(x, y + 1, z));
    }

    static boolean canWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        return context.precomputedData.canWalkOn(context.bsi, x, y, z, state);
    }

    static boolean canWalkOn(CalculationContext context, int x, int y, int z) {
        return canWalkOn(context, x, y, z, context.get(x, y, z));
    }

    static boolean canWalkOn(IPlayerContext ctx, BetterBlockPos pos, BlockState state) {
        return canWalkOn(new BlockStateInterface(ctx), pos.x, pos.y, pos.z, state);
    }

    static boolean canWalkOn(IPlayerContext ctx, BlockPos pos) {
        return canWalkOn(new BlockStateInterface(ctx), pos.getX(), pos.getY(), pos.getZ());
    }

    static boolean canWalkOn(IPlayerContext ctx, BetterBlockPos pos) {
        return canWalkOn(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
    }

    static boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z) {
        return canWalkOn(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canUseFrostWalker(CalculationContext context, BlockState state) {
        return context.frostWalker != 0
                && state == FrostedIceBlock.meltsInto()
                && ((Integer) state.getValue(LiquidBlock.LEVEL)) == 0;
    }

    static boolean canUseFrostWalker(IPlayerContext ctx, BlockPos pos) {
        BlockState state = BlockStateInterface.get(ctx, pos);
        return EnchantmentHelper.getEnchantmentLevel(ctx.player().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FROST_WALKER), ctx.player()) > 0
                && state == FrostedIceBlock.meltsInto()
                && ((Integer) state.getValue(LiquidBlock.LEVEL)) == 0;
    }

    /**
     * If movements make us stand/walk on this block, will it have a top to walk on?
     */
    static boolean mustBeSolidToWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.LADDER || block == Blocks.VINE) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            // used for frostwalker so only includes blocks where we are still on ground when leaving them to any side
            if (block instanceof SlabBlock) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return true;
                }
            } else if (block instanceof StairBlock) {
                if (state.getValue(StairBlock.HALF) == Half.TOP) {
                    return true;
                }
                StairsShape shape = state.getValue(StairBlock.SHAPE);
                if (shape == StairsShape.INNER_LEFT || shape == StairsShape.INNER_RIGHT) {
                    return true;
                }
            } else if (block instanceof TrapDoorBlock) {
                if (!state.getValue(TrapDoorBlock.OPEN) && state.getValue(TrapDoorBlock.HALF) == Half.TOP) {
                    return true;
                }
            } else if (block == Blocks.SCAFFOLDING) {
                return true;
            } else if (block instanceof LeavesBlock) {
                return true;
            }
            if (context.assumeWalkOnWater) {
                return false;
            }
            Block blockAbove = context.getBlock(x, y + 1, z);
            if (blockAbove instanceof LiquidBlock) {
                return false;
            }
        }
        return true;
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, int x, int y, int z) {
        return canPlaceAgainst(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, BlockPos pos) {
        return canPlaceAgainst(bsi, pos.getX(), pos.getY(), pos.getZ());
    }

    static boolean canPlaceAgainst(IPlayerContext ctx, BlockPos pos) {
        return canPlaceAgainst(new BlockStateInterface(ctx), pos);
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return false;
        }
        // can we look at the center of a side face of this block and likely be able to place?
        // (thats how this check is used)
        // therefore dont include weird things that we technically could place against (like carpet) but practically can't
        return isBlockNormalCube(state) || state.getBlock() == Blocks.GLASS || state.getBlock() instanceof StainedGlassBlock;
    }

    static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, boolean includeFalling) {
        return getMiningDurationTicks(context, x, y, z, context.get(x, y, z), includeFalling);
    }

    static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, BlockState state, boolean includeFalling) {
        Block block = state.getBlock();
        if (!canWalkThrough(context, x, y, z, state)) {
            if (!state.getFluidState().isEmpty()) {
                return COST_INF;
            }
            double mult = context.breakCostMultiplierAt(x, y, z, state);
            if (mult >= COST_INF) {
                return COST_INF;
            }
            if (avoidBreaking(context.bsi, x, y, z, state)) {
                return COST_INF;
            }
            double strVsBlock = context.toolSet.getStrVsBlock(state);
            if (strVsBlock <= 0) {
                return COST_INF;
            }
            double result = 1 / strVsBlock;
            result += context.breakBlockAdditionalCost;
            result *= mult;
            if (includeFalling) {
                BlockState above = context.get(x, y + 1, z);
                if (above.getBlock() instanceof FallingBlock) {
                    result += getMiningDurationTicks(context, x, y + 1, z, above, true);
                }
            }
            return result;
        }
        return 0; // we won't actually mine it, so don't check fallings above
    }

    static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /**
     * AutoTool for a specific block
     *
     * @param ctx The player context
     * @param b   the blockstate to mine
     */
    static void switchToBestToolFor(IPlayerContext ctx, BlockState b) {
        boolean silkTouch = BaritoneAPI.getSettings().preferSilkTouch.value
                || Baritone.settings().silkTouchBlocks.value.contains(b.getBlock());
        switchToBestToolFor(ctx, b, new ToolSet(ctx.player()), silkTouch);
    }

    /**
     * AutoTool for a specific block with precomputed ToolSet data
     *
     * @param ctx The player context
     * @param b   the blockstate to mine
     * @param ts  previously calculated ToolSet
     */
    static void switchToBestToolFor(IPlayerContext ctx, BlockState b, ToolSet ts, boolean preferSilkTouch) {
        IBaritone baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(ctx.player());
        if (baritone instanceof Baritone && ((Baritone) baritone).getInventoryBehavior().isAutoEating()) {
            return;
        }
        if (Baritone.settings().autoTool.value && !Baritone.settings().assumeExternalAutoTool.value) {
            ctx.player().getInventory().setSelectedSlot(ts.getBestSlot(b.getBlock(), preferSilkTouch));
        }
    }

    static void moveTowards(IPlayerContext ctx, MovementState state, BlockPos pos) {
        state.setTarget(new MovementTarget(
                RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        VecUtils.getBlockPosCenter(pos),
                        ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()),
                false
        )).setInput(Input.MOVE_FORWARD, true);
    }

    static boolean moveBackIfOvershot(IPlayerContext ctx, MovementState state, BlockPos src, BlockPos dest, double minDistance) {
        Vec3 destCenter = VecUtils.getBlockPosCenter(dest);
        Vec3 playerPos = ctx.player().position();
        double offsetX = playerPos.x - destCenter.x;
        double offsetZ = playerPos.z - destCenter.z;
        if (offsetX * offsetX + offsetZ * offsetZ < minDistance * minDistance) {
            return false;
        }

        int stepX = Integer.compare(dest.getX(), src.getX());
        int stepZ = Integer.compare(dest.getZ(), src.getZ());
        if (stepX == 0 && stepZ == 0) {
            return false;
        }
        if (offsetX * stepX + offsetZ * stepZ <= 0.0D) {
            return false;
        }

        Vec3 lookForward = new Vec3(destCenter.x + stepX, destCenter.y, destCenter.z + stepZ);
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), lookForward, ctx.playerRotations())
                .withPitch(ctx.playerRotations().getPitch());
        state.setTarget(new MovementTarget(rotation, false));
        state.setInput(Input.MOVE_FORWARD, false);
        state.setInput(Input.MOVE_BACK, true);
        state.setInput(Input.SPRINT, false);
        return true;
    }

    static boolean shouldSwimUnderwater(IPlayerContext ctx, BlockPos dest) {
        if (Baritone.settings().assumeWalkOnWater.value || ctx.player() == null || !ctx.player().isInWater()) {
            return false;
        }
        BetterBlockPos feet = ctx.playerFeet();
        BlockPos currentHead = feet.above(pathingPlayerHeight() - 1);
        BlockPos destHead = dest.above(pathingPlayerHeight() - 1);
        return isWater(ctx, feet)
                && isWater(ctx, dest)
                && (isWater(ctx, currentHead)
                || isWater(ctx, destHead)
                || hasSwimmableDepthBelow(ctx, feet)
                || hasSwimmableDepthBelow(ctx, dest));
    }

    static boolean hasSwimmableDepthBelow(IPlayerContext ctx, BlockPos feetPos) {
        for (int offset = 1; offset <= pathingPlayerHeight() + 1; offset++) {
            if (!isWater(ctx, feetPos.below(offset))) {
                return false;
            }
        }
        return true;
    }

    static boolean hasSwimmableDepthBelow(CalculationContext context, int x, int y, int z) {
        for (int offset = 1; offset <= context.playerHeight + 1; offset++) {
            if (!isWater(context.get(x, y - offset, z))) {
                return false;
            }
        }
        return true;
    }

    static boolean prefersLevelSwimming(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        if (context.assumeWalkOnWater) {
            return false;
        }
        if (!isWater(context.get(x, y, z)) || !isWater(context.get(destX, y, destZ))) {
            return false;
        }
        if (isBelowPreferredSwimY(context, x, y, z) || isBelowPreferredSwimY(context, destX, y, destZ)) {
            return false;
        }
        return hasSwimmableDepthBelow(context, x, y, z)
                && hasSwimmableDepthBelow(context, destX, y, destZ)
                && hasPlayerClearance(context, destX, y, destZ);
    }

    static boolean isSwimmableMovementColumn(CalculationContext context, int x, int y, int z) {
        if (!isWater(context.get(x, y, z))) {
            return false;
        }
        for (int offset = 1; offset < context.playerHeight; offset++) {
            BlockState state = context.get(x, y + offset, z);
            if (!canWalkThrough(context, x, y + offset, z, state)) {
                return false;
            }
            if (avoidWalkingInto(state) && !isWater(state)) {
                return false;
            }
        }
        return true;
    }

    static int preferredSwimFeetY(CalculationContext context, int x, int y, int z) {
        int topOccupiedY = y + context.playerHeight - 1;
        if (!isWater(context.get(x, topOccupiedY, z))) {
            return Integer.MIN_VALUE;
        }
        int topWaterY = topOccupiedY;
        while (topWaterY + 1 < context.world.getMaxY() && isWater(context.get(x, topWaterY + 1, z))) {
            topWaterY++;
        }
        return topWaterY - (context.playerHeight - 1);
    }

    static boolean isBelowPreferredSwimY(CalculationContext context, int x, int y, int z) {
        return y < preferredSwimFeetY(context, x, y, z);
    }

    static double underwaterDepthPenalty(CalculationContext context, int x, int y, int z) {
        if (context.assumeWalkOnWater) {
            return 0;
        }
        int extraDepth = preferredSwimFeetY(context, x, y, z) - y;
        if (extraDepth <= 0) {
            return 0;
        }
        return extraDepth * WALK_ONE_BLOCK_COST;
    }

    static boolean canSurfaceSwim(IPlayerContext ctx, BlockPos feetPos) {
        int scanY = feetPos.getY() + pathingPlayerHeight() - 1;
        if (!isWater(ctx, new BlockPos(feetPos.getX(), scanY, feetPos.getZ()))) {
            return true;
        }
        while (scanY + 1 < ctx.world().getMaxY()
                && isWater(ctx, new BlockPos(feetPos.getX(), scanY + 1, feetPos.getZ()))) {
            scanY++;
        }
        if (scanY + 1 >= ctx.world().getMaxY()) {
            return false;
        }
        return canWalkThrough(ctx, new BetterBlockPos(feetPos.getX(), scanY + 1, feetPos.getZ()));
    }

    static Double headOffsetFromWaterSurface(IPlayerContext ctx) {
        if (ctx.player() == null || ctx.world() == null) {
            return null;
        }
        BlockPos feet = ctx.playerFeet();
        int x = feet.getX();
        int z = feet.getZ();
        int minY = ctx.world().getMinY();
        int maxY = ctx.world().getMaxY();
        double trackedY = ctx.player().isSwimming()
                ? ctx.player().position().y
                : ctx.player().position().y + pathingPlayerHeight();
        int scanY = Math.max(minY, Math.min(maxY - 1, Mth.floor(trackedY)));
        while (scanY >= minY && !isWater(ctx, new BlockPos(x, scanY, z))) {
            scanY--;
        }
        if (scanY < minY) {
            return null;
        }
        int topWaterY = scanY;
        while (topWaterY + 1 < maxY && isWater(ctx, new BlockPos(x, topWaterY + 1, z))) {
            topWaterY++;
        }
        BlockPos topWater = new BlockPos(x, topWaterY, z);
        double surfaceY = topWaterY + ctx.world().getFluidState(topWater).getHeight(ctx.world(), topWater);
        return surfaceY - trackedY;
    }

    static boolean isHeadUnderWaterSurface(IPlayerContext ctx) {
        Double headOffset = headOffsetFromWaterSurface(ctx);
        return headOffset != null && headOffset > 0.0D;
    }

    static boolean isWaterSubmergeLatched(IPlayerContext ctx) {
        return ctx.player() != null && WATER_SUBMERGE_LATCH.containsKey(ctx.player());
    }

    static void setWaterSubmergeLatched(IPlayerContext ctx, boolean latched) {
        if (ctx.player() == null) {
            return;
        }
        if (latched) {
            WATER_SUBMERGE_LATCH.put(ctx.player(), Boolean.TRUE);
        } else {
            WATER_SUBMERGE_LATCH.remove(ctx.player());
        }
    }

    static boolean isWaterSurfaceTravelLatched(IPlayerContext ctx) {
        return ctx.player() != null && WATER_SURFACE_TRAVEL_LATCH.containsKey(ctx.player());
    }

    static void setWaterSurfaceTravelLatched(IPlayerContext ctx, boolean latched) {
        if (ctx.player() == null) {
            return;
        }
        if (latched) {
            WATER_SURFACE_TRAVEL_LATCH.put(ctx.player(), Boolean.TRUE);
        } else {
            WATER_SURFACE_TRAVEL_LATCH.remove(ctx.player());
        }
    }

    static boolean isWaterAirRecoveryLatched(IPlayerContext ctx) {
        return ctx.player() != null && WATER_AIR_RECOVERY_LATCH.containsKey(ctx.player());
    }

    static void setWaterAirRecoveryLatched(IPlayerContext ctx, boolean latched) {
        if (ctx.player() == null) {
            return;
        }
        if (latched) {
            WATER_AIR_RECOVERY_LATCH.put(ctx.player(), Boolean.TRUE);
        } else {
            WATER_AIR_RECOVERY_LATCH.remove(ctx.player());
        }
    }

    static boolean shouldRecoverWaterAir(IPlayerContext ctx) {
        if (ctx.player() == null || !ctx.player().isInWater()) {
            return false;
        }
        int maxAir = ctx.player().getMaxAirSupply();
        if (maxAir <= 0) {
            return false;
        }
        int currentAir = ctx.player().getAirSupply();
        if (isWaterAirRecoveryLatched(ctx)) {
            return currentAir < maxAir;
        }
        int twoBubbleThreshold = Math.max(1, (maxAir * 2) / 10);
        return currentAir <= twoBubbleThreshold;
    }

    static boolean isUnderwaterDescendEdgeStuck(IPlayerContext ctx) {
        if (ctx.player() == null) {
            return false;
        }
        Vec3 delta = ctx.player().getDeltaMovement();
        if (delta.x * delta.x + delta.z * delta.z > 0.0025D) {
            return false;
        }
        BlockPos feet = ctx.playerFeet();
        BlockPos[] supportChecks = new BlockPos[] {
                feet.below(),
                feet.north().below(),
                feet.south().below(),
                feet.east().below(),
                feet.west().below()
        };
        for (BlockPos supportPos : supportChecks) {
            if (canWalkOn(ctx, supportPos) && !isLiquid(ctx, supportPos)) {
                return true;
            }
        }
        return false;
    }

    static boolean applyUnderwaterSwimmingInputs(IPlayerContext ctx, MovementState state, BlockPos dest) {
        // Air recovery takes priority over everything: once the air threshold is
        // hit, stay latched at the surface until air is completely full again,
        // even if the swim gate below would flip false for a tick.
        boolean recoverAir = shouldRecoverWaterAir(ctx);
        setWaterAirRecoveryLatched(ctx, recoverAir);
        if (recoverAir) {
            if (ctx.player() != null && ctx.player().isInWater()) {
                state.setInput(Input.SNEAK, false);
                state.setInput(Input.JUMP, true);
                boolean wantsSprintSwimming = Baritone.settings().sprintInWater.value
                        && Baritone.settings().allowSprint.value
                        && ctx.player().getFoodData().getFoodLevel() > 6;
                state.setInput(Input.SPRINT, wantsSprintSwimming);
            }
            return true;
        }
        if (!shouldSwimUnderwater(ctx, dest)) {
            setWaterSubmergeLatched(ctx, false);
            setWaterSurfaceTravelLatched(ctx, false);
            return false;
        }
        boolean headUnderSurface = isHeadUnderWaterSurface(ctx);
        boolean swimming = ctx.player().isSwimming();
        boolean wantsSprintSwimming = Baritone.settings().sprintInWater.value
                && Baritone.settings().allowSprint.value
                && ctx.player().getFoodData().getFoodLevel() > 6;
        boolean submergeLatched = isWaterSubmergeLatched(ctx);
        boolean sprintSwimming = wantsSprintSwimming && swimming;
        boolean tryStartSwimming = wantsSprintSwimming && headUnderSurface && !swimming;
        if (sprintSwimming) {
            setWaterSubmergeLatched(ctx, false);
            state.setInput(Input.SPRINT, true);
        } else {
            setWaterSurfaceTravelLatched(ctx, false);
        }
        if (wantsSprintSwimming && (submergeLatched || !headUnderSurface || tryStartSwimming)) {
            setWaterSubmergeLatched(ctx, true);
        } else {
            setWaterSubmergeLatched(ctx, false);
        }
        boolean standingOnSolidSupport = canWalkOn(ctx, ctx.playerFeet().below())
                && !isLiquid(ctx, ctx.playerFeet().below());
        boolean stuckOnDescendEdge = isUnderwaterDescendEdgeStuck(ctx);
        double playerY = ctx.player().position().y;
        double verticalError = playerY - dest.getY();
        final double swimDeadband = Math.max(0.0D, Baritone.settings().swimDeadband.value);
        if (recoverAir) {
            setWaterSubmergeLatched(ctx, false);
            state.setInput(Input.SNEAK, false);
            state.setInput(Input.JUMP, true);
            if (wantsSprintSwimming) {
                state.setInput(Input.SPRINT, true);
            } else {
                state.setInput(Input.SPRINT, false);
            }
            return true;
        }
        if (isWaterSubmergeLatched(ctx) && !headUnderSurface) {
            state.setInput(Input.SPRINT, false);
            if (standingOnSolidSupport || stuckOnDescendEdge) {
                state.setInput(Input.SNEAK, false);
                state.setInput(Input.JUMP, false);
            } else {
                state.setInput(Input.SNEAK, true);
                state.setInput(Input.JUMP, false);
            }
            return true;
        }
        if (tryStartSwimming) {
            state.setInput(Input.SPRINT, true);
            state.setInput(Input.SNEAK, false);
            state.setInput(Input.JUMP, false);
            return true;
        }
        if (!headUnderSurface && !standingOnSolidSupport) {
            state.setInput(Input.SNEAK, true);
            state.setInput(Input.JUMP, false);
            return true;
        }
        if (verticalError < -swimDeadband) {
            state.setInput(Input.JUMP, true);
            state.setInput(Input.SNEAK, false);
            return true;
        }
        if (verticalError > swimDeadband && !standingOnSolidSupport && !stuckOnDescendEdge) {
            state.setInput(Input.SNEAK, true);
            state.setInput(Input.JUMP, false);
            return true;
        }
        state.setInput(Input.JUMP, false);
        state.setInput(Input.SNEAK, false);
        return true;
    }

    /**
     * The player's jump velocity (blocks/tick) including jump boost, replicating
     * LivingEntity#getJumpPower: JUMP_STRENGTH * blockJumpFactor + 0.1 * (amp + 1).
     */
    static double playerJumpPower(IPlayerContext ctx) {
        if (ctx.player() == null) {
            return 0.0D;
        }
        double power = ctx.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH)
                * ctx.player().getBlockStateOn().getBlock().getJumpFactor();
        if (ctx.player().hasEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST)) {
            power += 0.1D * (ctx.player().getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST).getAmplifier() + 1);
        }
        return power;
    }

    /**
     * How far (in blocks) a full jump would carry the player horizontally at their
     * current velocity, accounting for jump boost and speed effects (the latter are
     * already reflected in the current horizontal velocity). Includes per-tick air
     * drag (0.91) on the horizontal component.
     */
    static double predictedJumpDistance(IPlayerContext ctx) {
        if (ctx.player() == null) {
            return 0.0D;
        }
        double speed = ctx.player().getDeltaMovement().horizontalDistance();
        double jumpPower = playerJumpPower(ctx);
        if (jumpPower <= 0.0D) {
            return 0.0D;
        }
        double airTime = 2.0D * jumpPower / 0.08D; // ticks up and back down to the same height
        double dragSum = (1.0D - Math.pow(0.91D, airTime)) / 0.09D;
        return speed * dragSum;
    }

    /**
     * Max sprint speed in blocks/tick including speed effects (attribute 0.1 base
     * -> 0.13 sprinting -> 2.16 block conversion).
     */
    static double maxSprintSpeed(IPlayerContext ctx) {
        if (ctx.player() == null) {
            return 0.2806D;
        }
        return 2.16D * ctx.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
    }

    /**
     * Forward walk-ahead takeoff solver for 1-block ascends.
     * <p>
     * Simulates the jump that would be launched from the player's CURRENT position
     * and velocity, but with the horizontal direction taken from the movement's
     * dest (not the player's momentary movement vector, which can point elsewhere),
     * the current ground speed accelerated toward sprint max (walk-ahead), and the
     * vanilla sprint-jump boost (x1.3) applied on the launch tick. The trajectory
     * is then stepped tick by tick:
     * <ul>
     * <li>any block in the player's body band (feet+1, feet+2) that is not
     *     passable = head bonk / wall hit -> the takeoff is invalid</li>
     * <li>when the horizontal travel crosses the dest's X/Z, the feet height must
     *     be at or above the dest's top, otherwise the jump is too close / too far
     *     and the player collides with the step face</li>
     * </ul>
     * Returns true only when a jump launched right now would cleanly carry the
     * player onto the ascend dest. Re-run every tick; as the player walks closer
     * the crossing happens at a different point of the arc, so the first tick that
     * returns true is the optimal takeoff.
     */
    static boolean jumpClearsAscend(IPlayerContext ctx, BetterBlockPos src, BetterBlockPos dest) {
        if (ctx.player() == null) {
            return false;
        }
        Vec3 pos = ctx.player().position();
        double dx = (dest.getX() + 0.5D) - pos.x;
        double dz = (dest.getZ() + 0.5D) - pos.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.2D) {
            return true; // basically already there
        }
        double dirX = dx / flat;
        double dirZ = dz / flat;
        Vec3 vel = ctx.player().getDeltaMovement();
        // forward component of current velocity along the dest direction, accelerated
        double speed = Math.abs(dirX * vel.x + dirZ * vel.z);
        double maxSpeed = maxSprintSpeed(ctx);
        if (Baritone.settings().allowSprint.value) {
            speed = Math.min(speed + 0.086D, maxSpeed); // ground accel walk-ahead
        } else {
            speed = Math.min(speed, 0.215D); // walking
        }
        double jumpPower = playerJumpPower(ctx);
        if (jumpPower <= 0.0D) {
            return false;
        }
        // sprint-jump launch: x1.3 horizontal boost, jump power vertically
        double vx = dirX * speed * 1.3D;
        double vz = dirZ * speed * 1.3D;
        double vy = jumpPower;
        double px = pos.x, py = pos.y, pz = pos.z;
        double traveled = 0.0D;
        for (int i = 0; i < 30; i++) {
            px += vx;
            py += vy;
            pz += vz;
            traveled += Math.sqrt(vx * vx + vz * vz);
            vy -= 0.08D;
            vx *= 0.91D;
            vz *= 0.91D;
            if (traveled < 0.15D) {
                continue; // ignore the first sliver of movement, still on takeoff block
            }
            int bx = Mth.floor(px);
            int by = Mth.floor(py);
            int bz = Mth.floor(pz);
            // body band check: anything solid at torso/head height = bonk
            BetterBlockPos bandPos = new BetterBlockPos(bx, by + 1, bz);
            if (!canWalkThrough(ctx, bandPos) || !canWalkThrough(ctx, bandPos.above())) {
                return false;
            }
            if (traveled >= flat - 0.25D && traveled <= flat + 0.75D) {
                // horizontally at the dest - feet must clear the step top
                if (py >= dest.getY() + 0.1D) {
                    return true;
                }
            }
            if (traveled > flat + 0.75D) {
                // passed the dest while too low - this takeoff is too close; a jump
                // from further back would have crossed higher up the arc
                return false;
            }
        }
        return false; // could not reach dest at all (too far) - keep walking
    }

    static void moveTowardsWithoutRotation(IPlayerContext ctx, MovementState state, float idealYaw) {
        MovementOption.getOptions(
                Mth.sin(ctx.playerRotations().getYaw() * DEG_TO_RAD_F),
                Mth.cos(ctx.playerRotations().getYaw() * DEG_TO_RAD_F),
                Baritone.settings().allowSprint.value
        ).min(Comparator.comparing(option -> option.distanceToSq(
                Mth.sin(idealYaw * DEG_TO_RAD_F),
                Mth.cos(idealYaw * DEG_TO_RAD_F)
        ))).ifPresent(selection -> selection.setInputs(state));
    }

    static void moveTowardsWithoutRotation(IPlayerContext ctx, MovementState state, BlockPos dest) {
        float idealYaw = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                VecUtils.getBlockPosCenter(dest),
                ctx.playerRotations()
        ).getYaw();
        moveTowardsWithoutRotation(ctx, state, idealYaw);
    }

    static void moveTowardsWithSlightRotation(IPlayerContext ctx, MovementState state, BlockPos dest) {
        float idealYaw = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                VecUtils.getBlockPosCenter(dest),
                ctx.playerRotations()
        ).getYaw();
        float distance = Rotation.yawDistanceFromOffset(ctx.playerRotations().getYaw(), idealYaw) % 45f;
        float newYaw = distance > 0f ?
                distance > 22.5f ? distance - 45f : distance :
                distance < -22.5f ? distance + 45f : distance;
        state.setTarget(new MovementTarget(new Rotation(
                ctx.playerRotations().getYaw() - newYaw,
                ctx.playerRotations().getPitch()
        ), true));
        moveTowardsWithoutRotation(ctx, state, idealYaw);
    }

    /**
     * Returns whether or not the specified block is
     * water, regardless of whether or not it is flowing.
     *
     * @param state The block state
     * @return Whether or not the block is water
     */
    static boolean isWater(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.WATER || f == Fluids.FLOWING_WATER;
    }

    /**
     * Returns whether or not the block at the specified pos is
     * water, regardless of whether or not it is flowing.
     *
     * @param ctx The player context
     * @param bp  The block pos
     * @return Whether or not the block is water
     */
    static boolean isWater(IPlayerContext ctx, BlockPos bp) {
        return isWater(BlockStateInterface.get(ctx, bp));
    }

    static boolean isLava(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }

    /**
     * Returns whether or not the specified pos has a liquid
     *
     * @param ctx The player context
     * @param p   The pos
     * @return Whether or not the block is a liquid
     */
    static boolean isLiquid(IPlayerContext ctx, BlockPos p) {
        return isLiquid(BlockStateInterface.get(ctx, p));
    }

    static boolean isLiquid(BlockState blockState) {
        return !blockState.getFluidState().isEmpty();
    }

    static boolean possiblyFlowing(BlockState state) {
        FluidState fluidState = state.getFluidState();
        return fluidState.getType() instanceof FlowingFluid
                && fluidState.getType().getAmount(fluidState) != 8;
    }

    static boolean isFlowing(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        FluidState fluidState = state.getFluidState();
        if (!(fluidState.getType() instanceof FlowingFluid)) {
            return false;
        }
        if (fluidState.getType().getAmount(fluidState) != 8) {
            return true;
        }
        return possiblyFlowing(bsi.get0(x + 1, y, z))
                || possiblyFlowing(bsi.get0(x - 1, y, z))
                || possiblyFlowing(bsi.get0(x, y, z + 1))
                || possiblyFlowing(bsi.get0(x, y, z - 1));
    }

    static boolean isBlockNormalCube(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BambooStalkBlock
                || block instanceof MovingPistonBlock
                || block instanceof ScaffoldingBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof AmethystClusterBlock) {
            return false;
        }
        try {
            return Block.isShapeFullBlock(state.getCollisionShape(null, null));
        } catch (Exception ignored) {
            // if we can't get the collision shape, assume it's bad and add to blocksToAvoid
        }
        return false;
    }

    static PlaceResult attemptToPlaceABlock(MovementState state, IBaritone baritone, BlockPos placeAt, boolean preferDown, boolean wouldSneak) {
        IPlayerContext ctx = baritone.getPlayerContext();
        Optional<Rotation> direct = RotationUtils.reachable(ctx, placeAt, wouldSneak); // we assume that if there is a block there, it must be replacable
        boolean found = false;
        if (direct.isPresent()) {
            state.setTarget(new MovementTarget(direct.get(), true));
            found = true;
        }
        for (int i = 0; i < 5; i++) {
            BlockPos against1 = placeAt.relative(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
            if (MovementHelper.canPlaceAgainst(ctx, against1)) {
                if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(false, placeAt.getX(), placeAt.getY(), placeAt.getZ())) { // get ready to place a throwaway block
                    Helper.HELPER.logDebug("bb pls get me some blocks. dirt, netherrack, cobble");
                    state.setStatus(MovementStatus.UNREACHABLE);
                    return PlaceResult.NO_OPTION;
                }
                double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
                double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
                double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
                Rotation place = RotationUtils.calcRotationFromVec3d(wouldSneak ? RayTraceUtils.inferSneakingEyePosition(ctx.player()) : ctx.playerHead(), new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
                Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(place);
                HitResult res = RayTraceUtils.rayTraceTowards(ctx.player(), actual, ctx.playerController().getBlockReachDistance(), wouldSneak);
                if (res != null && res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1) && ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(placeAt)) {
                    state.setTarget(new MovementTarget(place, true));
                    found = true;

                    if (!preferDown) {
                        // if preferDown is true, we want the last option
                        // if preferDown is false, we want the first
                        break;
                    }
                }
            }
        }
        if (ctx.getSelectedBlock().isPresent()) {
            BlockPos selectedBlock = ctx.getSelectedBlock().get();
            Direction side = ((BlockHitResult) ctx.objectMouseOver()).getDirection();
            // only way for selectedBlock.equals(placeAt) to be true is if it's replaceable
            if (selectedBlock.equals(placeAt) || (MovementHelper.canPlaceAgainst(ctx, selectedBlock) && selectedBlock.relative(side).equals(placeAt))) {
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                ((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ());
                return PlaceResult.READY_TO_PLACE;
            }
        }
        if (found) {
            if (wouldSneak) {
                state.setInput(Input.SNEAK, true);
            }
            ((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ());
            return PlaceResult.ATTEMPTING;
        }
        return PlaceResult.NO_OPTION;
    }

    enum PlaceResult {
        READY_TO_PLACE, ATTEMPTING, NO_OPTION;
    }

    static boolean isTransparent(Block b) {

        return b instanceof AirBlock ||
                b == Blocks.LAVA ||
                b == Blocks.WATER;
    }

    static List<BetterBlockPos> steppingOnBlocks(IPlayerContext ctx) {
        List<BetterBlockPos> blocks = new ArrayList<>();
        for (byte x = -1; x <= 1; x++) {
            for (byte z = -1; z <= 1; z++) {
                if (ctx.player().getBoundingBox().intersects(Vec3.atLowerCornerOf(ctx.player().blockPosition()).add(x, 0, z), Vec3.atLowerCornerOf(ctx.player().blockPosition()).add(x + 1, 1, z + 1))) {
                    blocks.add(new BetterBlockPos(ctx.player().getBlockX() + x, ctx.player().getBlockY() - 1, ctx.player().getBlockZ() + z));
                }
            }
        }
        return blocks;
    }
}

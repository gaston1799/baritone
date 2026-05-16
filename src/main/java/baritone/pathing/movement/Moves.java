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

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.movements.*;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;

/**
 * An enum of all possible movements attached to all possible directions they could be taken in
 *
 * @author leijurv
 */
public enum Moves {
    DOWNWARD(0, -1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementDownward(context.getBaritone(), src, src.below());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementDownward.cost(context, x, y, z);
        }
    },

    PILLAR(0, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementPillar(context.getBaritone(), src, src.above());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementPillar.cost(context, x, y, z);
        }
    },

    TRAVERSE_NORTH(0, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.north());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x, z - 1);
        }
    },

    TRAVERSE_SOUTH(0, 0, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.south());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x, z + 1);
        }
    },

    TRAVERSE_EAST(+1, 0, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.east());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x + 1, z);
        }
    },

    TRAVERSE_WEST(-1, 0, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.west());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x - 1, z);
        }
    },

    ASCEND_NORTH(0, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x, src.y + 1, src.z - 1));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x, z - 1);
        }
    },

    ASCEND_SOUTH(0, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x, src.y + 1, src.z + 1));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x, z + 1);
        }
    },

    ASCEND_EAST(+1, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x + 1, src.y + 1, src.z));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x + 1, z);
        }
    },

    ASCEND_WEST(-1, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x - 1, src.y + 1, src.z));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x - 1, z);
        }
    },

    DESCEND_EAST(+1, -1, 0, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x + 1, z, result);
        }
    },

    DESCEND_WEST(-1, -1, 0, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x - 1, z, result);
        }
    },

    DESCEND_NORTH(0, -1, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x, z - 1, result);
        }
    },

    DESCEND_SOUTH(0, -1, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x, z + 1, result);
        }
    },

    DIAGONAL_NORTHEAST(+1, 0, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.NORTH, Direction.EAST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x + 1, z - 1, result);
        }
    },

    DIAGONAL_NORTHWEST(-1, 0, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.NORTH, Direction.WEST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x - 1, z - 1, result);
        }
    },

    DIAGONAL_SOUTHEAST(+1, 0, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.SOUTH, Direction.EAST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x + 1, z + 1, result);
        }
    },

    DIAGONAL_SOUTHWEST(-1, 0, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.SOUTH, Direction.WEST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x - 1, z + 1, result);
        }
    },

    PARKOUR_NORTH(0, 0, -4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.NORTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.NORTH, result);
        }
    },

    PARKOUR_SOUTH(0, 0, +4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.SOUTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.SOUTH, result);
        }
    },

    PARKOUR_EAST(+4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.EAST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.EAST, result);
        }
    },

    PARKOUR_WEST(-4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.WEST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.WEST, result);
        }
    },

    PARKOUR_DIAG2_NORTHEAST(+1, 0, -2) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.NORTH, Direction.EAST, 2, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.NORTH, Direction.EAST, 2, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG2_NORTHWEST(-1, 0, -2) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.NORTH, Direction.WEST, 2, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.NORTH, Direction.WEST, 2, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG2_SOUTHEAST(+1, 0, +2) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.SOUTH, Direction.EAST, 2, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.SOUTH, Direction.EAST, 2, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG2_SOUTHWEST(-1, 0, +2) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.SOUTH, Direction.WEST, 2, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.SOUTH, Direction.WEST, 2, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG2_EASTNORTH(+2, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.EAST, Direction.NORTH, 2, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.EAST, Direction.NORTH, 2, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG2_EASTSOUTH(+2, 0, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.EAST, Direction.SOUTH, 2, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.EAST, Direction.SOUTH, 2, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG2_WESTNORTH(-2, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.WEST, Direction.NORTH, 2, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.WEST, Direction.NORTH, 2, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG2_WESTSOUTH(-2, 0, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.WEST, Direction.SOUTH, 2, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.WEST, Direction.SOUTH, 2, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG3_NORTHEAST(+1, 0, -3) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.NORTH, Direction.EAST, 3, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.NORTH, Direction.EAST, 3, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG3_NORTHWEST(-1, 0, -3) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.NORTH, Direction.WEST, 3, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.NORTH, Direction.WEST, 3, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG3_SOUTHEAST(+1, 0, +3) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.SOUTH, Direction.EAST, 3, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.SOUTH, Direction.EAST, 3, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG3_SOUTHWEST(-1, 0, +3) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.SOUTH, Direction.WEST, 3, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.SOUTH, Direction.WEST, 3, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG3_EASTNORTH(+3, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.EAST, Direction.NORTH, 3, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.EAST, Direction.NORTH, 3, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG3_EASTSOUTH(+3, 0, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.EAST, Direction.SOUTH, 3, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.EAST, Direction.SOUTH, 3, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG3_WESTNORTH(-3, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.WEST, Direction.NORTH, 3, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.WEST, Direction.NORTH, 3, 0, 0, 0, result);
        }
    },

    PARKOUR_DIAG3_WESTSOUTH(-3, 0, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.WEST, Direction.SOUTH, 3, 0, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.WEST, Direction.SOUTH, 3, 0, 0, 0, result);
        }
    },

    PARKOUR_ASCEND_DIAG3_NORTHEAST(+1, +1, -3) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.NORTH, Direction.EAST, 3, 1, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.NORTH, Direction.EAST, 3, 1, 0, 0, result);
        }
    },

    PARKOUR_ASCEND_DIAG3_NORTHWEST(-1, +1, -3) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.NORTH, Direction.WEST, 3, 1, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.NORTH, Direction.WEST, 3, 1, 0, 0, result);
        }
    },

    PARKOUR_ASCEND_DIAG3_SOUTHEAST(+1, +1, +3) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.SOUTH, Direction.EAST, 3, 1, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.SOUTH, Direction.EAST, 3, 1, 0, 0, result);
        }
    },

    PARKOUR_ASCEND_DIAG3_SOUTHWEST(-1, +1, +3) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.SOUTH, Direction.WEST, 3, 1, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.SOUTH, Direction.WEST, 3, 1, 0, 0, result);
        }
    },

    PARKOUR_ASCEND_DIAG3_EASTNORTH(+3, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.EAST, Direction.NORTH, 3, 1, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.EAST, Direction.NORTH, 3, 1, 0, 0, result);
        }
    },

    PARKOUR_ASCEND_DIAG3_EASTSOUTH(+3, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.EAST, Direction.SOUTH, 3, 1, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.EAST, Direction.SOUTH, 3, 1, 0, 0, result);
        }
    },

    PARKOUR_ASCEND_DIAG3_WESTNORTH(-3, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.WEST, Direction.NORTH, 3, 1, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.WEST, Direction.NORTH, 3, 1, 0, 0, result);
        }
    },

    PARKOUR_ASCEND_DIAG3_WESTSOUTH(-3, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.WEST, Direction.SOUTH, 3, 1, 0, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.WEST, Direction.SOUTH, 3, 1, 0, 0, result);
        }
    },

    PARKOUR_ICE_ASCEND_NORTHEAST(+1, +1, -4) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.NORTH, Direction.EAST, 4, 1, 1, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.NORTH, Direction.EAST, 4, 1, 1, 1, result);
        }
    },

    PARKOUR_ICE_ASCEND_NORTHWEST(-1, +1, -4) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.NORTH, Direction.WEST, 4, 1, 1, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.NORTH, Direction.WEST, 4, 1, 1, 1, result);
        }
    },

    PARKOUR_ICE_ASCEND_SOUTHEAST(+1, +1, +4) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.SOUTH, Direction.EAST, 4, 1, 1, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.SOUTH, Direction.EAST, 4, 1, 1, 1, result);
        }
    },

    PARKOUR_ICE_ASCEND_SOUTHWEST(-1, +1, +4) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.SOUTH, Direction.WEST, 4, 1, 1, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.SOUTH, Direction.WEST, 4, 1, 1, 1, result);
        }
    },

    PARKOUR_ICE_ASCEND_EASTNORTH(+4, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.EAST, Direction.NORTH, 4, 1, 1, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.EAST, Direction.NORTH, 4, 1, 1, 1, result);
        }
    },

    PARKOUR_ICE_ASCEND_EASTSOUTH(+4, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.EAST, Direction.SOUTH, 4, 1, 1, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.EAST, Direction.SOUTH, 4, 1, 1, 1, result);
        }
    },

    PARKOUR_ICE_ASCEND_WESTNORTH(-4, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.WEST, Direction.NORTH, 4, 1, 1, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.WEST, Direction.NORTH, 4, 1, 1, 1, result);
        }
    },

    PARKOUR_ICE_ASCEND_WESTSOUTH(-4, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonal.cost(context, src, Direction.WEST, Direction.SOUTH, 4, 1, 1, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonal.cost(context, x, y, z, Direction.WEST, Direction.SOUTH, 4, 1, 1, 1, result);
        }
    },

    PARKOUR_ICE_NORTHEAST(+1, 0, -4) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonalIce.cost(context, src, Direction.NORTH, Direction.EAST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonalIce.cost(context, x, y, z, Direction.NORTH, Direction.EAST, result);
        }
    },

    PARKOUR_ICE_NORTHWEST(-1, 0, -4) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonalIce.cost(context, src, Direction.NORTH, Direction.WEST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonalIce.cost(context, x, y, z, Direction.NORTH, Direction.WEST, result);
        }
    },

    PARKOUR_ICE_SOUTHEAST(+1, 0, +4) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonalIce.cost(context, src, Direction.SOUTH, Direction.EAST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonalIce.cost(context, x, y, z, Direction.SOUTH, Direction.EAST, result);
        }
    },

    PARKOUR_ICE_SOUTHWEST(-1, 0, +4) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonalIce.cost(context, src, Direction.SOUTH, Direction.WEST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonalIce.cost(context, x, y, z, Direction.SOUTH, Direction.WEST, result);
        }
    },

    PARKOUR_ICE_EASTNORTH(+4, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonalIce.cost(context, src, Direction.EAST, Direction.NORTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonalIce.cost(context, x, y, z, Direction.EAST, Direction.NORTH, result);
        }
    },

    PARKOUR_ICE_EASTSOUTH(+4, 0, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonalIce.cost(context, src, Direction.EAST, Direction.SOUTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonalIce.cost(context, x, y, z, Direction.EAST, Direction.SOUTH, result);
        }
    },

    PARKOUR_ICE_WESTNORTH(-4, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonalIce.cost(context, src, Direction.WEST, Direction.NORTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonalIce.cost(context, x, y, z, Direction.WEST, Direction.NORTH, result);
        }
    },

    PARKOUR_ICE_WESTSOUTH(-4, 0, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkourDiagonalIce.cost(context, src, Direction.WEST, Direction.SOUTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkourDiagonalIce.cost(context, x, y, z, Direction.WEST, Direction.SOUTH, result);
        }
    };

    public final boolean dynamicXZ;
    public final boolean dynamicY;

    public final int xOffset;
    public final int yOffset;
    public final int zOffset;

    Moves(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
        this.dynamicXZ = dynamicXZ;
        this.dynamicY = dynamicY;
    }

    Moves(int x, int y, int z) {
        this(x, y, z, false, false);
    }

    public abstract Movement apply0(CalculationContext context, BetterBlockPos src);

    public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
        if (dynamicXZ || dynamicY) {
            throw new UnsupportedOperationException("Movements with dynamic offset must override `apply`");
        }
        result.x = x + xOffset;
        result.y = y + yOffset;
        result.z = z + zOffset;
        result.cost = cost(context, x, y, z);
    }

    public double cost(CalculationContext context, int x, int y, int z) {
        throw new UnsupportedOperationException("Movements must override `cost` or `apply`");
    }
}

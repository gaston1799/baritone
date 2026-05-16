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

package baritone.api.schematic;

import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A schematic that only touches the six outer faces of a bounding box, leaving
 * the interior completely untouched.  Interior positions return false from
 * {@link #inSchematic} so the builder never queues them.
 */
public class OutlineSchematic extends AbstractSchematic {

    private final BlockOptionalMeta bom;

    public OutlineSchematic(int x, int y, int z, BlockOptionalMeta bom) {
        super(x, y, z);
        this.bom = bom;
    }

    private boolean isOnBoundary(int lx, int ly, int lz) {
        return lx == 0 || lx == x - 1 || ly == 0 || ly == y - 1 || lz == 0 || lz == z - 1;
    }

    @Override
    public boolean inSchematic(int lx, int ly, int lz, BlockState currentState) {
        return lx >= 0 && lx < x && ly >= 0 && ly < y && lz >= 0 && lz < z
                && isOnBoundary(lx, ly, lz);
    }

    @Override
    public BlockState desiredState(int lx, int ly, int lz, BlockState current, List<BlockState> approxPlaceable) {
        if (bom.matches(current)) {
            return current;
        }
        for (BlockState placeable : approxPlaceable) {
            if (bom.matches(placeable)) {
                return placeable;
            }
        }
        return bom.getAnyBlockState();
    }
}

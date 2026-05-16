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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class AscendDebugTrace {

    private static final long MAX_AGE_MILLIS = 5000L;
    private static final AtomicLong NEXT_SEQUENCE = new AtomicLong();

    private static volatile AscendDebugInfo lastInfo;
    private static volatile long lastConsumedSequence;

    private AscendDebugTrace() {}

    public static void record(CalculationContext context, int x, int y, int z, int destX, int destZ, String reason, BetterBlockPos... blockers) {
        if (!Baritone.settings().renderAscendDebug.value && !Baritone.settings().logAscendDebug.value) {
            return;
        }
        if (context.getBaritone() == null || context.getBaritone().getPlayerContext().player() == null) {
            return;
        }
        BetterBlockPos feet = context.getBaritone().getPlayerContext().playerFeet();
        if (feet != null) {
            int dx = Math.abs(feet.x - x);
            int dy = Math.abs(feet.y - y);
            int dz = Math.abs(feet.z - z);
            if (Math.max(dx, Math.max(dy, dz)) > 6) {
                return;
            }
        }
        List<BetterBlockPos> blockerList = blockers.length == 0 ? Collections.emptyList() : Arrays.asList(blockers);
        lastInfo = new AscendDebugInfo(
                new BetterBlockPos(x, y, z),
                new BetterBlockPos(destX, y + 1, destZ),
                blockerList,
                describe(context, reason, blockerList),
                NEXT_SEQUENCE.incrementAndGet(),
                System.currentTimeMillis()
        );
    }

    private static String describe(CalculationContext context, String reason, List<BetterBlockPos> blockers) {
        if (blockers.isEmpty()) {
            return reason;
        }
        StringBuilder builder = new StringBuilder(reason);
        builder.append(" [playerHeight=").append(context.playerHeight);
        for (BetterBlockPos blocker : blockers) {
            BlockState state = context.get(blocker);
            builder.append(", ")
                    .append(blocker)
                    .append("=")
                    .append(state)
                    .append(", walkThrough=")
                    .append(MovementHelper.canWalkThrough(context, blocker.x, blocker.y, blocker.z, state));
        }
        return builder.append(']').toString();
    }

    public static AscendDebugInfo current() {
        AscendDebugInfo info = lastInfo;
        if (info == null) {
            return null;
        }
        if (System.currentTimeMillis() - info.createdAtMillis() > MAX_AGE_MILLIS) {
            return null;
        }
        return info;
    }

    public static AscendDebugInfo pollForChat() {
        AscendDebugInfo info = current();
        if (info == null || info.sequence() <= lastConsumedSequence) {
            return null;
        }
        lastConsumedSequence = info.sequence();
        return info;
    }
}

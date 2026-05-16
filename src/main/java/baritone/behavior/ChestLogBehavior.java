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
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.Helper;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Optional;

public class ChestLogBehavior extends Behavior {

    public boolean enabled = false;
    private AbstractContainerMenu lastMenu;

    // remembered while a container is open — used to label the close log line
    private String openTitle;
    private BlockPos openPos;

    public ChestLogBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (!enabled || event.getType() != TickEvent.Type.IN) return;
        if (ctx.player() == null) return;

        AbstractContainerMenu current = ctx.player().containerMenu;
        boolean wasInventory = lastMenu == null || lastMenu == ctx.player().inventoryMenu;
        boolean isContainer  = current != ctx.player().inventoryMenu;

        if (isContainer && wasInventory) {
            // Resolve screen title
            openTitle = "Unknown";
            if (ctx.minecraft().screen instanceof AbstractContainerScreen<?> cs) {
                openTitle = cs.getTitle().getString();
            }

            // Crosshair target block
            Optional<BlockPos> targeted = ctx.getSelectedBlock();
            openPos = targeted.orElse(null);

            String posStr = openPos != null
                    ? " @ " + openPos.getX() + " " + openPos.getY() + " " + openPos.getZ()
                    : "";
            int slots = current.slots.size();
            Helper.HELPER.logDirect("[Chest] Opened: " + openTitle + posStr + " (" + slots + " slots)");
        }

        lastMenu = current;
    }

    /**
     * Fires when the CLIENT sends ServerboundContainerClosePacket — meaning the
     * player (or bot) deliberately closed the container, not a server force-close.
     */
    @Override
    public void onSendPacket(PacketEvent event) {
        if (!enabled || event.getState() != EventState.PRE) return;
        if (!(event.getPacket() instanceof ServerboundContainerClosePacket)) return;

        String posStr = openPos != null
                ? " @ " + openPos.getX() + " " + openPos.getY() + " " + openPos.getZ()
                : "";
        String title = openTitle != null ? openTitle : "Unknown";
        Helper.HELPER.logDirect("[Chest] Closed: " + title + posStr);

        openTitle = null;
        openPos   = null;
    }
}

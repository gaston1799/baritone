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

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.utils.BaritoneNetwork;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;

/**
 * #net – connect multiple Minecraft clients running Baritone so a single master
 * can send commands to all workers simultaneously over LAN or WAN.
 *
 * Subcommands:
 *   host [port]           – become master, open TCP server (default port 11111)
 *   connect <ip> [port]   – become worker, connect to master
 *   stop                  – shut down server or disconnect from master
 *   list                  – show current connection status
 *   send <command...>     – (master) broadcast a baritone command to all workers
 */
public class NetCommand extends Command {

    private static final List<String> SUBCOMMANDS =
            Arrays.asList("host", "connect", "stop", "list", "send");

    private final BaritoneNetwork network;

    public NetCommand(IBaritone baritone) {
        super(baritone, "net");
        this.network = new BaritoneNetwork(baritone);
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        String sub = args.getString().toLowerCase();

        switch (sub) {
            case "host": {
                int port = args.hasAny()
                        ? args.getAs(Integer.class)
                        : BaritoneNetwork.DEFAULT_PORT;
                try {
                    network.startMaster(port);
                    String ip = lanIp();
                    logDirect("Hosting on " + ip + ":" + port);
                    logDirect("Workers connect with: #net connect " + ip + " " + port);
                    logDirect("If workers time out, allow port " + port + " through Windows Firewall:");
                    logDirect("  netsh advfirewall firewall add rule name=\"Baritone Net\" protocol=TCP dir=in localport=" + port + " action=allow");
                } catch (IOException e) {
                    throw new CommandInvalidStateException("Failed to start server: " + e.getMessage());
                } catch (IllegalStateException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                break;
            }

            case "connect": {
                args.requireMin(1);
                String ip = args.getString();
                int port = args.hasAny()
                        ? args.getAs(Integer.class)
                        : BaritoneNetwork.DEFAULT_PORT;
                try {
                    network.connectToMaster(ip, port);
                    logDirect("Connected to master at " + ip + ":" + port
                            + ". Awaiting commands.");
                } catch (IOException e) {
                    throw new CommandInvalidStateException("Failed to connect: " + e.getMessage());
                } catch (IllegalStateException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                break;
            }

            case "stop": {
                if (!network.isMaster() && !network.isWorker()) {
                    throw new CommandInvalidStateException("Not connected.");
                }
                network.stop();
                logDirect("Network stopped.");
                break;
            }

            case "list": {
                logDirect(network.getStatusLine());
                if (network.isMaster()) {
                    List<String> addrs = network.workerAddresses();
                    if (addrs.isEmpty()) {
                        logDirect("  (no workers)");
                    } else {
                        addrs.forEach(a -> logDirect("  " + a));
                    }
                }
                break;
            }

            case "send": {
                args.requireMin(1);
                // Reassemble the remaining tokens into a single command string
                StringBuilder sb = new StringBuilder();
                while (args.hasAny()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(args.getString());
                }
                String command = sb.toString();
                try {
                    int count = network.liveWorkerCount();
                    if (count == 0) {
                        throw new CommandInvalidStateException("No workers connected.");
                    }
                    network.broadcast(command);
                    logDirect("Sent to " + count + " worker(s): " + command);
                } catch (IllegalStateException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                break;
            }

            default:
                throw new CommandInvalidStateException(
                        "Unknown subcommand '" + sub + "'. Use: host, connect, stop, list, send");
        }
    }

    private static String lanIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                    continue;
                }
                if (isVirtualAdapter(iface.getName()) || isVirtualAdapter(iface.getDisplayName())) {
                    continue;
                }
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip.contains(":")) continue; // skip IPv6
                    // Prefer 192.168.x.x (typical home LAN) — return immediately
                    if (ip.startsWith("192.168.")) return ip;
                    // 10.x.x.x or 172.16-31.x.x are private but may be VPN/virtual — keep as fallback
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback != null ? fallback : "<your-ip>";
    }

    private static boolean isVirtualAdapter(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.contains("zerotier")
                || lower.contains("virtualbox")
                || lower.contains("vmware")
                || lower.contains("vbox")
                || lower.contains("hyper-v")
                || lower.contains("vpn")
                || lower.contains("tun")
                || lower.contains("tap")
                || lower.contains("docker")
                || lower.contains("wsl");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return SUBCOMMANDS.stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Multi-instance command broadcasting";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Connect multiple Baritone instances so one master can send commands",
                "to all workers in real time over LAN or WAN (TCP).",
                "",
                "Usage:",
                "> net host [port]           - Open a server (default port 11111)",
                "> net connect <ip> [port]   - Connect this instance as a worker",
                "> net stop                  - Stop server or disconnect",
                "> net list                  - Show connection status + worker list",
                "> net send <command...>     - Broadcast a baritone command to workers",
                "",
                "Example workflow:",
                "  Machine A (master):  #net host",
                "  Machine B (worker):  #net connect 192.168.1.5",
                "  Machine A sends:     #net send mine diamond_ore",
                "  (Machine B starts mining diamonds)"
        );
    }
}

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

package baritone.utils;

import baritone.api.IBaritone;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages TCP networking so one Baritone instance (master) can send commands
 * to any number of connected worker instances over LAN or WAN.
 *
 * Protocol (line-delimited text):
 *   master → worker : "CMD:<command_string>"
 *   worker → master : "STATUS:<message>"
 */
public class BaritoneNetwork {

    public static final int DEFAULT_PORT = 11111;

    private final IBaritone baritone;

    // ── master state ──────────────────────────────────────────────────────────
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final List<WorkerConnection> workers = new CopyOnWriteArrayList<>();

    // ── worker state ──────────────────────────────────────────────────────────
    private Socket masterSocket;
    private PrintWriter masterOut;
    private Thread receiveThread;

    private String reconnectIp;
    private int reconnectPort;
    private volatile boolean stopReconnect = false;

    private static final int RECONNECT_DELAY_MS = 5000;

    private volatile boolean isMaster = false;
    private volatile boolean isWorker = false;

    public BaritoneNetwork(IBaritone baritone) {
        this.baritone = baritone;
    }

    // ── master API ────────────────────────────────────────────────────────────

    /**
     * Opens a TCP server on {@code port} and accepts incoming worker connections.
     * Blocks until the port is bound; acceptance runs in a daemon thread.
     */
    public void startMaster(int port) throws IOException {
        if (isMaster || isWorker) {
            throw new IllegalStateException("Already running – call #net stop first");
        }
        serverSocket = new ServerSocket(port);
        isMaster = true;
        acceptThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    WorkerConnection conn = new WorkerConnection(client);
                    workers.add(conn);
                    conn.startReading();
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        e.printStackTrace();
                    }
                }
            }
        }, "baritone-net-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * Sends {@code command} (without {@code #} prefix) to every live worker.
     * Dead connections are pruned automatically.
     */
    public void broadcast(String command) {
        if (!isMaster) {
            throw new IllegalStateException("Not the master – start with #net host first");
        }
        workers.removeIf(WorkerConnection::isDead);
        for (WorkerConnection w : workers) {
            w.send("CMD:" + command);
        }
    }

    // ── worker API ────────────────────────────────────────────────────────────

    /**
     * Connects to a master at {@code ip:port} and enables auto-reconnect.
     * If the connection drops, the worker retries every 5 seconds until
     * {@link #stop()} is called. Incoming commands run on the MC client thread.
     */
    public void connectToMaster(String ip, int port) throws IOException {
        if (isMaster || isWorker) {
            throw new IllegalStateException("Already running – call #net stop first");
        }
        reconnectIp = ip;
        reconnectPort = port;
        stopReconnect = false;
        doConnect(ip, port); // throws IOException on first-attempt failure
    }

    private static final int CONNECT_TIMEOUT_MS = 5000;

    private void doConnect(String ip, int port) throws IOException {
        masterSocket = new Socket();
        masterSocket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
        masterOut = new PrintWriter(new OutputStreamWriter(masterSocket.getOutputStream()), true);
        isWorker = true;
        receiveThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(masterSocket.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("CMD:")) {
                        final String command = line.substring(4);
                        Minecraft.getInstance().execute(() -> {
                            if (command.startsWith("/")) {
                                Minecraft mc = Minecraft.getInstance();
                                if (mc.player != null) {
                                    mc.player.connection.sendCommand(command.substring(1));
                                }
                            } else {
                                baritone.getCommandManager().execute(command);
                            }
                        });
                        masterOut.println("STATUS:executed " + command);
                    }
                }
            } catch (IOException e) {
                if (!masterSocket.isClosed()) {
                    e.printStackTrace();
                }
            }
            isWorker = false;
            scheduleReconnect();
        }, "baritone-net-receive");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    private void scheduleReconnect() {
        if (stopReconnect) return;
        Thread t = new Thread(() -> {
            while (!stopReconnect) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (stopReconnect) return;
                try {
                    doConnect(reconnectIp, reconnectPort);
                    return; // connected — receive thread takes over
                } catch (IOException ignored) {
                    // still unreachable, loop and retry
                }
            }
        }, "baritone-net-reconnect");
        t.setDaemon(true);
        t.start();
    }

    // ── shared API ────────────────────────────────────────────────────────────

    public void stop() {
        if (isMaster) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            for (WorkerConnection w : workers) w.close();
            workers.clear();
            isMaster = false;
        }
        if (isWorker || reconnectIp != null) {
            stopReconnect = true;
            reconnectIp = null;
            if (masterSocket != null) {
                try { masterSocket.close(); } catch (IOException ignored) {}
            }
            isWorker = false;
        }
    }

    public boolean isMaster() { return isMaster; }
    public boolean isWorker() { return isWorker; }

    public int liveWorkerCount() {
        workers.removeIf(WorkerConnection::isDead);
        return workers.size();
    }

    public List<String> workerAddresses() {
        workers.removeIf(WorkerConnection::isDead);
        List<String> addrs = new ArrayList<>();
        for (WorkerConnection w : workers) addrs.add(w.getAddress());
        return addrs;
    }

    public String getStatusLine() {
        if (isMaster) {
            return "Master on port " + serverSocket.getLocalPort()
                    + " – " + liveWorkerCount() + " worker(s) connected";
        }
        if (isWorker) {
            return "Worker – connected to " + masterSocket.getRemoteSocketAddress();
        }
        if (reconnectIp != null && !stopReconnect) {
            return "Worker – reconnecting to " + reconnectIp + ":" + reconnectPort + " (retrying every 5s)";
        }
        return "Not connected";
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private static class WorkerConnection {
        private final Socket socket;
        private final PrintWriter out;
        private volatile boolean dead = false;

        WorkerConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        }

        void startReading() {
            Thread t = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()))) {
                    // drain STATUS lines (could log them if desired)
                    while (reader.readLine() != null) {}
                } catch (IOException ignored) {}
                dead = true;
            }, "baritone-net-worker-" + socket.getRemoteSocketAddress());
            t.setDaemon(true);
            t.start();
        }

        void send(String line) {
            if (!dead) out.println(line);
        }

        boolean isDead() { return dead || socket.isClosed(); }

        void close() {
            dead = true;
            try { socket.close(); } catch (IOException ignored) {}
        }

        String getAddress() { return socket.getRemoteSocketAddress().toString(); }
    }
}

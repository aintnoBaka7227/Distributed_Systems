package org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * MessageServer:
 * a daemon thread TCP server that accepts one-line messages and forwards them to the Paxos node
 * Features:
 * Retry-binding on startup
 * Per-connection handler threads (daemon)
 * Supports control/admin commands over TCP
 * Applies receive-side latency/drop profile before dispatching
 */
public class MessageServer implements Runnable {
    private final int port;
    private final MemberProfile profile;
    private final PaxosNode node;
    private volatile boolean running = true;
    private int retryLimit = 40;

    public MessageServer(int port, MemberProfile profile, PaxosNode node) {
        this.port = port;
        this.profile = profile;
        this.node = node;
    }

    /**
     * run:
     * Attempt to bind to the configured port with limited retries
     * Accept incoming connections and spawn per-connection handler threads
     * On shutdown or error, close the server socket
     */
    @Override
    public void run() {
        ServerSocket serverSocket = null;

        // Retry binding to tolerate TIME_WAIT or quick restarts
        for (int attempt = 0; attempt < retryLimit && running; attempt++) {
            try {
                serverSocket = new ServerSocket(port);
                Logger.info("Server bound on port " + port);
                break;
            } catch (BindException be) {
                if (attempt == 0) {
                    System.err.println("Server bind error (in use), retrying: " + be.getMessage());
                }
                try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            } catch (IOException e) {
                System.err.println("Server error while binding: " + e.getMessage());
                return;
            }
        }

        if (serverSocket == null) {
            System.err.println("Server failed to bind on port " + port + " after retries.");
            return;
        }

        try {
            while (running) {
                Socket socket = serverSocket.accept();
                Thread t = new Thread(() -> handleIncomingMessage(socket),
                        "Conn-" + socket.getRemoteSocketAddress());
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Server error: " + e.getMessage());
            }
        } finally {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * handleIncomingMessage:
     * Reads exactly one line from the socket:
     * If parseable as a protocol Message: apply profile filter, log, and dispatch to node
     * Otherwise: treat as admin command (e.g., "propose M5", "profile reliable")
     * Always closes the socket
     */
    private void handleIncomingMessage(Socket socket) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line = br.readLine();
            if (line != null) {
                // Receive-side drop/latency simulation; return early if dropped
                if (!profile.beforeNetworkAction()) return;
                try {
                    Message m = Message.parseMessage(line);
                    Logger.messageReceived(m.senderID, m);
                    node.handleMessage(m);
                } catch (IllegalArgumentException ex) {
                    // Fallback to admin commands
                    String cmd = line.trim();
                    Logger.admin(cmd);
                    handleAdminCommand(cmd);
                }
            }
        } catch (IOException e) {
            // Quietly ignore transient connection issues
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * handleAdminCommand:
     * Executes side-effecting runtime controls received over TCP:
     * propose <Candidate>       start a Paxos run
     * profile/latency/drop      adjust runtime network profile
     * crash                     terminate process (simulated failure)
     * state                     log current node snapshot
     * Safety parsing to avoid crashing on invalid commands
     */
    private void handleAdminCommand(String cmd) {
        try {
            if (cmd.toLowerCase().startsWith("propose ")) {
                String candidate = cmd.substring("propose ".length()).trim();
                node.initiateProposal(candidate);

            } else if (cmd.toLowerCase().startsWith("profile ")) {
                String name = cmd.substring("profile ".length()).trim();
                MemberProfile np = MemberProfile.setLatency(name);
                profile.updateFrom(np);
                System.out.println("Profile updated to " + profile.getLatencyType());

            } else if (cmd.toLowerCase().startsWith("latency ")) {
                String[] parts = cmd.split("\\s+");
                if (parts.length >= 3) {
                    int min = Integer.parseInt(parts[1]);
                    int max = Integer.parseInt(parts[2]);
                    profile.setLatency(min, max);
                    System.out.println("Latency set to " + min + "-" + max + " ms");
                }

            } else if (cmd.toLowerCase().startsWith("drop ")) {
                String[] parts = cmd.split("\\s+");
                if (parts.length >= 2) {
                    double rate = Double.parseDouble(parts[1]);
                    profile.setDropRate(rate);
                    System.out.println("Drop rate set to " + rate);
                }

            } else if (cmd.equalsIgnoreCase("crash")) {
                System.out.println("Crashing by request via TCP...");
                System.exit(1);

            } else if (cmd.equalsIgnoreCase("state")) {
                String snap = node.getStateSnapshot();
                Logger.info("STATE " + snap);
            }
        } catch (Exception e) {
            System.err.println("Admin cmd error: " + e.getMessage());
        }
    }
}

package org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Simple TCP server that accepts incoming messages and dispatches to the Paxos node.
 */
public class MessageServer implements Runnable {
    private final int port;
    private final MemberProfile profile;
    private final PaxosNode node;
    private volatile boolean running = true;

    public MessageServer(int port, MemberProfile profile, PaxosNode node) {
        this.port = port;
        this.profile = profile;
        this.node = node;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (running) {
                Socket socket = serverSocket.accept();
                Thread t = new Thread(() -> handle(socket), "Conn-" + socket.getRemoteSocketAddress());
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Server error: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line = br.readLine();
            if (line != null) {
                // simulate receive-side profile
                if (!profile.beforeNetworkAction()) return; // drop
                try {
                    Message m = Message.decode(line);
                    Logger.messageReceived(m.fromId, m);
                    node.onMessage(m);
                } catch (IllegalArgumentException ex) {
                    // Treat as admin command for automation via TCP (nc)
                    String cmd = line.trim();
                    Logger.admin(cmd);
                    handleAdminCommand(cmd);
                }
            }
        } catch (IOException e) {
            // ignore
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleAdminCommand(String cmd) {
        try {
            if (cmd.toLowerCase().startsWith("propose ")) {
                String candidate = cmd.substring("propose ".length()).trim();
                node.initiateProposal(candidate);
            } else if (cmd.toLowerCase().startsWith("profile ")) {
                String name = cmd.substring("profile ".length()).trim();
                MemberProfile np = MemberProfile.fromName(name);
                profile.updateFrom(np);
                System.out.println("Profile updated to " + profile.getName());
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

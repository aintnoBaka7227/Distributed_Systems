package org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
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
    private int retryLimit = 40;

    public MessageServer(int port, MemberProfile profile, PaxosNode node) {
        this.port = port;
        this.profile = profile;
        this.node = node;
    }

    @Override
    public void run() {
        ServerSocket serverSocket = null;
        // Retry binding to the port in case a previous process is still releasing it
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
                Thread t = new Thread(() -> handleIncomingMessage(socket), "Conn-" + socket.getRemoteSocketAddress());
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

    private void handleIncomingMessage(Socket socket) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line = br.readLine();
            if (line != null) {
                // simulate receive-side profile
                if (!profile.beforeNetworkAction()) return; // drop
                try {
                    Message m = Message.parseMessage(line);
                    Logger.messageReceived(m.senderID, m);
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

package org;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageServerTest:
 * Verifies that MessageServer correctly handles both protocol-formatted lines (Message.parseMessage)
 * and raw admin commands sent over TCP, update the active MemberProfile accordingly.
 */
public class MessageServerTest {

    /**
     * Creates a temporary one-line config file for binding.
     */
    private static NetworkConfig mockConfig(String peerId, int port) throws IOException {
        File temp = File.createTempFile("cfg", ".txt");
        try (FileWriter w = new FileWriter(temp)) {
            w.write(peerId + ",localhost," + port + "\n");
        }
        temp.deleteOnExit();
        return NetworkConfig.loadConfigFile(temp.getAbsolutePath());
    }

    /**
     * Server accepts a protocol message and an admin line on the same port
     * Protocol line should parse and dispatch to node without error
     * Admin line "profile latent" should update MemberProfile at runtime
     */
    @Test
    void handlesMessageAndAdminLines() throws Exception {
        // Pick an free port
        java.net.ServerSocket chooser = new java.net.ServerSocket(0);
        int port = chooser.getLocalPort();
        chooser.close();

        // Minimal 1-member config for this port
        NetworkConfig cfg = mockConfig("M1", port);
        MemberProfile profile = MemberProfile.setLatency("reliable");

        // No-op client: server path under test is inbound handling
        NetworkClient client = new NetworkClient(cfg, profile) {
            @Override public void sendMessage(String id, Message m) {}
            @Override public void broadcastMessage(String self, Message m, boolean x) {}
        };
        PaxosNode node = new PaxosNode("M1", cfg, client, profile);

        // Start the TCP server under test
        MessageServer server = new MessageServer(port, profile, node);
        Thread t = new Thread(server, "server-test");
        t.setDaemon(true);
        t.start();

        // Send a protocol message line 
        // Use a real socket so we exercise the full accept/read/dispatch path
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", port), 1000);
            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {
                out.println(Message.prepare("M2", 1).constructMessage());
            }
        }

        // Send an admin command line
        assertEquals("reliable", profile.getLatencyType(), "Precondition: profile starts as reliable");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", port), 1000);
            try (PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {
                out.println("profile latent");
            }
        }

        // Allow the background server thread to process both connections
        Thread.sleep(200);

        // Profile should have been updated by the admin command handler
        assertEquals("latent", profile.getLatencyType());
    }
}

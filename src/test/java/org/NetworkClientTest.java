package org;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NetworkClientTest:
 * Exercises the outbound send path over TCP to ensure messages are serialized
 * and delivered correctly to a listening peer
 */
public class NetworkClientTest {

    /** Creates a temporary one-line config file for a single peer on localhost:port */
    private static NetworkConfig mockConfig(String peerId, int port) throws IOException {
        File temp = File.createTempFile("cfg", ".txt");
        try (FileWriter w = new FileWriter(temp)) {
            w.write(peerId + ",localhost," + port + "\n");
        }
        temp.deleteOnExit();
        return NetworkConfig.loadConfigFile(temp.getAbsolutePath());
    }

    /**
     * sendMessage performs a TCP connect/write and the receiver
     * can parse the line into a valid Message object
     * Spin up an ephemeral ServerSocket to act as a peer
     * Start a thread that accepts one connection and parses a single line
     * Use NetworkClient.sendMessage() to deliver a PREPARE from M1 to M2
     */
    @Test
    void sendMessageOverTCP() throws Exception {
        ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();

        NetworkConfig cfg = mockConfig("M2", port);
        NetworkClient client = new NetworkClient(cfg, MemberProfile.setLatency("reliable"));

        // Receiver thread accept exactly one connection and parse one line
        new Thread(() -> {
            try {
                Socket s = server.accept();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
                    String line = br.readLine();
                    Message parsed = Message.parseMessage(line);
                    assertEquals(MessageType.PREPARE, parsed.type);
                    assertEquals("M1", parsed.senderID);
                    assertEquals(123, parsed.proposalID);
                } finally {
                    s.close();
                }
            } catch (Exception e) {
                fail(e);
            }
        }).start();

        // Send a PREPARE to peer "M2" on the chosen port
        client.sendMessage("M2", Message.prepare("M1", 123));

        // Cleanup server socket after the interaction
        server.close();
    }
}

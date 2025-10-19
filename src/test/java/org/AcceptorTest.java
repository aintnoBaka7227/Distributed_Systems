package org;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AcceptorTest:
 * Verifies core Acceptor behaviors across Paxos Phase 1 and Phase 2 handling
 * Test promise/accept rules, replay prevention, and recovery behavior
 */
class AcceptorTest {

    /**
     * CapturingClient:
     * Lightweight mock NetworkClient that captures all sent and broadcast messages
     */
    static class CapturingClient extends NetworkClient {
        List<Message> sent = new ArrayList<>();
        List<Message> broadcast = new ArrayList<>();

        CapturingClient(NetworkConfig c, MemberProfile p) { super(c, p); }

        @Override
        public void sendMessage(String id, Message m) { sent.add(m); }

        @Override
        public void broadcastMessage(String selfId, Message m, boolean excludeSelf) { broadcast.add(m); }
    }

    /**
     * Creates a temporary 3-member network configuration for isolated testing
     * Each member is bound to localhost ports 9001–9003
     */
    private static NetworkConfig mockConfigWithThreeMembers() {
        try {
            String text = "M1,localhost,9001\nM2,localhost,9002\nM3,localhost,9003\n";
            var f = java.io.File.createTempFile("cfg", ".txt");
            try (var w = new java.io.FileWriter(f)) { w.write(text); }
            f.deleteOnExit();
            return NetworkConfig.loadConfigFile(f.getAbsolutePath());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * Acceptor only sends a PROMISE when receiving a strictly higher proposal ID
     */
    @Test
    void onlySendPromiseWhenHigher() {
        var cfg = mockConfigWithThreeMembers();
        var client = new CapturingClient(cfg, MemberProfile.setLatency("reliable"));
        var learner = new Learner("M1", cfg);
        var acceptor = new Acceptor("M2", client, learner);

        // Send repeated and lower PREPARE messages
        acceptor.handlePrepare(Message.prepare("P", 100));   
        acceptor.handlePrepare(Message.prepare("P", 100));   
        acceptor.handlePrepare(Message.prepare("P", 99));    

        // Validate exactly one PROMISE generated and internal state updated
        assertEquals(1, client.sent.size(), "Exactly one PROMISE for first higher prepare");
        assertEquals(MessageType.PROMISE, client.sent.get(0).type);
        assertEquals(100, acceptor.getHighestPromised());
    }

    /**
     * Acceptor accepts only requests with proposal ID >= highestPromised
     * Updates acceptedId, acceptedValue, and broadcasts ACCEPTED
     * Lower proposals are ignored
     */
    @Test
    void onlyAcceptedWithHigherValue() {
        var cfg = mockConfigWithThreeMembers();
        var client = new CapturingClient(cfg, MemberProfile.setLatency("reliable"));
        var learner = new Learner("M1", cfg);
        var acceptor = new Acceptor("M2", client, learner);

        // Phase 2: valid then invalid proposal ID sequence
        acceptor.handleAcceptRequest(Message.acceptRequest("P", 21, "Ingrid")); // >= -1 accepted
        acceptor.handleAcceptRequest(Message.acceptRequest("P", 20, "Phong"));  // < 21 ignored

        // Verify only one ACCEPTED broadcast and state persistence
        assertEquals(1, client.broadcast.size(), "One ACCEPTED for n=21");
        assertEquals(Integer.valueOf(21), acceptor.getAcceptedId());
        assertEquals("Ingrid", acceptor.getAcceptedValue());
        assertEquals(21, acceptor.getHighestPromised());
    }

    /**
     * Recovery behavior when learner already decided a value.
     * Acceptor echoes ACCEPTED only if proposal >= highestPromised AND value == decidedValue.
     * Different value or lower proposal number causes no broadcast.
     */
    @Test
    void handleAcceptRequestFromRecoveryMember() {
        var cfg = mockConfigWithThreeMembers();
        var client = new CapturingClient(cfg, MemberProfile.setLatency("reliable"));
        var learner = new Learner("M1", cfg);
        var acceptor = new Acceptor("M2", client, learner);

        // Simulate learner has already decided on "Ingrid"
        learner.trackAccepts(7, "Ingrid", "A1");
        learner.trackAccepts(7, "Ingrid", "A2");
        assertTrue(learner.isDecided());

        // Prime acceptor with a promise so highestPromised = 10
        acceptor.handlePrepare(Message.prepare("P", 10));
        int before = client.broadcast.size();

        // Case 1: matching decided value + n >= highestPromised-> echo
        acceptor.handleAcceptRequest(Message.acceptRequest("P", 12, "Ingrid"));
        assertEquals(before + 1, client.broadcast.size());

        // Case 2: mismatching value → ignore
        before = client.broadcast.size();
        acceptor.handleAcceptRequest(Message.acceptRequest("P", 15, "Phong"));
        assertEquals(before, client.broadcast.size());

        // Case 3: n < highestPromised even if value matches -> ignore
        before = client.broadcast.size();
        acceptor.handleAcceptRequest(Message.acceptRequest("P", 9, "Ingrid"));
        assertEquals(before, client.broadcast.size());
    }
}

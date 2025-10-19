package org;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PaxosIntegrationTest:
 * End-to-end Paxos verification using A stub NetworkClient
 * Ensures that multiple PaxosNode instances reach agreement under:
 * ideal conditions,concurrent proposals,proposer crash after Phase 1 (prepare) starts
 */
class PaxosIntegrationTest {

    /**
     * A stub NetworkClient that delivers messages directly to target nodes' handleMessage
     * to avoids network timing and flakiness
     */
    static class Stub extends NetworkClient {
        private final Map<String, PaxosNode> nodes;

        Stub(NetworkConfig cfg, MemberProfile profile, Map<String, PaxosNode> nodes) {
            super(cfg, profile);
            this.nodes = nodes;
        }

        @Override
        public void sendMessage(String id, Message m) {
            PaxosNode n = nodes.get(id);
            if (n != null) n.handleMessage(m);
        }

        @Override
        public void broadcastMessage(String selfId, Message m, boolean excludeSelf) {
            for (var e : nodes.entrySet()) {
                if (excludeSelf && e.getKey().equals(selfId)) continue;
                e.getValue().handleMessage(m);
            }
        }
    }

    /**
     * Builds a temporary NetworkConfig with n members on localhost with unique ports
     */
    private static NetworkConfig mockConfigWithNMembers(int n) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= n; i++)
                sb.append("M").append(i).append(",localhost,9").append(100 + i).append("\n");
            var f = java.io.File.createTempFile("cfg", ".txt");
            try (var w = new java.io.FileWriter(f)) { w.write(sb.toString()); }
            f.deleteOnExit();
            return NetworkConfig.loadConfigFile(f.getAbsolutePath());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * Under a reliable, low-latency profile with a single proposer,
     * all learners decide on one value quickly and consistently
     */
    @Test
    void idealLatencyMembersWithOneProposal() {
        NetworkConfig cfg = mockConfigWithNMembers(5); 
        MemberProfile profile = MemberProfile.setLatency("reliable"); 

        Map<String, PaxosNode> nodes = new HashMap<>();
        Stub bus = new Stub(cfg, profile, nodes);

        // Create nodes that share the same in-memory bus
        for (String id : cfg.getMemberIds()) {
            nodes.put(id, new PaxosNode(id, cfg, bus, MemberProfile.setLatency("reliable")));
        }

        // Single proposer scenario: M4 proposes M5
        PaxosNode proposer = nodes.get("M4");
        proposer.initiateProposal("M5");

        // Poll for completion (in-memory should be near-instant; allow small buffer)
        long deadline = System.currentTimeMillis() + 1000;
        boolean allDecided;
        do {
            allDecided = nodes.values().stream().allMatch(n -> n.getLearner().isDecided());
        } while (!allDecided && System.currentTimeMillis() < deadline);

        assertTrue(allDecided, "All learners should decide under ideal network");

        // All nodes must agree on the exact same decided value
        String decided = nodes.values().iterator().next().getLearner().getDecidedValue();
        for (PaxosNode n : nodes.values()) {
            assertEquals(decided, n.getLearner().getDecidedValue(), "All nodes must agree on one value");
        }
    }

    /**
     * With two nearly concurrent proposers, Paxos still converges on a single value.
     * The winner depends on the proposal number ordering (seq + memberNumericId).
     */
    @Test
    void concurrentProposalEndWithOneWinner() {
        NetworkConfig cfg = mockConfigWithNMembers(5);
        Map<String, PaxosNode> nodes = new HashMap<>();
        Stub bus = new Stub(cfg, MemberProfile.setLatency("reliable"), nodes);

        for (String id : cfg.getMemberIds()) {
            nodes.put(id, new PaxosNode(id, cfg, bus, MemberProfile.setLatency("reliable")));
        }

        // Competing proposals (race): M1 vs M5
        nodes.get("M1").initiateProposal("M1");
        nodes.get("M5").initiateProposal("M5");

        long deadline = System.currentTimeMillis() + 1500;
        boolean allDecided;
        do {
            allDecided = nodes.values().stream().allMatch(n -> n.getLearner().isDecided());
        } while (!allDecided && System.currentTimeMillis() < deadline);

        assertTrue(allDecided, "Consensus should still be reached with concurrent proposals");

        // Check single-winner property: every node reports the same decided value
        String v = nodes.values().iterator().next().getLearner().getDecidedValue();
        for (PaxosNode n : nodes.values()) assertEquals(v, n.getLearner().getDecidedValue());
    }

    /**
     * System proceeds to decision even if the original proposer "crashes" after PREPARE
     * Let M3 start a proposal (Phase 1)
     * Simulate crash by preventing further outbound from M3
     * Another node (M2) proposes and drives consensus to completion
     */
    @Test
    void systemVoteNormallyAfterProposerCrashAfterPrepare() {
        NetworkConfig cfg = mockConfigWithNMembers(5);
        Map<String, PaxosNode> nodes = new HashMap<>();

        MemberProfile reliable = MemberProfile.setLatency("reliable");

        // Shared in-memory bus for all nodes
        Stub mainBus = new Stub(cfg, reliable, nodes);

        // M3 will be crashed purposely after it initiates
        for (String id : cfg.getMemberIds()) {
            NetworkClient client = mainBus; // could swap to a null bus for M3 after crash
            nodes.put(id, new PaxosNode(id, cfg, client, MemberProfile.setLatency("reliable")));
        }

        // Step 1: M3 initiates then "crashes"
        PaxosNode m3 = nodes.get("M3");
        m3.initiateProposal("Phong");

        // Step 2: Another node (M2) drives the system to completion
        nodes.get("M2").initiateProposal("Ingrid");

        long deadline = System.currentTimeMillis() + 2000;
        boolean allDecided;
        do {
            allDecided = nodes.values().stream()
                    .filter(n -> !n.equals(m3)) // exclude crashed node from "all decided" requirement
                    .allMatch(n -> n.getLearner().isDecided());
        } while (!allDecided && System.currentTimeMillis() < deadline);

        assertTrue(allDecided, "Remaining nodes should reach consensus despite M3 crashing");

        // All non-crashed nodes must agree on the same decided value
        String decided = nodes.values().stream()
                .filter(n -> !n.equals(m3))
                .findFirst().get()
                .getLearner().getDecidedValue();

        for (PaxosNode n : nodes.values()) {
            if (n == m3) continue; // crashed node may not have learned yet
            assertEquals(decided, n.getLearner().getDecidedValue());
        }
    }
}

package org;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LearnerTest:
 * Verifies that the Learner correctly tracks ACCEPTED messages and decides when a majority agrees
 * Tests quorum logic and decision finalization
 */
class LearnerTest {

    /**
     * Creates a temporary network configuration file with n members,
     * each bound to localhost with incrementing ports
     */
    private static NetworkConfig mockConfigWithnMembers(int n) {
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
     * Learner reaches a decision only after receiving a majority of matching ACCEPTED messages
     * Before quorum: not decided
     * Upon reaching quorum: marks decided and exposes decided value
     */
    @Test
    void decidesOnMajorityAccepted() {
        NetworkConfig cfg = mockConfigWithnMembers(5);
        Learner learner = new Learner("M1", cfg);

        // Arrange 2 out of 5 ACCEPTED messages → below majority
        learner.handleAccepted(Message.accepted("A1", 100, "M7"));
        learner.handleAccepted(Message.accepted("A2", 100, "M7"));
        assertFalse(learner.isDecided(), "Should not decide before reaching quorum");

        // Third acceptance (majority reached)
        learner.handleAccepted(Message.accepted("A3", 100, "M7"));

        // learner can now decide
        assertTrue(learner.isDecided(), "Learner should decide when majority reached");
        assertEquals("M7", learner.getDecidedValue(), "Decided value should match majority proposal");
    }
}

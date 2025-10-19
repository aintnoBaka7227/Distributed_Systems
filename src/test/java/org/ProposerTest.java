package org;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProposerTest:
 * Focused checks for Proposer’s Phase 1/2 behavior:
 * PREPARE emission and monotonic proposal numbering
 * Value selection on majority of PROMISEs (highest acceptedId wins)
 * Ignoring irrelevant/late PROMISEs
 * Stopping after the Learner decides from ACCEPTED quorum
 */
class ProposerTest {

    /**
     * CapturingClient:
     * NetworkClient stub that records broadcasts for assertions
     */
    static class CapturingClient extends NetworkClient {
        final List<Message> broadcasts = new ArrayList<>();
        CapturingClient(NetworkConfig c, MemberProfile p) { super(c, p); }
        @Override
        public void broadcastMessage(String selfId, Message m, boolean excludeSelf) { broadcasts.add(m); }
        @Override
        public void sendMessage(String id, Message m) {}
    }

    /**
     * Builds a temporary NetworkConfig with n members on localhost with unique ports
     */
    private static NetworkConfig mockConfigWithNMembers(int n) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= n; i++) sb.append("M").append(i).append(",localhost,9").append(100 + i).append("\n");
            var f = java.io.File.createTempFile("cfg", ".txt");
            try (var w = new java.io.FileWriter(f)) { w.write(sb.toString()); }
            f.deleteOnExit();
            return NetworkConfig.loadConfigFile(f.getAbsolutePath());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Initiating proposals emits PREPARE and produces strictly increasing proposal IDs */
    @Test
    void initiateProposalEmitsPrepareAndMonotonicId() {
        NetworkConfig cfg = mockConfigWithNMembers(5);
        CapturingClient client = new CapturingClient(cfg, MemberProfile.setLatency("reliable"));
        Learner learner = new Learner("M1", cfg);
        Proposer proposer = new Proposer("M1", 1, cfg, client, MemberProfile.setLatency("reliable"), learner);

        // First proposal
        proposer.initiateProposal("Phong");
        int n1 = proposer.getActiveProposalId();
        assertTrue(proposer.isProposing());
        assertTrue(client.broadcasts.stream().anyMatch(m -> m.type == MessageType.PREPARE && m.proposalID == n1));

        // Second proposal must have higher id (monotonic)
        proposer.initiateProposal("Ingrid");
        int n2 = proposer.getActiveProposalId();
        assertTrue(n2 > n1, "proposal id must increase monotonically");
    }

    /**
     * After quorum of PROMISEs, proposer adopts the value tied to the highest acceptedId
     * among promises (if any), and sends exactly one ACCEPT_REQUEST for the active proposal
     */
    @Test
    void promisesMajorityAdoptsHighestAcceptedValueAndSendsAcceptOnce() {
        NetworkConfig cfg = mockConfigWithNMembers(5); // majority 3
        CapturingClient client = new CapturingClient(cfg, MemberProfile.setLatency("reliable"));
        Learner learner = new Learner("M2", cfg);
        Proposer proposer = new Proposer("M2", 2, cfg, client, MemberProfile.setLatency("reliable"), learner);

        proposer.initiateProposal("Phong");
        int n = proposer.getActiveProposalId();

        // Three PROMISES, with two carry prior accepted values with ids 50 ("Ingrid") and 70 ("Fin")
        proposer.handlePromise(Message.promise("A1", n, 50, "Ingrid"));
        proposer.handlePromise(Message.promise("A2", n, 70, "Fin"));
        proposer.handlePromise(Message.promise("A3", n, null, null));

        // Exactly one ACCEPT_REQUEST, using the value from the highest acceptedId ("Fin")
        long arCount = client.broadcasts.stream()
                .filter(m -> m.type == MessageType.ACCEPT_REQUEST && m.proposalID == n)
                .count();
        assertEquals(1, arCount, "should send one ACCEPT_REQUEST after reaching majority");

        Optional<Message> ar = client.broadcasts.stream()
                .filter(m -> m.type == MessageType.ACCEPT_REQUEST && m.proposalID == n)
                .findFirst();
        assertTrue(ar.isPresent());
        assertEquals("Fin", ar.get().proposalVal);
    }

    /**
     * Proposer ignores PROMISEs for a different proposal number and ignores
     * all PROMISEs once the learner has already decided
     */
    @Test
    void ignoresPromiseForDifferentProposalAndWhenAlreadyDecided() {
        NetworkConfig cfg = mockConfigWithNMembers(3);
        CapturingClient client = new CapturingClient(cfg, MemberProfile.setLatency("reliable"));
        Learner learner = new Learner("M3", cfg);
        Proposer proposer = new Proposer("M3", 3, cfg, client, MemberProfile.setLatency("reliable"), learner);

        proposer.initiateProposal("Phong");
        int n = proposer.getActiveProposalId();

        // Different proposal id -> should not count
        proposer.handlePromise(Message.promise("A1", n + 1, null, null));
        assertEquals(0, proposer.getPromiseCount());

        // Once decided, further promises are ignored
        learner.trackAccepts(1, "Ingrid", "A");
        learner.trackAccepts(1, "Ingrid", "B");
        assertTrue(learner.isDecided());

        proposer.handlePromise(Message.promise("A2", n, null, null));
        assertEquals(0, proposer.getPromiseCount());
    }

    /**
     * When quorum of ACCEPTED messages arrives for the active proposal,
     * the learner decides and the proposer stops proposing
     */
    @Test
    void acceptedQuorumMakesLearnerDecideAndStopsProposing() {
        NetworkConfig cfg = mockConfigWithNMembers(3); 
        CapturingClient client = new CapturingClient(cfg, MemberProfile.setLatency("reliable"));
        Learner learner = new Learner("M4", cfg);
        Proposer proposer = new Proposer("M4", 4, cfg, client, MemberProfile.setLatency("reliable"), learner);

        proposer.initiateProposal("W");
        int n = proposer.getActiveProposalId();

        // First ACCEPTED: not yet majority
        proposer.handleAccepted(Message.accepted("A1", n, "W"));
        assertFalse(learner.isDecided());

        // Second ACCEPTED: majority reached → decision
        proposer.handleAccepted(Message.accepted("A2", n, "W"));

        assertTrue(learner.isDecided());
        assertEquals("W", learner.getDecidedValue());
        assertFalse(proposer.isProposing(), "proposer should stop once decision is learned");
    }
}

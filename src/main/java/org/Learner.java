package org;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Learner:
 * Collects ACCEPTED messages and determines when quorum is met on the same proposal/value
 * Once a majority is reached, the value is marked as decided, and further accepts are ignored
 */
class Learner {
    private final String memberId;
    private final NetworkConfig config;

    // Track if consensus is reached
    private volatile boolean decided = false;
    // The consensus value once consensus is achieved
    private volatile String decidedValue = null;
    // tracked all accepteds
    private final Map<String, Set<String>> accepts = new HashMap<>();

    Learner(String memberId, NetworkConfig config) {
        this.memberId = memberId;
        this.config = config;
    }

    boolean isDecided() { return decided; }
    String getDecidedValue() { return decidedValue; }

    /**
     * handleAccepted:
     * Processes incoming ACCEPTED messages from acceptors
     * Call trackAccepts() to update acceptance counts
     */
    synchronized void handleAccepted(Message m) {
        if (m.proposalID == null || m.proposalVal == null) return;
        trackAccepts(m.proposalID, m.proposalVal, m.senderID);
    }

    /**
     * trackAccepts:
     * When a quorum is reached, marks the value as decided
     */
    synchronized void trackAccepts(Integer proposalId, String value, String acceptorId) {
        if (proposalId == null || value == null || decided) return;

        String key = proposalId + "|" + value;
        Set<String> set = accepts.computeIfAbsent(key, k -> new HashSet<>());
        set.add(acceptorId);
        int count = set.size();

        Logger.info(memberId + " LEARN count=" + count + "/" + config.majority()
                + " for n=" + proposalId + " v=" + value);

        // If majority reached, mark final consensus value
        if (count >= config.majority() && !decided) {
            decided = true;
            decidedValue = value;
            Logger.consensus(decidedValue);
            System.out.println("CONSENSUS: " + decidedValue + " has been elected Council President!");
        }
    } 
}

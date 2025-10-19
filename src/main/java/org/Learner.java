package org;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Learner role: aggregates ACCEPTED messages and decides when a majority is observed.
 */
class Learner {
    private final String memberId;
    private final NetworkConfig config;
    private volatile boolean decided = false;
    private volatile String decidedValue = null;
    private final Map<String, Set<String>> accepts = new HashMap<>();

    Learner(String memberId, NetworkConfig config) {
        this.memberId = memberId;
        this.config = config;
    }

    boolean isDecided() { return decided; }
    String getDecidedValue() { return decidedValue; }

    synchronized void handleAccepted(Message m) {
        if (m.proposalID == null || m.proposalVal == null) return;
        trackAccepts(m.proposalID, m.proposalVal, m.senderID);
    }

    synchronized void trackAccepts(Integer proposalId, String value, String acceptorId) {
        if (proposalId == null || value == null) return;
        if (decided) return;
        String key = proposalId + "|" + value;
        Set<String> set = accepts.computeIfAbsent(key, k -> new HashSet<>());
        set.add(acceptorId);
        int count = set.size();
        Logger.info(memberId + " LEARN count=" + count + "/" + config.majority() + " for n=" + proposalId + " v=" + value);
        if (count >= config.majority() && !decided) {
            decided = true;
            decidedValue = value;
            Logger.consensus(decidedValue);
            System.out.println("CONSENSUS: " + decidedValue + " has been elected Council President!");
        }
    } 
}


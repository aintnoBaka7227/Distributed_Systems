package org;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Proposer role: drives PREPARE/PROMISE and ACCEPT_REQUEST.
 */
class Proposer {
    private static final int RETRY_BASE_MS = 2000;
    private static final int RETRY_JITTER_MS = 1000;

    private final String memberId;
    private final int memberNumericId;
    private final NetworkConfig config;
    private final NetworkClient client;
    private final MemberProfile profile;
    private final Learner learner;
    private final Random rnd = new Random();

    private int proposalSeq = 0;
    private volatile boolean proposing = false;
    private int activeProposalId = -1;
    private String activeCandidate = null;
    private final Object lock = new Object();
    private final Map<String, PromiseInfo> promises = new HashMap<>();
    private final Set<String> acceptedBy = new HashSet<>();
    private boolean acceptRequested = false;

    private static class PromiseInfo {
        Integer acceptedId; String acceptedValue;
        PromiseInfo(Integer id, String val) { this.acceptedId = id; this.acceptedValue = val; }
    }

    Proposer(String memberId, int memberNumericId, NetworkConfig config, NetworkClient client, MemberProfile profile, Learner learner) {
        this.memberId = memberId;
        this.memberNumericId = memberNumericId;
        this.config = config;
        this.client = client;
        this.profile = profile;
        this.learner = learner;
    }

    // Expose state for snapshot
    boolean isProposing() { synchronized (lock) { return proposing; } }
    int getActiveProposalId() { synchronized (lock) { return activeProposalId; } }
    String getActiveCandidate() { synchronized (lock) { return activeCandidate; } }
    int getPromiseCount() { synchronized (lock) { return promises.size(); } }
    int getAcceptedByCount() { synchronized (lock) { return acceptedBy.size(); } }

    void initiateProposal(String candidate) {
        if (learner.isDecided()) {
            System.out.println("Already decided: " + learner.getDecidedValue());
            return;
        }
        synchronized (lock) {
            proposalSeq++;
            activeProposalId = PaxosNode.makeProposalId(proposalSeq, memberNumericId);
            activeCandidate = candidate;
            proposing = true;
            promises.clear();
            acceptedBy.clear();
            acceptRequested = false;
        }
        Logger.info(memberId + " starting PREPARE n=" + activeProposalId + " v=" + candidate);
        broadcastPrepare(activeProposalId);
        new Thread(() -> proposalRetry(activeProposalId), "proposalRetry-" + activeProposalId).start();
    }

    private void proposalRetry(int proposalId) {
        try {
            int timeout = Math.max(RETRY_BASE_MS, profile.getMaxLatencyMs() * 2 + 500);
            timeout += rnd.nextInt(RETRY_JITTER_MS + 1);
            Thread.sleep(timeout);
        } catch (InterruptedException ignored) {}
        synchronized (lock) {
            if (!proposing || activeProposalId != proposalId || learner.isDecided()) return;
            if (acceptedBy.size() >= config.majority()) return;
            Logger.warn(memberId + " retrying proposal due to timeout n=" + proposalId);
        }
        initiateProposal(activeCandidate);
    }

    private void broadcastPrepare(int proposalId) {
        Message m = Message.prepare(memberId, proposalId);
        client.broadcastMessage(memberId, m, false);
    }

    private void broadcastAcceptRequest(int proposalId, String value) {
        Message m = Message.acceptRequest(memberId, proposalId, value);
        client.broadcastMessage(memberId, m, false);
    }

    void handlePromise(Message m) {
        synchronized (lock) {
            if (!proposing || learner.isDecided()) return;
            if (!m.proposalID.equals(activeProposalId)) return;
            promises.put(m.senderID, new PromiseInfo(m.acceptedID, m.acceptedValue));
            Logger.info(memberId + " PROMISES count=" + promises.size() + "/" + config.size() +
                    " maj=" + config.majority() + " for n=" + activeProposalId);
            if (promises.size() >= config.majority()) {
                String valueToPropose = activeCandidate;
                int highest = -1;
                for (PromiseInfo pi : promises.values()) {
                    if (pi.acceptedId != null && pi.acceptedId > highest) {
                        highest = pi.acceptedId;
                        valueToPropose = pi.acceptedValue;
                    }
                }
                if (!acceptRequested) {
                    acceptRequested = true;
                    Logger.info(memberId + " sending ACCEPT_REQUEST n=" + activeProposalId + " v=" + valueToPropose);
                    broadcastAcceptRequest(activeProposalId, valueToPropose);
                }
            }
        }
    }

    void handleAccepted(Message m) {
        synchronized (lock) {
            if (m.proposalID != null && m.proposalID.equals(activeProposalId) && m.proposalVal != null) {
                acceptedBy.add(m.senderID);
            }
        }
        learner.handleAccepted(m);
    }
}


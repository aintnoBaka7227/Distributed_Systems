package org;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Proposer:
 * Drives Paxos Phase 1 (PREPARE/PROMISE) and Phase 2 (ACCEPT_REQUEST)
 * Handles retries with backoff/jitter, quorum counting, and value selection per Paxos rule:
 * If any PROMISE carries a previously accepted value, propose the one with highest acceptedId
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

    // Current proposal context
    private int proposalSeq = 0;
    private volatile boolean proposing = false;
    private int activeProposalId = -1;
    private String activeCandidate = null;

    // Concurrency guard for proposer state
    private final Object lock = new Object();

    // track all promises arrive for phase 1
    private final Map<String, PromiseInfo> promises = new HashMap<>();
    // track all accepteds arrive for phase 2
    private final Set<String> acceptedBy = new HashSet<>();

    // Phase 2 transition switch, use for handle recovery Proposer
    private boolean acceptRequested = false;
    private String phase2Value = null;

    private static class PromiseInfo {
        Integer acceptedId; String acceptedValue;
        PromiseInfo(Integer id, String val) { this.acceptedId = id; this.acceptedValue = val; }
    }

    /**
     * Wires proposer with network/config/profile and the shared learner.
     */
    Proposer(String memberId, int memberNumericId, NetworkConfig config, NetworkClient client, MemberProfile profile, Learner learner) {
        this.memberId = memberId;
        this.memberNumericId = memberNumericId;
        this.config = config;
        this.client = client;
        this.profile = profile;
        this.learner = learner;
    }

    // State exposure for snapshots/diagnostics
    boolean isProposing() { synchronized (lock) { return proposing; } }
    int getActiveProposalId() { synchronized (lock) { return activeProposalId; } }
    String getActiveCandidate() { synchronized (lock) { return activeCandidate; } }
    int getPromiseCount() { synchronized (lock) { return promises.size(); } }
    int getAcceptedByCount() { synchronized (lock) { return acceptedBy.size(); } }

    /**
     * initiateProposal:
     * Starts a new Paxos attempt for the given candidate value.
     * Increments local round and forms a globally ordered proposal ID.
     * Broadcasts PREPARE and set a retry timer.
     */
    void initiateProposal(String candidate) {
        if (learner.isDecided()) {
            System.out.println("Already decided: " + learner.getDecidedValue());
            return;
        }
        synchronized (lock) {
            proposalSeq++;
            activeProposalId = PaxosNode.makeMonotonicProposalId(proposalSeq, memberNumericId);
            activeCandidate = candidate;
            proposing = true;
            promises.clear();
            acceptedBy.clear();
            acceptRequested = false;
            phase2Value = null;
        }
        Logger.info(memberId + " starting PREPARE n=" + activeProposalId + " v=" + candidate);
        broadcastPrepare(activeProposalId);
        new Thread(() -> proposalRetry(activeProposalId), "proposalRetry-" + activeProposalId).start();
    }

    /**
     * proposalRetry:
     * Timeout/retry logic:
     * If Phase 2 is in progress but quorum not reached, re-send ACCEPT_REQUEST with same n,v.
     * If Phase 1 stalled (no majority PROMISE), increase proposal number and restart PREPARE.
     * Backoff duration scales with simulated network latency and adds jitter.
     */
    private void proposalRetry(int proposalId) {
        try {
            int timeout = Math.max(RETRY_BASE_MS, profile.getMaxLatencyMs() * 2 + 500);
            timeout += rnd.nextInt(RETRY_JITTER_MS + 1);
            Thread.sleep(timeout);
        } catch (InterruptedException ignored) {}
        synchronized (lock) {
            if (!proposing || activeProposalId != proposalId || learner.isDecided()) return;
            if (acceptedBy.size() >= config.majority()) return;

            if (acceptRequested) {
                // Phase 2 retry: same proposal number/value, idempotent
                Logger.warn(memberId + " resending ACCEPT_REQUEST due to timeout n=" + proposalId + " v=" + phase2Value);
                broadcastAcceptRequest(activeProposalId, phase2Value);
                new Thread(() -> proposalRetry(proposalId), "proposalRetry-" + proposalId).start();
                return;
            }

            // Phase 1 retry: escalate proposal number to outrank competing proposers
            Logger.warn(memberId + " retrying Phase1; bumping proposal from n=" + proposalId);
            proposalSeq++;
            activeProposalId = PaxosNode.makeMonotonicProposalId(proposalSeq, memberNumericId);
            promises.clear();
            acceptedBy.clear();
            acceptRequested = false;
            phase2Value = null;
        }
        Logger.info(memberId + " starting PREPARE n=" + activeProposalId + " v=" + activeCandidate);
        broadcastPrepare(activeProposalId);
        new Thread(() -> proposalRetry(activeProposalId), "proposalRetry-" + activeProposalId).start();
    }

    /**
     * broadcastPrepare:
     * Sends PREPARE(n) to all peers (Phase 1)
     */
    private void broadcastPrepare(int proposalId) {
        Message m = Message.prepare(memberId, proposalId);
        client.broadcastMessage(memberId, m, false);
    }

    /**
     * broadcastAcceptRequest:
     * Sends ACCEPT_REQUEST(n, v) to all peers (Phase 2)
     */
    private void broadcastAcceptRequest(int proposalId, String value) {
        Message m = Message.acceptRequest(memberId, proposalId, value);
        client.broadcastMessage(memberId, m, false);
    }

    /**
     * handlePromise:
     * Track promises per sender
     * When quorum reached, choose value:
     * If any promise carries a prior accepted value, pick the one with highest acceptedId
     * Otherwise propose our candidate
     * Send ACCEPT_REQUEST once (guarded by acceptRequested)
     */
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
                    phase2Value = valueToPropose;
                    Logger.info(memberId + " sending ACCEPT_REQUEST n=" + activeProposalId + " v=" + phase2Value);
                    broadcastAcceptRequest(activeProposalId, phase2Value);
                }
            }
        }
    }

    /**
     * handleAccepted:
     * Tracks accepted for the active proposal and forwards to the Learner
     * so it can detect quorum and mark decision. Stops proposing when decision observed
     */
    void handleAccepted(Message m) {
        synchronized (lock) {
            if (m.proposalID != null && m.proposalID.equals(activeProposalId) && m.proposalVal != null) {
                acceptedBy.add(m.senderID);
            }
        }
        learner.handleAccepted(m);
        if (learner.isDecided()) {
            synchronized (lock) { proposing = false; }
        }
    }
}

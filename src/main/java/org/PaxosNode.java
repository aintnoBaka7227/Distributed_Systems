package org;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Implements Paxos roles (Proposer, Acceptor, Learner) for a single-decree election.
 * Each member can initiate proposals and participates as acceptor/learner.
 */
public class PaxosNode {
    private static final int PROPOSAL_MULTIPLIER = 100;
    private static final int WATCHDOG_BASE_MS = 2000;
    private static final int WATCHDOG_JITTER_MS = 1000;
    private final String memberId;
    private final int memberNumericId;
    private final NetworkConfig config;
    private final NetworkClient client;
    private final MemberProfile profile;
    private final Random rnd = new Random();

    // Acceptor state
    private int highestPromised = -1;
    private Integer acceptedId = null;
    private String acceptedValue = null;

    // Learner state
    private volatile boolean decided = false;
    private volatile String decidedValue = null;
    // Track ACCEPTED counts per (proposalId,value)
    private final Map<String, Set<String>> learnerAccepts = new HashMap<>();

    // Proposer state
    private int proposalSeq = 0;
    private volatile boolean proposing = false;
    private int activeProposalId = -1;
    private String activeCandidate = null;
    private final Object proposerLock = new Object();
    private final Map<String, PromiseInfo> promises = new HashMap<>();
    private final Set<String> acceptedBy = new HashSet<>();
    private boolean acceptRequested = false;

    private static class PromiseInfo {
        Integer acceptedId;
        String acceptedValue;
        PromiseInfo(Integer id, String val) { this.acceptedId = id; this.acceptedValue = val; }
    }

    public PaxosNode(String memberId, NetworkConfig config, NetworkClient client, MemberProfile profile) {
        this.memberId = memberId;
        this.memberNumericId = parseMemberNumericId(memberId);
        this.config = config;
        this.client = client;
        this.profile = profile;
    }

    /**
     * Initiate a proposal for a candidate value. Safe to call repeatedly; if a proposal is in-flight,
     * this will attempt to start a new round with a higher proposal number.
     * @param candidate candidate value (e.g., M5)
     */
    public void initiateProposal(String candidate) {
        if (decided) {
            System.out.println("Already decided: " + decidedValue);
            return;
        }
        synchronized (proposerLock) {
            proposalSeq++;
            activeProposalId = makeProposalId(proposalSeq, memberNumericId);
            activeCandidate = candidate;
            proposing = true;
            promises.clear();
            acceptedBy.clear();
            acceptRequested = false;
        }
        Logger.info(memberId + " starting PREPARE n=" + activeProposalId + " v=" + candidate);
        broadcastPrepare(activeProposalId);

        // Fire a timer thread to check progress and possibly retry
        new Thread(() -> proposalWatchdog(activeProposalId), "Watchdog-" + activeProposalId).start();
    }

    private void proposalWatchdog(int proposalId) {
        try {
            // Dynamic timeout heuristic based on profile latency range + small jitter
            int timeout = Math.max(WATCHDOG_BASE_MS, profile.getMaxLatencyMs() * 2 + 500);
            timeout += rnd.nextInt(WATCHDOG_JITTER_MS + 1);
            Thread.sleep(timeout);
        } catch (InterruptedException ignored) {}
        synchronized (proposerLock) {
            if (!proposing || activeProposalId != proposalId || decided) return;
            if (acceptedBy.size() >= config.majority()) return; // already in accept phase or decided
            Logger.warn(memberId + " retrying proposal due to timeout n=" + proposalId);
        }
        // Retry with higher proposal
        initiateProposal(activeCandidate);
    }

    private void broadcastPrepare(int proposalId) {
        Message m = Message.prepare(memberId, proposalId);
        client.broadcast(memberId, m, false);
    }

    private void broadcastAcceptRequest(int proposalId, String value) {
        Message m = Message.acceptRequest(memberId, proposalId, value);
        client.broadcast(memberId, m, false);
    }

    /**
     * Handle an inbound message from the server.
     * @param m message
     */
    public void onMessage(Message m) {
        switch (m.type) {
            case PREPARE: handlePrepare(m); break;
            case PROMISE: handlePromise(m); break;
            case ACCEPT_REQUEST: handleAcceptRequest(m); break;
            case ACCEPTED: handleAccepted(m); break;
        }
    }

    // Acceptor role handlers
    private synchronized void handlePrepare(Message m) {
        if (decided) return;
        int n = m.proposalId == null ? -1 : m.proposalId;
        if (n > highestPromised) {
            highestPromised = n;
            Message promise = Message.promise(memberId, n, acceptedId, acceptedValue);
            client.send(m.fromId, promise);
        } else {
            // ignore lower proposals as per Paxos (we could optionally NACK)
        }
    }

    private synchronized void handleAcceptRequest(Message m) {
        if (decided) return;
        int n = m.proposalId == null ? -1 : m.proposalId;
        if (n >= highestPromised) {
            highestPromised = n; // promise not to accept lower
            acceptedId = n;
            acceptedValue = m.value;
            Message acc = Message.accepted(memberId, n, acceptedValue);
            // Broadcast ACCEPTED to all learners (no DECIDE path)
            client.broadcast(memberId, acc, true); // exclude self; record locally below
            // Record local acceptance for learner aggregation
            recordLearnerAcceptance(n, acceptedValue, memberId);
        } else {
            // reject implicitly
        }
    }

    // Proposer role handlers
    private void handlePromise(Message m) {
        synchronized (proposerLock) {
            if (!proposing || decided) return;
            if (!m.proposalId.equals(activeProposalId)) return; // different round
            promises.put(m.fromId, new PromiseInfo(m.acceptedId, m.acceptedValue));
            Logger.info(memberId + " PROMISES count=" + promises.size() + "/" + config.size() +
                    " maj=" + config.majority() + " for n=" + activeProposalId);
            if (promises.size() >= config.majority()) {
                // choose value: if any promised includes accepted, pick highest acceptedId
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
                // do not clear promises; continue collecting ACCEPTED
            }
        }
    }

    private void handleAccepted(Message m) {
        synchronized (proposerLock) {
            if (!m.proposalId.equals(activeProposalId)) {
                // still update learner aggregation even if not the active proposal
                if (m.value != null && m.proposalId != null) {
                    recordLearnerAcceptance(m.proposalId, m.value, m.fromId);
                }
                return;
            }
            if (m.value == null) return;
            // Proposer bookkeeping to suppress timeouts
            acceptedBy.add(m.fromId);
            // Learner aggregation for any ACCEPTED
            recordLearnerAcceptance(m.proposalId, m.value, m.fromId);
        }
    }

    // Learner aggregation: count ACCEPTED per (proposalId,value)
    private synchronized void recordLearnerAcceptance(Integer proposalId, String value, String acceptorId) {
        if (proposalId == null || value == null) return;
        if (decided) return;
        String key = proposalId + "|" + value;
        Set<String> set = learnerAccepts.computeIfAbsent(key, k -> new HashSet<>());
        set.add(acceptorId);
        int count = set.size();
        Logger.info(memberId + " LEARN count=" + count + "/" + config.majority() + " for n=" + proposalId + " v=" + value);
        if (count >= config.majority() && !decided) {
            decided = true;
            decidedValue = value;
            proposing = false;
            Logger.consensus(decidedValue);
            System.out.println("CONSENSUS: " + decidedValue + " has been elected Council President!");
        }
    }

    /**
     * Get a one-line state snapshot for debugging via admin 'state' command.
     * @return snapshot string
     */
    public synchronized String getStateSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(memberId)
          .append(" decided=").append(decided)
          .append(decided ? (" value=" + decidedValue) : "")
          .append(" highestPromised=").append(highestPromised)
          .append(" acceptedId=").append(acceptedId)
          .append(" acceptedValue=").append(acceptedValue)
          .append(" proposing=").append(proposing)
          .append(" activeN=").append(activeProposalId)
          .append(" candidate=").append(activeCandidate)
          .append(" promiseCount=").append(promises.size())
          .append(" acceptedBy=").append(acceptedBy.size());
        return sb.toString();
    }

    private static int parseMemberNumericId(String memberId) {
        try {
            if (memberId != null && memberId.length() >= 2 && (memberId.charAt(0) == 'M' || memberId.charAt(0) == 'm')) {
                return Integer.parseInt(memberId.substring(1));
            }
        } catch (NumberFormatException ignored) {}
        // fallback hash
        return Math.abs(memberId.hashCode() % 100) + 1;
    }

    private static int makeProposalId(int seq, int memberNumericId) {
        // encode as seq*100 + memberId to ensure total order among proposers
        return seq * PROPOSAL_MULTIPLIER + memberNumericId;
    }
}

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

    // Role instances
    private final Proposer proposer;
    private final Acceptor acceptor;
    private final Learner learner;

    public PaxosNode(String memberId, NetworkConfig config, NetworkClient client, MemberProfile profile) {
        this.memberId = memberId;
        this.memberNumericId = parseMemberNumericId(memberId);
        this.config = config;
        this.client = client;
        this.profile = profile;
        this.learner = new Learner();
        this.acceptor = new Acceptor();
        this.proposer = new Proposer();
    }

    // ============ Proposer ============
    private class Proposer {
        private int proposalSeq = 0;
        private volatile boolean proposing = false;
        private int activeProposalId = -1;
        private String activeCandidate = null;
        private final Object lock = new Object();
        private final Map<String, PromiseInfo> promises = new HashMap<>();
        private final Set<String> acceptedBy = new HashSet<>();
        private boolean acceptRequested = false;

        private class PromiseInfo {
            Integer acceptedId; String acceptedValue;
            PromiseInfo(Integer id, String val) { this.acceptedId = id; this.acceptedValue = val; }
        }

        void initiateProposal(String candidate) {
            if (learner.decided) {
                System.out.println("Already decided: " + learner.decidedValue);
                return;
            }
            synchronized (lock) {
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
            new Thread(() -> proposalWatchdog(activeProposalId), "Watchdog-" + activeProposalId).start();
        }

        private void proposalWatchdog(int proposalId) {
            try {
                int timeout = Math.max(WATCHDOG_BASE_MS, profile.getMaxLatencyMs() * 2 + 500);
                timeout += rnd.nextInt(WATCHDOG_JITTER_MS + 1);
                Thread.sleep(timeout);
            } catch (InterruptedException ignored) {}
            synchronized (lock) {
                if (!proposing || activeProposalId != proposalId || learner.decided) return;
                if (acceptedBy.size() >= config.majority()) return;
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

        void onPromise(Message m) {
            synchronized (lock) {
                if (!proposing || learner.decided) return;
                if (!m.proposalId.equals(activeProposalId)) return;
                promises.put(m.fromId, new PromiseInfo(m.acceptedId, m.acceptedValue));
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

        void onAccepted(Message m) {
            synchronized (lock) {
                if (m.proposalId != null && m.proposalId.equals(activeProposalId) && m.value != null) {
                    acceptedBy.add(m.fromId);
                }
            }
            // Always inform learner
            learner.onAccepted(m);
        }
    }

    // ============ Acceptor ============
    private class Acceptor {
        private int highestPromised = -1;
        private Integer acceptedId = null;
        private String acceptedValue = null;

        synchronized void onPrepare(Message m) {
            if (learner.decided) return;
            int n = m.proposalId == null ? -1 : m.proposalId;
            if (n > highestPromised) {
                highestPromised = n;
                Message promise = Message.promise(memberId, n, acceptedId, acceptedValue);
                client.send(m.fromId, promise);
            } else {
                // ignore lower proposals
            }
        }

        synchronized void onAcceptRequest(Message m) {
            if (learner.decided) return;
            int n = m.proposalId == null ? -1 : m.proposalId;
            if (n >= highestPromised) {
                highestPromised = n;
                acceptedId = n;
                acceptedValue = m.value;
                Message acc = Message.accepted(memberId, n, acceptedValue);
                client.broadcast(memberId, acc, true);
                learner.recordAcceptance(n, acceptedValue, memberId);
            } else {
                // reject implicitly
            }
        }
    }

    // ============ Learner ============
    private class Learner {
        private volatile boolean decided = false;
        private volatile String decidedValue = null;
        private final Map<String, Set<String>> accepts = new HashMap<>();

        synchronized void onAccepted(Message m) {
            if (m.proposalId == null || m.value == null) return;
            recordAcceptance(m.proposalId, m.value, m.fromId);
        }

        synchronized void recordAcceptance(Integer proposalId, String value, String acceptorId) {
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

    /** Route inbound messages to roles. */
    public void onMessage(Message m) {
        switch (m.type) {
            case PREPARE: acceptor.onPrepare(m); break;
            case PROMISE: proposer.onPromise(m); break;
            case ACCEPT_REQUEST: acceptor.onAcceptRequest(m); break;
            case ACCEPTED: proposer.onAccepted(m); break;
        }
    }

    /** Initiate a new proposal via proposer role. */
    public void initiateProposal(String candidate) {
        proposer.initiateProposal(candidate);
    }

    /** State snapshot for admin debugging. */
    public String getStateSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(memberId)
          .append(" decided=").append(learner.decided)
          .append(learner.decided ? (" value=" + learner.decidedValue) : "")
          .append(" highestPromised=").append(acceptor.highestPromised)
          .append(" acceptedId=").append(acceptor.acceptedId)
          .append(" acceptedValue=").append(acceptor.acceptedValue);
        synchronized (proposer.lock) {
            sb.append(" proposing=").append(proposer.proposing)
              .append(" activeN=").append(proposer.activeProposalId)
              .append(" candidate=").append(proposer.activeCandidate)
              .append(" promiseCount=").append(proposer.promises.size())
              .append(" acceptedBy=").append(proposer.acceptedBy.size());
        }
        return sb.toString();
    }

    private static int parseMemberNumericId(String memberId) {
        try {
            if (memberId != null && memberId.length() >= 2 && (memberId.charAt(0) == 'M' || memberId.charAt(0) == 'm')) {
                return Integer.parseInt(memberId.substring(1));
            }
        } catch (NumberFormatException ignored) {}
        return Math.abs(memberId.hashCode() % 100) + 1;
    }

    private static int makeProposalId(int seq, int memberNumericId) {
        return seq * PROPOSAL_MULTIPLIER + memberNumericId;
    }
}

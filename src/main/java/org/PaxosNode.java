package org;

/**
 * PaxosNode:
 * Wires together the three Paxos roles (Proposer, Acceptor, Learner) for a single-decree election
 * Routes inbound messages to the appropriate role
 */
public class PaxosNode {
    // Construct monotonic id, addtional space with 100 slots for safetiness
    private static final int PROPOSAL_MULTIPLIER = 100;

    private final String memberId;
    // Extract numberic value from member id
    private final int memberNumericId;
    // Store this paxos member profile
    private final MemberProfile profile;

    // Role instances
    private final Proposer proposer;
    private final Acceptor acceptor;
    private final Learner learner;

    /**
     * Constructs a node and instantiates all three Paxos roles sharing the same network client/profile
     * The Learner is shared with the Acceptor so ACCEPTED events can be tallied immediately
     */
    public PaxosNode(String memberId, NetworkConfig config, NetworkClient client, MemberProfile profile) {
        this.memberId = memberId;
        this.memberNumericId = parseMemberNumericId(memberId);
        this.profile = profile;
        this.learner = new Learner(memberId, config);
        this.acceptor = new Acceptor(memberId, client, learner);
        this.proposer = new Proposer(memberId, memberNumericId, config, client, profile, learner);
    }

    
    public Learner getLearner() {
        return learner;
    }

    /**
     * handleMessage:
     * Demultiplexes inbound protocol messages to the correct role handler.
     */
    public void handleMessage(Message m) {
        switch (m.type) {
            case PREPARE:        acceptor.handlePrepare(m);        break; // Phase 1b
            case PROMISE:        proposer.handlePromise(m);        break; // Proposer collects promises
            case ACCEPT_REQUEST: acceptor.handleAcceptRequest(m);  break; // Phase 2b
            case ACCEPTED:       proposer.handleAccepted(m);       break; // Proposer observes quorum progress
        }
    }

    /** Starts a fresh proposal flow (Proposer drives Phase 1 then Phase 2). */
    public void initiateProposal(String candidate) {
        proposer.initiateProposal(candidate);
    }

    /**
     * getStateSnapshot:
     * Builds a string aggregating role state and profile.
     */
    public String getStateSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(memberId)
          .append(" decided=").append(learner.isDecided())
          .append(learner.isDecided() ? (" value=" + learner.getDecidedValue()) : "")
          .append(" profile=").append(profile.getLatencyType())
          .append(" latency=").append(profile.getMinLatencyMs()).append("-").append(profile.getMaxLatencyMs()).append("ms")
          .append(" drop=").append(profile.getDropRate())
          .append(" highestPromised=").append(acceptor.getHighestPromised())
          .append(" acceptedId=").append(acceptor.getAcceptedId())
          .append(" acceptedValue=").append(acceptor.getAcceptedValue())
          .append(" proposing=").append(proposer.isProposing())
          .append(" activeN=").append(proposer.getActiveProposalId())
          .append(" candidate=").append(proposer.getActiveCandidate())
          .append(" promiseCount=").append(proposer.getPromiseCount())
          .append(" acceptedBy=").append(proposer.getAcceptedByCount());
        return sb.toString();
    }

    /**
     * parseMemberNumericId:
     * Extracts the numeric suffix from IDs like "M7".
     */
    private static int parseMemberNumericId(String memberId) {
        try {
            if (memberId != null && memberId.length() >= 2 &&
                (memberId.charAt(0) == 'M' || memberId.charAt(0) == 'm')) {
                return Integer.parseInt(memberId.substring(1));
            }
        } catch (NumberFormatException ignored) {}
        return Math.abs(memberId.hashCode() % 100) + 1;
    }

    /**
     * makeMonotonicProposalId:
     * Produces a globally comparable proposal number embedding (seq, memberId)
     * Formula: proposal = seq * PROPOSAL_MULTIPLIER + memberNumericId
     * Ensures uniqueness and monotonic across members for the same round
     * Use for tie-breaking
     */
    static int makeMonotonicProposalId(int seq, int memberNumericId) {
        return seq * PROPOSAL_MULTIPLIER + memberNumericId;
    }
}


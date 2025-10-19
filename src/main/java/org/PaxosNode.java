package org;

/**
 * Implements Paxos roles (Proposer, Acceptor, Learner) for a single-decree election.
 * Each member can initiate proposals and participates as acceptor/learner.
 */
public class PaxosNode {
    private static final int PROPOSAL_MULTIPLIER = 100;
    private final String memberId;
    private final int memberNumericId;
    // Role instances
    private final Proposer proposer;
    private final Acceptor acceptor;
    private final Learner learner;

    public PaxosNode(String memberId, NetworkConfig config, NetworkClient client, MemberProfile profile) {
        this.memberId = memberId;
        this.memberNumericId = parseMemberNumericId(memberId);
        this.learner = new Learner(memberId, config);
        this.acceptor = new Acceptor(memberId, client, learner);
        this.proposer = new Proposer(memberId, memberNumericId, config, client, profile, learner);
    }

    /** Route inbound messages to roles. */
    public void onMessage(Message m) {
        switch (m.type) {
            case PREPARE: acceptor.handlePrepare(m); break;
            case PROMISE: proposer.handlePromise(m); break;
            case ACCEPT_REQUEST: acceptor.handleAcceptRequest(m); break;
            case ACCEPTED: proposer.handleAccepted(m); break;
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
          .append(" decided=").append(learner.isDecided())
          .append(learner.isDecided() ? (" value=" + learner.getDecidedValue()) : "")
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

    private static int parseMemberNumericId(String memberId) {
        try {
            if (memberId != null && memberId.length() >= 2 && (memberId.charAt(0) == 'M' || memberId.charAt(0) == 'm')) {
                return Integer.parseInt(memberId.substring(1));
            }
        } catch (NumberFormatException ignored) {}
        return Math.abs(memberId.hashCode() % 100) + 1;
    }

    static int makeProposalId(int seq, int memberNumericId) {
        return seq * PROPOSAL_MULTIPLIER + memberNumericId;
    }
}

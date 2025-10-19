package org;

/**
 * Acceptor:
 * Implements Paxos Acceptor behavior
 * Responsibilities:
 * handlePrepare: never accept proposals < highestPromised to form promise
 * handleAcceptRequest: accept value for proposal n when n >= highestPromised; If winner existed,
 * only re-accept if the incoming value matches the decided value (additional implementation for crash member recovery)
 */
class Acceptor {
    // Member Id
    private final String memberId;
    // Outbound network client to broadcast messages
    private final NetworkClient client;
    // Learner to track accepted messages and query winner
    private final Learner learner;

    // Initalize highest promised to -1 -> fresh start
    private int highestPromised = -1;
    // track last acceptedID
    private Integer acceptedId = null;
    // track last accepted value
    private String acceptedValue = null;

    Acceptor(String memberId, NetworkClient client, Learner learner) {
        this.memberId = memberId;
        this.client = client;
        this.learner = learner;
    }

    /** @return current highest promised proposal number */
    int getHighestPromised() { return highestPromised; }

    /** @return last accepted proposal number */
    Integer getAcceptedId() { return acceptedId; }

    /** @return last accepted value */
    String getAcceptedValue() { return acceptedValue; }

    /**
     * handlePrepare:
     * Paxos Phase 1 — receiving PREPARE(n):
     * Not to accept proposals < n, and reply with PROMISE including any prior accepted (acceptedId, acceptedValue)
     * Else: ignore
     * Note: Allow PREPARE even after round finished to enable recovery; safety preserved by promise rule
     */
    synchronized void handlePrepare(Message m) {
        int n = m.proposalID == null ? -1 : m.proposalID;

        // Only advance promise if strictly greater
        if (n > highestPromised) {
            highestPromised = n;

            // Send promise so proposer can pick the highest previously-accepted value
            Message promise = Message.promise(memberId, n, acceptedId, acceptedValue);
            client.sendMessage(m.senderID, promise);
        }
    }

    /**
     * handleAcceptRequest:
     * Paxos Phase 2 — receiving ACCEPT_REQUEST(n, v):
     * learner has already decided a value:
     * Only reaccept if v equals the decided value and n >= highestPromised (for crash recovery)
     * Broadcast ACCEPTED to help late learners converge
     * Else (not yet decided):
     * If n >= highestPromised: record (n, v) as accepted, set highestPromised = n, and broadcast ACCEPTED
     * Else: ignore (must not accept lower than promised)
     */
    synchronized void handleAcceptRequest(Message m) {
        // Normalize proposal ID
        int n = (m.proposalID == null) ? -1 : m.proposalID;

        // If the system has already chosen a value, only reinforce matching accepts to maintain safety
        if (learner.isDecided()) {
            String chosen = learner.getDecidedValue();

            // Accept only if the proposed value equals the chosen value and the proposal number is not lower than promised
            if (chosen != null && chosen.equals(m.proposalVal) && n >= highestPromised) {
                highestPromised = n;
                acceptedId = n;
                // Lock acceptance to the decided value
                acceptedValue = chosen;

                // Broadcast ACCEPTED so crashed/lagging learners/acceptors converge
                Message acc = Message.accepted(memberId, n, acceptedValue);
                client.broadcastMessage(memberId, acc, true);
                learner.trackAccepts(n, acceptedValue, memberId);
            }
            return;
        }

        // Accept when n >= highestPromised
        if (n >= highestPromised) {
            highestPromised = n;
            acceptedId = n;
            acceptedValue = m.proposalVal;

            // Notify cluster that this acceptor accepted (n, v), enables learner quorum counting
            Message acc = Message.accepted(memberId, n, acceptedValue);
            client.broadcastMessage(memberId, acc, true);
            learner.trackAccepts(n, acceptedValue, memberId);
        }
    }
}

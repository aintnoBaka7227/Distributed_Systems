package org;

/**
 * Acceptor role: responds to PREPARE and ACCEPT_REQUEST.
 */
class Acceptor {
    private final String memberId;
    private final NetworkClient client;
    private final Learner learner;
    private int highestPromised = -1;
    private Integer acceptedId = null;
    private String acceptedValue = null;

    Acceptor(String memberId, NetworkClient client, Learner learner) {
        this.memberId = memberId;
        this.client = client;
        this.learner = learner;
    }

    int getHighestPromised() { return highestPromised;}
    Integer getAcceptedId() { return acceptedId; }
    String getAcceptedValue() { return acceptedValue; }

    synchronized void handlePrepare(Message m) {
        if (learner.isDecided()) return;
        int n = m.proposalID == null ? -1 : m.proposalID;
        if (n > highestPromised) {
            highestPromised = n;
            Message promise = Message.promise(memberId, n, acceptedId, acceptedValue);
            client.sendMessage(m.senderID, promise);
        }
    }

    synchronized void handleAcceptRequest(Message m) {
        if (learner.isDecided()) return;
        int n = m.proposalID == null ? -1 : m.proposalID;
        if (n >= highestPromised) {
            highestPromised = n;
            acceptedId = n;
            acceptedValue = m.proposalVal;
            Message acc = Message.accepted(memberId, n, acceptedValue);
            client.broadcastMessage(memberId, acc, true);
            learner.trackAccepts(n, acceptedValue, memberId);
        }
    }
}


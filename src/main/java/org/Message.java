package org;

/**
 * Message types used in Paxos protocol.
 * Kept package-private and colocated with Message for simplicity.
 */
enum MessageType {
    PREPARE,
    PROMISE,
    ACCEPT_REQUEST,
    ACCEPTED
}

/**
 * Simple text-based message with key fields for Paxos.
 * Encoded as colon-delimited: TYPE:senderID:proposalID:proposalVal:acceptedID:acceptedValue
 */
public class Message {
    public MessageType type;
    public String senderID;
    public Integer proposalID; // may be null in some control messages
    public String proposalVal;    // candidate or decided value
    public Integer acceptedID; // for PROMISE responses
    public String acceptedValue; // for PROMISE responses

    public Message() {}

    public static Message prepare(String senderID, int proposalID) {
        Message m = new Message();
        m.type = MessageType.PREPARE;
        m.senderID = senderID;
        m.proposalID = proposalID;
        return m;
    }

    public static Message promise(String senderID, int proposalID, Integer acceptedId, String acceptedValue) {
        Message m = new Message();
        m.type = MessageType.PROMISE;
        m.senderID = senderID;
        m.proposalID = proposalID;
        m.acceptedID = acceptedId;
        m.acceptedValue = acceptedValue;
        return m;
    }

    public static Message acceptRequest(String senderID, int proposalID, String proposalVal) {
        Message m = new Message();
        m.type = MessageType.ACCEPT_REQUEST;
        m.senderID = senderID;
        m.proposalID = proposalID;
        m.proposalVal = proposalVal;
        return m;
    }

    public static Message accepted(String senderID, int proposalID, String proposalVal) {
        Message m = new Message();
        m.type = MessageType.ACCEPTED;
        m.senderID = senderID;
        m.proposalID = proposalID;
        m.proposalVal = proposalVal;
        return m;
    }

    // No DECIDE message in this variant; learners aggregate ACCEPTED quorum.

    /**
     * Encode to a single line for sending over TCP.
     * @return encoded string (colon-delimited)
     */
    public String constructMessage() {
        String t = type.name();
        String pid = proposalID == null ? "" : Integer.toString(proposalID);
        String v = proposalVal == null ? "" : proposalVal;
        String aid = acceptedID == null ? "" : Integer.toString(acceptedID);
        String av = acceptedValue == null ? "" : acceptedValue;
        return String.join(":", t, senderID, pid, v, aid, av);
    }

    /**
     * Decode from a line of text.
     * @param line encoded string (colon-delimited)
     * @return message instance
     */
    public static Message parseMessage(String line) {
        String[] parts = line.split(":", -1);
        if (parts.length < 2) throw new IllegalArgumentException("Bad message: " + line);
        Message m = new Message();
        m.type = MessageType.valueOf(parts[0]);
        m.senderID = parts[1];
        m.proposalID = parseIntOrNull(parts.length > 2 ? parts[2] : null);
        m.proposalVal = parts.length > 3 && !parts[3].isEmpty() ? parts[3] : null;
        m.acceptedID = parseIntOrNull(parts.length > 4 ? parts[4] : null);
        m.acceptedValue = parts.length > 5 && !parts[5].isEmpty() ? parts[5] : null;
        return m;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        return Integer.parseInt(s);
    }
}

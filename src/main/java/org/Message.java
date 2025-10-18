package org;

/**
 * Simple text-based message with key fields for Paxos.
 * Encoded as pipe-delimited: TYPE|from|proposalId|value|accId|accVal
 */
public class Message {
    public MessageType type;
    public String fromId;
    public Integer proposalId; // may be null in some control messages
    public String value;    // candidate or decided value
    public Integer acceptedId; // for PROMISE responses
    public String acceptedValue; // for PROMISE responses

    public Message() {}

    public static Message prepare(String fromId, int proposalId) {
        Message m = new Message();
        m.type = MessageType.PREPARE;
        m.fromId = fromId;
        m.proposalId = proposalId;
        return m;
    }

    public static Message promise(String fromId, int proposalId, Integer acceptedId, String acceptedValue) {
        Message m = new Message();
        m.type = MessageType.PROMISE;
        m.fromId = fromId;
        m.proposalId = proposalId;
        m.acceptedId = acceptedId;
        m.acceptedValue = acceptedValue;
        return m;
    }

    public static Message acceptRequest(String fromId, int proposalId, String value) {
        Message m = new Message();
        m.type = MessageType.ACCEPT_REQUEST;
        m.fromId = fromId;
        m.proposalId = proposalId;
        m.value = value;
        return m;
    }

    public static Message accepted(String fromId, int proposalId, String value) {
        Message m = new Message();
        m.type = MessageType.ACCEPTED;
        m.fromId = fromId;
        m.proposalId = proposalId;
        m.value = value;
        return m;
    }

    // No DECIDE message in this variant; learners aggregate ACCEPTED quorum.

    /**
     * Encode to a single line for sending over TCP.
     * @return encoded string
     */
    public String encode() {
        String t = type.name();
        String pid = proposalId == null ? "" : Integer.toString(proposalId);
        String v = value == null ? "" : value;
        String aid = acceptedId == null ? "" : Integer.toString(acceptedId);
        String av = acceptedValue == null ? "" : acceptedValue;
        return String.join("|", t, fromId, pid, v, aid, av);
    }

    /**
     * Decode from a line of text.
     * @param line encoded string
     * @return message instance
     */
    public static Message decode(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 2) throw new IllegalArgumentException("Bad message: " + line);
        Message m = new Message();
        m.type = MessageType.valueOf(parts[0]);
        m.fromId = parts[1];
        m.proposalId = parseIntOrNull(parts.length > 2 ? parts[2] : null);
        m.value = parts.length > 3 && !parts[3].isEmpty() ? parts[3] : null;
        m.acceptedId = parseIntOrNull(parts.length > 4 ? parts[4] : null);
        m.acceptedValue = parts.length > 5 && !parts[5].isEmpty() ? parts[5] : null;
        return m;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        return Integer.parseInt(s);
    }
}

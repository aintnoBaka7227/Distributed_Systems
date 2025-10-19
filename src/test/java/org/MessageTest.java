package org;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageTest:
 * Ensures the Message serialization/deserialization is symmetric and robust
 * Test PREPARE, PROMISE, ACCEPT_REQUEST, ACCEPTED, minimal forms, and invalid inputs
 */
public class MessageTest {

    /** PREPARE is serialized and parsed back with correct mandatory/optional fields */
    @Test
    void constructAndParsePrepare() {
        Message m = Message.prepare("M1", 101);
        String line = m.constructMessage();
        Message parsed = Message.parseMessage(line);

        assertEquals(MessageType.PREPARE, parsed.type);
        assertEquals("M1", parsed.senderID);
        assertEquals(101, parsed.proposalID);
        assertNull(parsed.proposalVal);
        assertNull(parsed.acceptedID);
        assertNull(parsed.acceptedValue);
    }

    /** PROMISE carries proposalID and previously-accepted (id,value) payload */
    @Test
    void constructAndParsePromise() {
        Message m = Message.promise("M5", 300, 200, "M8");
        String line = m.constructMessage();
        Message parsed = Message.parseMessage(line);

        assertEquals(MessageType.PROMISE, parsed.type);
        assertEquals("M5", parsed.senderID);
        assertEquals(300, parsed.proposalID);
        assertEquals(200, parsed.acceptedID);
        assertEquals("M8", parsed.acceptedValue);
    }

    /** ACCEPT_REQUEST and ACCEPTED round-trip with proposal id and value */
    @Test
    void constructAndParseAcceptRequestAndAccepted() {
        Message ar = Message.acceptRequest("M2", 404, "M7");
        Message ac = Message.accepted("M3", 404, "M7");

        Message par = Message.parseMessage(ar.constructMessage());
        Message pac = Message.parseMessage(ac.constructMessage());

        assertEquals(MessageType.ACCEPT_REQUEST, par.type);
        assertEquals("M2", par.senderID);
        assertEquals(404, par.proposalID);
        assertEquals("M7", par.proposalVal);

        assertEquals(MessageType.ACCEPTED, pac.type);
        assertEquals("M3", pac.senderID);
        assertEquals(404, pac.proposalID);
        assertEquals("M7", pac.proposalVal);
    }

    /** Parser accepts minimal valid form and leaves missing fields null */
    @Test
    void parseWithMissingOptionalFields() {
        Message parsed = Message.parseMessage("PREPARE:M9");
        assertEquals(MessageType.PREPARE, parsed.type);
        assertEquals("M9", parsed.senderID);
        assertNull(parsed.proposalID);
    }

    /** Unknown type token results in IllegalArgumentException */
    @Test
    void parseInvalidType() {
        assertThrows(IllegalArgumentException.class, () -> Message.parseMessage("NOPE:M1:12"));
    }

    /** Empty string invalid length/format is rejected */
    @Test
    void parseInvalidLength() {
        assertThrows(IllegalArgumentException.class, () -> Message.parseMessage(""));
    }
}

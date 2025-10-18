package org;

/**
 * Message types used in Paxos protocol.
 */
public enum MessageType {
    PREPARE,
    PROMISE,
    ACCEPT_REQUEST,
    ACCEPTED
}

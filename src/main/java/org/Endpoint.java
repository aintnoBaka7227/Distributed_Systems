package org;

/**
 * Represents a network endpoint for a member.
 */
public class Endpoint {
    public final String id;
    public final String host;
    public final int port;

    public Endpoint(String id, String host, int port) {
        this.id = id;
        this.host = host;
        this.port = port;
    }

    @Override
    public String toString() {
        return id + "@" + host + ":" + port;
    }
}


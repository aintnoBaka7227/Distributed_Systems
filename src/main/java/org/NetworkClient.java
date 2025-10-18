package org;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Simple TCP client utility for sending messages to peers.
 */
public class NetworkClient {
    private final NetworkConfig config;
    private final MemberProfile profile;

    public NetworkClient(NetworkConfig config, MemberProfile profile) {
        this.config = config;
        this.profile = profile;
    }

    /**
     * Send message to a specific member by ID.
     * @param toId destination member id
     * @param m message
     */
    public void send(String toId, Message m) {
        Endpoint ep = config.getEndpoint(toId);
        if (ep == null) return;
        // simulate send-side profile
        if (!profile.beforeNetworkAction()) return; // drop
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(ep.host, ep.port), 1000);
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()));
            pw.println(m.encode());
            pw.flush();
            Logger.messageSent(toId, m);
        } catch (IOException ignored) {
        }
    }

    /**
     * Broadcast a message to all members except self if excludeSelf is true.
     * @param selfId this member id
     * @param m message
     * @param excludeSelf whether to exclude self
     */
    public void broadcast(String selfId, Message m, boolean excludeSelf) {
        List<String> ids = config.getMemberIds();
        for (String id : ids) {
            if (excludeSelf && id.equals(selfId)) continue;
            send(id, m);
        }
    }
}

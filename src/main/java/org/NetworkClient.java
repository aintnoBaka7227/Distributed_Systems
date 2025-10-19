package org;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Simple TCP client utility for sending messages to peers
 */
public class NetworkClient {
    private final NetworkConfig config;
    private final MemberProfile profile;

    public NetworkClient(NetworkConfig config, MemberProfile profile) {
        this.config = config;
        this.profile = profile;
    }

    /**
     * Send message to a specific member by ID
     * @param toDestinationId destination member id
     * @param m message
     */
    public void sendMessage(String toDestinationId, Message m) {
        Endpoint endpoint = config.getEndpoint(toDestinationId);
        if (endpoint == null) return;
        // simulate send-side profile
        // check rate for immediate drop 
        if (!profile.beforeNetworkAction()) return; 
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), 1000);
            try (PrintWriter out = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream()), true)) {
                out.println(m.constructMessage());
                Logger.messageSent(toDestinationId, m);
            }
        } catch (IOException e) {
            Logger.error("Failed to send to " + toDestinationId + ": " + e.getMessage());
        }
    }

    /**
     * Broadcast a message to all members except self if excludeSelf is true
     * @param selfId this member id
     * @param m message
     * @param excludeSelf whether to exclude self
     */
    public void broadcastMessage(String selfId, Message m, boolean excludeSelf) {
        List<String> ids = config.getMemberIds();
        for (String id : ids) {
            if (excludeSelf && id.equals(selfId)) continue;
            sendMessage(id, m);
        }
    }
}

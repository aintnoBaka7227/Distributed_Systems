package org;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Package-private Endpoint co-located with NetworkConfig for simplicity
class Endpoint {
    public final String id;
    public final String host;
    public final int port;

    Endpoint(String id, String host, int port) {
        this.id = id;
        this.host = host;
        this.port = port;
    }

    @Override
    public String toString() {
        return id + "@" + host + ":" + port;
    }
}

/**
 * Loads and holds the network configuration mapping member IDs to host:port.
 * Format per line: M1,localhost,9001
 */
public class NetworkConfig {
    private final Map<String, Endpoint> endpoints;

    private NetworkConfig(Map<String, Endpoint> endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * Load configuration from a file path.
     * @param path file path
     * @return loaded config
     * @throws IOException on read errors
     */
    public static NetworkConfig load(String path) throws IOException {
        Map<String, Endpoint> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                String id = parts[0].trim();
                String host = parts[1].trim();
                int port = Integer.parseInt(parts[2].trim());
                map.put(id, new Endpoint(id, host, port));
            }
        }
        return new NetworkConfig(map);
    }

    /**
     * Get endpoint by member ID.
     * @param id member id
     * @return endpoint or null
     */
    public Endpoint getEndpoint(String id) {
        return endpoints.get(id);
    }

    /**
     * List all member IDs.
     * @return unmodifiable list of IDs
     */
    public List<String> getMemberIds() {
        List<String> ids = new ArrayList<>(endpoints.keySet());
        Collections.sort(ids);
        return ids;
    }

    /**
     * Get majority threshold for quorum (N/2 + 1)
     * @return majority size
     */
    public int majority() {
        int n = endpoints.size();
        return n / 2 + 1;
    }

    /**
     * Get number of members.
     * @return size
     */
    public int size() {
        return endpoints.size();
    }
}



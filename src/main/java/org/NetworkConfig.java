package org;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a single network participant's address and port.
 * Used internally by NetworkConfig to define where each member runs.
 * Example entry: M1@localhost:9001
 */
class Endpoint {
    public final String id;
    public final String host;
    public final int port;

    /**
     * Constructor for Endpoint.
     * @param id   Member ID (e.g., "M1")
     * @param host Hostname or IP address
     * @param port TCP port number assigned 
     */
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
 * NetworkConfig:
 * Loads and stores network information for all members.
 * Each member ID maps to an Endpoint containing host and port details.
 * Expected configuration file format:
 *   M1,localhost,9001
 *   ...
 */
public class NetworkConfig {
    // Maps member IDs to their Endpoint definitions
    private final Map<String, Endpoint> endpoints;

    private NetworkConfig(Map<String, Endpoint> endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * Loads a network configuration from a file on disk.
     * Responsibilities:
     * Ignore comments, empty lines.
     * Parse member ID, host, and port.
     * Build Endpoint objects and store in a map.
     *
     * @param path Path to configuration file (e.g., "network.config")
     * @return NetworkConfig containing all parsed members
     * @throws IOException If file cannot be read or parsed
     */
    public static NetworkConfig loadConfigFile(String path) throws IOException {
        Map<String, Endpoint> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // Skip empty lines and comment lines starting with '#'
                if (line.isEmpty() || line.startsWith("#")) continue;

                // Split line into id, host, port
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
     * Retrieves the endpoint linking to a member ID.
     * @param id Member identifier (e.g., "M1")
     * @return Endpoint object or null if not found
     */
    public Endpoint getEndpoint(String id) {
        return endpoints.get(id);
    }

    /**
     * Returns a sorted list of all member IDs.
     * @return Sorted unmodifiable list of member IDs
     */
    public List<String> getMemberIds() {
        List<String> ids = new ArrayList<>(endpoints.keySet());
        Collections.sort(ids);
        return ids;
    }

    /**
     * Calculates the quorum for Paxos.
     * floor(N/2) + 1
     * @return Number of members required for majority
     */
    public int majority() {
        int n = endpoints.size();
        return n / 2 + 1;
    }

    /**
     * Returns the total number of members in the configuration.
     * @return Count of configured members
     */
    public int size() {
        return endpoints.size();
    }
}

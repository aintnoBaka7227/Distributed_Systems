package org;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.*;
import com.google.gson.*;

/**
 * GETClient:
 * Periodically sends GET requests to the Aggregation Server.
 * Each request carries a Lamport clock value.
 * Implements fault tolerance through retry with exponential backoff.
 * Displays the weather data returned in a readable format.
 */
public class GETClient {
    private static final Logger logger = Logger.getLogger(GETClient.class.getName());

    private final String SERVER_URL;   // Aggregation server base URL
    private final String stationID;    // Specific station ID (optional)
    private final LamportClock clock;  // Lamport clock instance

    /**
     * Construct a GET client.
     * @param serverUrl Aggregation server URL (host:port)
     * @param stationId Station ID to query (nullable, "" → request all stations)
     */
    public GETClient(String serverUrl, String stationId) {
        this.SERVER_URL = serverUrl;
        this.stationID = stationId;
        this.clock = new LamportClock();
    }

    /**
     * Main loop – periodically sends GET requests every 3s.
     */
    private void startRunning() {
        while (true) {
            try {
                clock.increment(); // tick before sending a request
                boolean isSuccess = getDataRobustly();
                if (isSuccess) {
                    logger.info("Data retrieved successfully.");
                } else {
                    logger.warning("Failed to retrieve data after retries.");
                }

                Thread.sleep(3000); // pause before next poll

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Unexpected error in GET client loop", e);
            }
        }
    }

    /**
     * Attempt to GET data with retries (exponential backoff: 1,2,4...).
     * Three retries are attempted.
     * Retry after a timeout of 5s.
     * @return true if success, false if failed after retries
     */
    boolean getDataRobustly() {
        int maxRetries = 3;
        int baseDelay = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse res = sendGET();
                int status = res.getStatusCode();

                if (status == 200) {
                    logger.info("HTTP 200 OK (station=" + stationID + ", clock=" + res.getClock() + ")");
                    displayWeather(res.getBody());
                    return true;
                } else if (status >= 400 && status < 500) {
                    // Permanent failure from the client, no retry
                    logger.warning("Client error " + status + " – not retrying.");
                    return false;
                } else if (status == 500) {
                    logger.warning("Server error " + status + " – retrying (attempt " + attempt + ")");
                } else if (status == 204) {
                    logger.info("No content available for station=" + stationID);
                    return true;
                }

            } catch (SocketTimeoutException e) {
                logger.warning("Timeout after 5s → retrying (attempt " + attempt + ")");
            } catch (IOException e) {
                logger.warning("Network error: " + e.getMessage() + " → retrying (attempt " + attempt + ")");
            }

            // Exponential backoff
            int delay = baseDelay * (int) Math.pow(2, attempt - 1);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) {}
        }

        return false;
    }

    /**
     * Send an HTTP GET request to the server.
     * @return HttpResponse parsed from the server
     * @throws IOException if network or parsing fails
     */
    private HttpResponse sendGET() throws IOException {
        URI uri = URI.create(SERVER_URL);
        String host = uri.getHost() != null ? uri.getHost() : SERVER_URL.split(":")[0];
        int port = (uri.getPort() == -1) ? 4567 : uri.getPort();

        // Construct endpoint: add a query parameter if station ID is specified
        String endpoint = (stationID != null && !stationID.isEmpty())
                ? "/weather.json?id=" + stationID
                : "/weather.json";

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);

            try (BufferedWriter wr = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader rd = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                // Prepare request
                Map<String, String> headers = new HashMap<>();
                headers.put("accept", "application/json");
                HttpRequest req = new HttpRequest("GET", endpoint, headers, null, clock.getValue());
                req.write(wr, host);

                // Parse response
                HttpResponse resp = HttpResponse.parse(rd);
                if (resp != null && resp.getClock() >= 0) {
                    clock.update(resp.getClock()); // update Lamport clock
                }
                return resp;
            }
        }
    }

    /**
     * Parse and print weather data in human-readable format.
     */
    private void displayWeather(String jsonString) {
        try {
            JsonElement root = JsonParser.parseString(jsonString);

            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();

                // Case 1: Multiple stations
                boolean isMultiStation = obj.entrySet().stream()
                        .allMatch(e -> e.getValue().isJsonObject());

                if (isMultiStation) {
                    for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                        System.out.println(entry.getKey() + ":");
                        System.out.println(formatStation(entry.getValue().getAsJsonObject()));
                        System.out.println(); // blank line between stations
                    }
                }
                // Case 2: Single station
                else {
                    System.out.println(formatStation(obj));
                }
            }
            // Case 3: Array of stations
            else if (root.isJsonArray()) {
                int index = 1;
                for (JsonElement element : root.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        System.out.println("Station " + index + ":");
                        System.out.println(formatStation(element.getAsJsonObject()));
                        System.out.println();
                    }
                    index++;
                }
            }
            else {
                logger.warning("Unexpected JSON format: " + jsonString);
            }

        } catch (Exception e) {
            logger.warning("Failed to parse JSON: " + e.getMessage());
        }
    }

    /**
     * Helper: format a single station's JSON object into key:value lines.
     */
    private String formatStation(JsonObject obj) {
        StringBuilder sb = new StringBuilder();
        for (String key : obj.keySet()) {
            JsonElement value = obj.get(key);
            if (value.isJsonObject()) {
                sb.append(key).append(":\n");
                sb.append(formatStation(value.getAsJsonObject())); // recursive
            } else {
                sb.append(key).append(": ").append(value.getAsString()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Ensure URL always starts with "http://".
     */
    private static String handleURL(String url) {
        if (!url.startsWith("http://")) return "http://" + url;
        return url;
    }

    /**
     * Main entry point for running the GET client.
     *
     * Example:
     *   java org.GETClient -url localhost:4567 -sid IDS60901
     */
    public static void main(String[] args) {
        // Configure logger to print to console
        Logger rootLogger = Logger.getLogger("");
        for (Handler h : rootLogger.getHandlers()) {
            rootLogger.removeHandler(h);
        }
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new SimpleFormatter());
        rootLogger.addHandler(handler);
        rootLogger.setLevel(Level.ALL);

        // Parse arguments
        String serverUrl = null, stationId = null;
        for (int i = 0; i < args.length; i++) {
            if ("-url".equals(args[i]) && i + 1 < args.length) {
                serverUrl = args[++i];
            } else if ("-sid".equals(args[i]) && i + 1 < args.length) {
                stationId = args[++i];
            }
        }
        if (serverUrl == null) {
            throw new IllegalArgumentException("Usage: java GETClient -url {server url} [-sid stationID]");
        }
        serverUrl = handleURL(serverUrl);

        // Start a client loop
        new GETClient(serverUrl, stationId).startRunning();
    }
}

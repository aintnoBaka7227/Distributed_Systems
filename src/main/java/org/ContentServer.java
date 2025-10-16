package org;

import java.io.*;
import java.net.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.*;

/**
 * ContentServer:
 *  Reads weather station data from a text file.
 *  Periodically uploads the data to the Aggregation Server via HTTP PUT.
 *  Each PUT carries a Lamport clock value.
 *  Retries requests on failure (with exponential backoff).
 *
 * Usage:
 *   java ContentServer -url <server url> -f <station data file>
 */
public class ContentServer {

    private static final Logger logger = Logger.getLogger(ContentServer.class.getName());

    private final String FILE_PATH;   // path to an input file with station data
    private final String SERVER_URL;  // aggregation server base URL
    private final LamportClock clock; // local Lamport clock
    private final Gson gson;          // JSON serializer

    public ContentServer(String filePath, String serverUrl) {
        this.FILE_PATH = filePath;
        this.SERVER_URL = serverUrl;
        this.clock = new LamportClock();
        this.gson = new Gson();
    }

    /**
     * Read the station data file into a list of JSON objects.
     * A blank line separates each record in the file.
     * @return list of station JSON records
     */
    java.util.List<JsonObject> readStationData() throws IOException {
        java.util.List<JsonObject> records = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
            JsonObject current = new JsonObject();
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    // End of one record
                    if (!current.isEmpty()) {
                        records.add(current);
                        current = new JsonObject();
                    }
                } else {
                    // Parse "key: value" lines
                    String[] stationData = line.split(":", 2);
                    if (stationData.length == 2) {
                        current.addProperty(stationData[0].trim(), stationData[1].trim());
                    }
                }
            }

            // Add the last record if a file doesn’t end with a blank line
            if (!current.isEmpty()) {
                records.add(current);
            }
        }

        logger.info("Read " + records.size() + " records from file " + FILE_PATH);
        return records;
    }

    /**
     * Main loop: continuously read a station file and send PUTs.
     */
    private void startRunning() {
        while (true) {
            try {
                java.util.List<JsonObject> records = readStationData();

                for (JsonObject record : records) {
                    if (!record.has("id")) {
                        logger.warning("Skipping record with no id: " + record);
                        continue;
                    }

                    clock.increment(); // local event: preparing PUT
                    boolean isSuccess = sendPUTRobustly(record);

                    if (isSuccess) {
                        logger.info("PUT succeeded for record id=" + record.get("id").getAsString());
                    } else {
                        logger.warning("PUT failed for record id=" + record.get("id").getAsString());
                    }

                    Thread.sleep(3000); // space out PUTs
                }

                Thread.sleep(30000); // wait before re-reading a file again

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Unexpected ContentServer error", e);
            }
        }
    }

    /**
     * PUT with retry logic (max 3 attempts, exponential backoff: 1,2,4...).
     * Retry after a timeout of 5s.
     * @param json station JSON record to PUT
     * @return true if success, false if failed after retries
     */
    boolean sendPUTRobustly(JsonObject json) {
        int maxRetries = 3;
        int baseDelay = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse res = sendPUT(json);
                int status = res.getStatusCode();

                if (status == 200 || status == 201) {
                    logger.info("PUT success (status=" + status + ") on attempt " + attempt);
                    return true;
                } else if (status == 204) {
                    logger.info("PUT returned 204 No Content. Treating as success.");
                    return true;
                } else if (status >= 400 && status < 500) {
                    logger.warning("Client error " + status + " (record id=" + json.get("id") + "). Not retrying.");
                    return false;
                } else if (status == 500) {
                    logger.warning("Server error " + status + " (record id=" + json.get("id") + ") → retrying...");
                }

            } catch (SocketTimeoutException e) {
                logger.warning("Attempt " + attempt + " for record id=" + json.get("id") + ": timeout after 5s");
            } catch (IOException e) {
                logger.warning("Attempt " + attempt + " for record id=" + json.get("id") +
                        ": network error → " + e.getMessage());
            }

            // exponential backoff
            int delay = baseDelay * (int) Math.pow(2, attempt - 1);
            logger.info("Retrying record id=" + json.get("id") + " after " + delay + "ms");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) {}
        }

        logger.severe("All " + maxRetries + " attempts failed for record id=" + json.get("id"));
        return false;
    }

    /**
     * Send one PUT request to the Aggregation Server.
     */
    private HttpResponse sendPUT(JsonObject json) throws IOException {
        URI uri = URI.create(SERVER_URL);
        String host = uri.getHost() != null ? uri.getHost() : SERVER_URL.split(":")[0];
        int port = (uri.getPort() == -1) ? 4567 : uri.getPort();
        String body = gson.toJson(json);
        String endpoint = "/weather.json";

        logger.info("Sending PUT with Clock=" + clock.getValue() +
                " to " + host + ":" + port + endpoint +
                " with body: " + body);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);

            try (BufferedWriter wr = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader rd = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                // Build & send request
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                HttpRequest req = new HttpRequest("PUT", endpoint, headers, body, clock.getValue());
                req.write(wr, host);

                // Parse response
                HttpResponse resp = HttpResponse.parse(rd);
                if (resp != null && resp.getClock() >= 0) {
                    clock.update(resp.getClock()); // merge Lamport clock
                }
                return resp;
            }
        }
    }

    /**
     * Ensure the URL always has "http://".
     */
    private static String handleURL(String url) {
        if (!url.startsWith("http://")) {
            return "http://" + url;
        }
        return url;
    }

    /**
     * Entry point.
     * Expected arguments:
     * url <server url>
     * f <station data file>
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

        String url = null;
        String filePath = null;

        // Parse command-line args
        for (int i = 0; i < args.length; i++) {
            if ("-url".equals(args[i])) {
                url = args[++i];
            } else if ("-f".equals(args[i])) {
                filePath = args[++i];
            }
        }

        if (url == null || filePath == null) {
            throw new IllegalArgumentException("Usage: java ContentServer -url <server url> -f <station data file>");
        }

        url = handleURL(url);

        // Start the content server
        ContentServer server = new ContentServer(filePath, url);
        server.startRunning();
    }
}

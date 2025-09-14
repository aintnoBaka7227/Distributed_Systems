package org;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.*;
import com.google.gson.*;

public class GETClient {
    private static final Logger logger = Logger.getLogger(GETClient.class.getName());

    private final String SERVER_URL;
    private final String stationID;
    private final LamportClock clock;

    GETClient(String serverUrl, String stationId) {
        this.SERVER_URL = serverUrl;
        this.stationID = stationId;
        this.clock = new LamportClock();
    }

    private void startRunning() {
        while (true) {
            try {
                clock.increment();
                boolean isSuccess = getDataRobustly();
                if (isSuccess) {
                    logger.info("Data retrieved successfully.");
                } else {
                    logger.warning("Failed to retrieve data after retries.");
                }

                Thread.sleep(5000);

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Unexpected error in GET client loop", e);
            }
        }
    }

    private boolean getDataRobustly() {
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

            int delay = baseDelay * (int) Math.pow(2, attempt - 1);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) {}
        }

        return false;
    }

    private HttpResponse sendGET() throws IOException {
        URI uri = URI.create(SERVER_URL);
        String host = uri.getHost() != null ? uri.getHost() : SERVER_URL.split(":")[0];
        int port = (uri.getPort() == -1) ? 4567 : uri.getPort();

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

                Map<String, String> headers = new HashMap<>();
                headers.put("accept", "application/json");
                HttpRequest req = new HttpRequest("GET", endpoint, headers, null, clock.getValue());
                req.write(wr, host);

                HttpResponse resp = HttpResponse.parse(rd);
                if (resp != null && resp.getClock() >= 0) {
                    clock.update(resp.getClock());
                }
                return resp;
            }
        }
    }

    private void displayWeather(String jsonString) {
        try {
            JsonElement root = JsonParser.parseString(jsonString);

            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();

                // Case 1: This object looks like multiple stations (map keyed by ID)
                boolean isMultiStation = obj.entrySet().stream()
                        .allMatch(e -> e.getValue().isJsonObject());

                if (isMultiStation) {
                    for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                        System.out.println(entry.getKey() + ":");
                        System.out.println(formatStation(entry.getValue().getAsJsonObject()));
                        System.out.println(); // blank line between stations
                    }
                }
                // Case 2: Just a single station record
                else {
                    System.out.println(formatStation(obj));
                }
            }
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


    /** Format a station JSON object into key:value lines (like the input file). */
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


    private static String handleURL(String url) {
        if (!url.startsWith("http://")) return "http://" + url;
        return url;
    }

    public static void main(String[] args) {
        // Configure logger
        Logger rootLogger = Logger.getLogger("");
        for (Handler h : rootLogger.getHandlers()) {
            rootLogger.removeHandler(h);
        }
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new SimpleFormatter());
        rootLogger.addHandler(handler);
        rootLogger.setLevel(Level.ALL);

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
        new GETClient(serverUrl, stationId).startRunning();
    }
}

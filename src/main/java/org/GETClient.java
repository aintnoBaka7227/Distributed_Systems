package org;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.logging.Level;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
                    logger.info("Got data successfully!");
                } else {
                    logger.warning("Failed to get data!");
                }

                Thread.sleep(5000);

            } catch (Exception e) {
                logger.log(Level.SEVERE, "GET Client error: " + e.getMessage(), e);
            }
        }
    }

    private boolean getDataRobustly() {
        int maxRetries = 3;
        int baseDelay = 1000;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                int status = sendGET();

                if (status == 200) {
                    return true;
                } else if (status >= 400 && status < 500) {
                    logger.warning("Client error " + status + ", not retrying.");
                    return false;
                } else if (status == 500) {
                    logger.warning("Server error " + status + ", retrying...");
                }

            } catch (SocketTimeoutException e) {
                logger.warning("Timeout after 5s → retrying (attempt " + attempt + ")");
            } catch (IOException e) {
                logger.warning("Network error: " + e.getMessage() + " → retrying (attempt " + attempt + ")");
            }

            int delay = baseDelay * (int) Math.pow(2, attempt);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) {}
        }

        return false;
    }

    private int sendGET() throws IOException {
        URI uri = URI.create(SERVER_URL);
        String host = uri.getHost() != null ? uri.getHost() : SERVER_URL.split(":")[0];
        int port = (uri.getPort() == -1) ? 4567 : uri.getPort();

        String endpoint = "/weather.json";
        if (stationID != null && !stationID.isEmpty()) {
            endpoint += "?id=" + stationID;
        } else {
            endpoint += "?id=";
        }


        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);

            try (BufferedWriter wr = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                wr.write("GET " + endpoint + " HTTP/1.1\r\n");
                wr.write("Host: " + host + "\r\n");
                wr.write("User-Agent: ATOMClient/1/0\r\n");
                wr.write("Clock: " + clock.getValue() + "\r\n");
                wr.write("\r\n");
                wr.flush();

                String statusLine = in.readLine();
                if (statusLine == null || !statusLine.startsWith("HTTP/1.1")) {
                    throw new IOException("Invalid response from server");
                }
                logger.info("Response: " + statusLine);

                String[] parts = statusLine.split(" ");
                int statusCode = (parts.length >= 2) ? Integer.parseInt(parts[1]) : -1;

                String header;
                while ((header = in.readLine()) != null && !header.isEmpty()) {
                    if (header.startsWith("Clock:")) {
                        int serverClock = Integer.parseInt(header.split(":")[1].trim());
                        clock.update(serverClock);
                    }
                }

                StringBuilder body = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    body.append(line);
                }

                if (!body.isEmpty()) {
                    if (statusCode == 200) {
                        displayWeather(body.toString());
                    } else {
                        logger.warning("Error response body: " + body);
                    }
                }

                return statusCode;
            }
        }
    }

    private void displayWeather(String jsonString) {
        try {
            JsonElement root = JsonParser.parseString(jsonString);

            if (root.isJsonObject()) {
                // If it's a single station JSON object
                prettyPrintObject(root.getAsJsonObject(), "");
            } else if (root.isJsonArray()) {
                // If it's multiple stations, print each
                int index = 1;
                for (JsonElement element : root.getAsJsonArray()) {
                    System.out.println("Station " + index + ":");
                    if (element.isJsonObject()) {
                        prettyPrintObject(element.getAsJsonObject(), "  "); // indent for clarity
                    }
                    index++;
                    System.out.println();
                }
            } else {
                logger.warning("Unexpected JSON format: " + jsonString);
            }

        } catch (Exception e) {
            logger.warning("Failed to parse JSON: " + e.getMessage());
        }
    }

    private void prettyPrintObject(JsonObject obj, String indent) {
        for (String key : obj.keySet()) {
            JsonElement value = obj.get(key);
            if (value.isJsonObject()) {
                System.out.println(indent + key + ":");
                prettyPrintObject(value.getAsJsonObject(), indent + "  ");
            } else {
                System.out.println(indent + key + " = " + value.getAsString());
            }
        }
    }


    private static String handleURL(String url) {
        if (!url.startsWith("http://")) {
            return "http://" + url;
        }
        return url;
    }

    public static void main(String[] args) {
        String serverUrl = null;
        String stationId = null;
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

        GETClient client = new GETClient(serverUrl, stationId);
        client.startRunning();
    }
}

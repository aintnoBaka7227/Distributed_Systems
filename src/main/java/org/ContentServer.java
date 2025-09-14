package org;

import java.io.*;
import java.net.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.logging.*;



/*
 * Content Server:
 * Read station data from a text file
 * Upload the data to the Aggregation Server through HTTP PUT
 * Each request is sent with a Lamport Clock value
 * Fault Tolerance: resend based on response status
 */
public class ContentServer {

    private static final Logger logger = Logger.getLogger(ContentServer.class.getName());

    private final String FILE_PATH;
    private final String SERVER_URL;
    private final LamportClock clock;
    private final Gson gson;

    ContentServer(String filePath, String serverUrl) {
        this.FILE_PATH = filePath;
        this.SERVER_URL = serverUrl;
        this.clock = new LamportClock();
        this.gson = new Gson();
    }

    private java.util.List<JsonObject> readStationData() throws IOException {
        java.util.List<JsonObject> records = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH))) {
            JsonObject current = new JsonObject();
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    if (!current.isEmpty()) {
                        records.add(current);
                        current = new JsonObject();
                    }
                } else {
                    String[] stationData = line.split(":", 2);
                    if (stationData.length == 2) {
                        current.addProperty(stationData[0].trim(), stationData[1].trim());
                    }
                }
            }

            // Add last record if file doesn’t end with blank line
            if (!current.isEmpty()) {
                records.add(current);
            }
        }

        logger.info("Read " + records.size() + " records from file " + FILE_PATH);
        return records;
    }

    private void startRunning() {
        while (true) {
            try {
                java.util.List<JsonObject> records = readStationData();

                for (JsonObject record : records) {

                    if (!record.has("id")) {
                        logger.warning("Skipping record with no id: " + record);
                        continue;
                    }

                    clock.increment();
                    boolean isSuccess = sendPUTRobustly(record);
                    if (isSuccess) {
                        logger.info("PUT succeeded for " + record);
                    } else {
                        logger.warning("PUT failed for " + record);
                    }
                    Thread.sleep(3000); // space out PUTs
                }

                Thread.sleep(5000); // wait before re-reading file again

            } catch (Exception e) {
                logger.log(Level.SEVERE, "Content Server error: " + e.getMessage(), e);
            }
        }
    }



    private int sendPUT(JsonObject json) throws IOException {
        URI uri = URI.create(SERVER_URL);
        String host = uri.getHost();
        int port = (uri.getPort() == -1) ? 4567 : uri.getPort();
        String endpoint = "/weather.json";

        String body = gson.toJson(json);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);

            try (BufferedWriter wr = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                // ---- send request ----
                wr.write("PUT " + endpoint + " HTTP/1.1\r\n");
                wr.write("Host: " + host + "\r\n");
                wr.write("User-Agent: ATOMClient/1/0\r\n");
                wr.write("Content-Type: application/json\r\n");
                wr.write("Content-Length: " + bodyBytes.length + "\r\n");
                wr.write("Clock: " + clock.getValue() + "\r\n");
                wr.write("\r\n");
                wr.write(body);
                wr.flush();

                // ---- read response ----
                String statusLine = in.readLine();
                if (statusLine == null || !statusLine.startsWith("HTTP/1.1")) {
                    throw new IOException("Invalid response from server");
                }
                logger.info("Response: " + statusLine);

                String[] parts = statusLine.split(" ");
                int statusCode = (parts.length >= 2) ? Integer.parseInt(parts[1]) : -1;

                String header;
                while ((header = in.readLine()) != null && !header.isEmpty()) {
                    logger.fine("Header: " + header);
                    if (header.startsWith("Clock:")) {
                        int serverClock = Integer.parseInt(header.split(":")[1].trim());
                        clock.update(serverClock);
                        logger.info("Updated Lamport clock to " + clock.getValue());
                    }
                }

                // ✅ Now capture the response body
                StringBuilder responseBody = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    responseBody.append(line).append("\n");
                }
                if (!responseBody.isEmpty()) {
                    logger.info("Response body: " + responseBody.toString().trim());
                }

                return statusCode;
            }
        }
    }


    private boolean sendPUTRobustly(JsonObject json) {
        int maxRetries = 3;
        int baseDelay = 1000;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                int status = sendPUT(json);

                if (status == 200 || status == 201) {
                    logger.info("PUT success (status=" + status + ") on attempt " + attempt);
                    return true;
                } else if (status == 204) {
                    logger.warning("PUT returned 204 No Content. Stopping retries.");
                    return true;
                } else if (status >= 400 && status < 500) {
                    logger.warning("Client error " + status + ". Not retrying.");
                    return false;
                } else if (status == 500) {
                    logger.warning("Server error " + status + " → retrying...");
                }

            } catch (SocketTimeoutException e) {
                logger.warning("Attempt " + attempt + ": timeout after 5s → retrying");
            } catch (IOException e) {
                logger.warning("Attempt " + attempt + ": network error → " + e.getMessage());
            }

            int delay = baseDelay * (int) Math.pow(2, attempt);
            logger.info("Retrying after " + delay + "ms");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) {}
        }

        logger.severe("All " + maxRetries + " attempts failed. Aborting.");
        return false;
    }

    private static String handleURL(String url) {
        if (!url.startsWith("http://")) {
            return "http://" + url;
        }
        return url;
    }

    public static void main(String[] args) throws IOException {
        // Optional: configure logger format
        Logger rootLogger = Logger.getLogger("");
        for (Handler h : rootLogger.getHandlers()) {
            h.setFormatter(new SimpleFormatter());
        }

        String url = null;
        String filePath = null;

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

        ContentServer server = new ContentServer(filePath, url);
        server.startRunning();
    }
}

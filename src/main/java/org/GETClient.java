package org;

import java.io.*;
import java.net.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

public class GETClient {

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
                    System.out.println("Got data successfully!");
                } else {
                    System.out.println("Failed to get data!");
                }

                Thread.sleep(5000);

            } catch (Exception e) {
                System.err.println("GET Client error: " + e.getMessage());
            }
        }
    }

    private boolean getDataRobustly() {
        int maxRetries = 3;
        int baseDelay = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                int status = sendGET();

                if (status == 200) {
                    return true;
                } else if (status >= 400 && status < 500) {
                    System.err.println("Client error " + status + ", not retrying.");
                    return false;
                } else if (status >= 500) {
                    System.err.println("Server error " + status + ", retrying...");
                }

            } catch (SocketTimeoutException e) {
                System.err.println("Timeout after 5s → retrying (attempt " + attempt + ")");
            } catch (IOException e) {
                System.err.println("Network error: " + e.getMessage() + " → retrying (attempt " + attempt + ")");
            }

            int delay = baseDelay * (int) Math.pow(2, attempt - 1);
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
        if (stationID != null) {
            endpoint += "?id=" + stationID;
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
                System.out.println("Response: " + statusLine);

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

                if (statusCode == 200) {
                    displayWeather(body.toString());
                }

                return statusCode;
            }
        }
    }

    private void displayWeather(String jsonString) {
        JsonElement root = JsonParser.parseString(jsonString);
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            for (String key : obj.keySet()) {
                System.out.println(key + " = " + obj.get(key).getAsString());
            }
        } else {
            System.out.println("Invalid JSON response: " + jsonString);
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



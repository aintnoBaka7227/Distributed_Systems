package org;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/*
 * Content Server:
 * Read station data from a text file
 * Upload the data to the Aggregation Server through HTTP PUT
 * Each request is sent with a Lamport Clock value
 * Fault Tolerance: resend based on response status
 */

public class ContentServer {

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

    private JsonObject readStationData() throws IOException {

        JsonObject data = new JsonObject();
        try {
            BufferedReader reader = new BufferedReader(new FileReader(FILE_PATH));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] stationData = line.split(":");
                data.addProperty(stationData[0], stationData[1]);
            }
            reader.close();
            System.out.println("Finished reading station data: " + data);
            return data;
        } catch (FileNotFoundException e) {
            System.out.println("File not found: " + FILE_PATH);
            e.printStackTrace();
            return null;
        }
    }

    private void startRunning() {
        while (true) {
            try {
                JsonObject jsonStationData = readStationData();
                clock.increment();
                sendPUT(jsonStationData);

                Thread.sleep(5000);

            } catch (Exception e) {
                System.err.println("Content Server error:" + e.getMessage());
            }
        }
    }

    private void sendPUT(JsonObject json) {
        try {

            URL url = new URL(SERVER_URL);
            String host = url.getHost();
            int port = (url.getPort() == -1) ? 4567 : url.getPort();


            String body = gson.toJson(json);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);


            try (Socket socket = new Socket(host, port);
                 BufferedWriter wr = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                String endpoint = "/weather.json";
                // Build HTTP request manually
                wr.write("PUT " + endpoint + " HTTP/1.1\r\n");
                wr.write("User-Agent: ATOMClient/1/0\r\n");
                wr.write("Content-Type: application/json\r\n");
                wr.write("Content-Length: " + bodyBytes.length + "\r\n");
                wr.write("Clock: " + clock.getValue() + "\r\n");
                wr.write("\r\n"); // blank line before body
                wr.write(body);
                wr.flush();

                // Read HTTP response
                String responseLine;
                System.out.println("=== Server Response ===");
                while ((responseLine = in.readLine()) != null && !responseLine.isEmpty()) {
                    System.out.println(responseLine);
                }
                System.out.println("=======================");

            }

        } catch (IOException e) {
            System.err.println("Error sending PUT request to Aggregation Server: " + e.getMessage());
            // For debugging only; consider removing stack trace for submission
            e.printStackTrace();
        }
    }

    private static String handleURL(String url) {
        if (!url.startsWith("http://")) {
            return "http://" + url;
        }
        return url;
    }

    public static void main(String[] args) throws IOException {
        String url = null;
        String filePath = null;

        for ( int i = 0; i < args.length; i++ ) {
            if ( args[i].equals("-url") ) {
                url = args[++i];
            }
            else if ( args[i].equals("-f") ) {
                filePath = args[++i];
            }
        }

        if ( url == null || filePath == null ) {
            System.out.println("Usage: java ContentServer -url <server url> -f <station data file>");
            System.exit(1);
        }

        url = handleURL(url);

        ContentServer server = new ContentServer(filePath, url);
        server.startRunning();
    }
}

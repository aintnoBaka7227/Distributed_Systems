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
        while (true){
            try {
                JsonObject jsonStationData = readStationData();
                clock.increment();
                boolean isSuccess = sendPUTRobustly(jsonStationData);
                if (isSuccess) {
                    System.out.println("Successfully send PUT Robustly for " + jsonStationData);
                }
                else System.out.println("Failed to send PUT Robustly for " + jsonStationData);

                Thread.sleep(5000);

            } catch (Exception e) {
                System.err.println("Content Server error:" + e.getMessage());
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

                wr.write("PUT " + endpoint + " HTTP/1.1\r\n");
                wr.write("Host: " + host + "\r\n");
                wr.write("User-Agent: ATOMClient/1/0\r\n");
                wr.write("Content-Type: application/json\r\n");
                wr.write("Content-Length: " + bodyBytes.length + "\r\n");
                wr.write("Clock: " + clock.getValue() + "\r\n");
                wr.write("\r\n");
                wr.write(body);
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
                    System.out.println(header);
                    if (header.startsWith("Clock:")) {
                        int serverClock = Integer.parseInt(header.split(":")[1].trim());
                        clock.update(serverClock);
                    }
                }

                return statusCode;
            }
        }
    }



    private boolean sendPUTRobustly(JsonObject json) {
        int maxRetries = 3;
        int baseDelay = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                int status = sendPUT(json);

                if (status == 200 || status == 201) {
                    if (status == 200) {
                        System.out.println("Open connection to Aggregation Server");
                    } else System.out.println("Update Station Data");
                    System.out.println("Success on attempt " + attempt);
                    return true;
                } else if (status == 204) {
                    System.out.println("No content, stopping retries.");
                    return true;
                } else if (status >= 400 && status < 500) {
                    System.err.println("Client error " + status + ", not retrying.");
                    return false;
                } else if (status >= 500) {
                    System.err.println("Server error " + status + " → will retry");
                }

            } catch (SocketTimeoutException e) {
                System.err.println("Attempt " + attempt + ": no response in 5s → retrying");
            } catch (IOException e) {
                System.err.println("Network error: " + e.getMessage() + " → retrying");
            }

            int delay = baseDelay * (int) Math.pow(2, attempt - 1);
            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
        }

        System.err.println("All attempts failed after " + maxRetries + " retries.");
        return false;
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
            throw new IllegalArgumentException("Usage: java ContentServer -url <server url> -f <station data file>");
        }

        url = handleURL(url);

        ContentServer server = new ContentServer(filePath, url);
        server.startRunning();
    }
}

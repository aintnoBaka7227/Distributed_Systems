package org;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/*
 * Creating a mock class with fake sendPUT and sendGetAll methods to test
 * Max Capacity and Expiry
 */

class ServerCapacityAndExpiryTest {

    private static ExecutorService serverExecutor;
    private static int testPort;
    private static AggregationServer server;
    private static File mainFile;
    private static File tempFile;

    @BeforeAll
    static void startServer() throws Exception {
        serverExecutor = Executors.newSingleThreadExecutor();

        // OS picks free port
        try (ServerSocket s = new ServerSocket(0)) {
            testPort = s.getLocalPort();
        }

        // Fake persistence files
        mainFile = File.createTempFile("weather-main", ".db");
        tempFile = File.createTempFile("weather-temp", ".db");

        server = new AggregationServer(testPort,
                mainFile.getAbsolutePath(),
                tempFile.getAbsolutePath());

        serverExecutor.submit(() -> {
            try {
                server.startRunning();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.stopRunning();
        serverExecutor.shutdownNow();
        mainFile.delete();
        tempFile.delete();
    }

    // Mock response class for testing
    private static class Response {
        int statusCode;
        String body = "";
    }

    // Send a request to the server and return the response
    private Response exchange(String request) throws IOException {
        try (Socket socket = new Socket("localhost", testPort)) {
            BufferedWriter wr = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            BufferedReader rd = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

            wr.write(request);
            wr.flush();
            socket.shutdownOutput();

            Response resp = new Response();

            // Parse status line
            String statusLine = rd.readLine();
            if (statusLine == null) throw new IOException("No response");
            String[] parts = statusLine.split(" ");
            resp.statusCode = Integer.parseInt(parts[1]);

            // Parse headers
            int contentLength = 0;
            String line;
            while ((line = rd.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                }
            }

            // Parse response body
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = rd.read(buf);
                resp.body = new String(buf, 0, read);
            }

            return resp;
        }
    }

    // Send a PUT request to the server and return the response
    private Response sendPut(String id, String name) throws IOException {
        String body = String.format("{\"id\":\"%s\",\"name\":\"%s\"}", id, name);
        String request = "PUT /weather.json HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body;
        return exchange(request);
    }

    // Send a GET request to the server and return the response with all stations
    private Response sendGetAll() throws IOException {
        String request = "GET /weather.json HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Accept: application/json\r\n" +
                "\r\n";
        return exchange(request);
    }

    @Test
    void enforcesTwentySlotLimit() throws Exception {
        // insert 22 stations
        for (int i = 0; i < 22; i++) {
            Response r = sendPut("S" + i, "Station" + i);
            assertTrue(r.statusCode == 200 || r.statusCode == 201);
        }

        // fetch all
        Thread.sleep(1);
        Response res = sendGetAll();
        assertEquals(200, res.statusCode);

        String body = res.body;
        assertNotNull(body);

        // Parse JSON
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        System.out.println(json.size());

        // Capacity should not exceed 20
        assertTrue(json.size() <= 20, "Server should not hold more than 20 stations");

        // The newer one should be presented
        assertTrue(body.contains("Station21"), "Newest station should exist");
    }


    // Test if expired stations are removed
    @Test
    void expiredStationsAreRemoved() throws Exception {
        // Insert a test station
        Response putRes = sendPut("EXP1", "ExpiringStation");
        assertTrue(putRes.statusCode == 200 || putRes.statusCode == 201);

        // Immediately confirm it's retrievable
        String request1 = "GET /weather.json?id=EXP1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Accept: application/json\r\n" +
                "\r\n";
        Response getRes1 = exchange(request1);
        assertEquals(200, getRes1.statusCode);

        // Freeze longer than expiryMillis (30s in your AggregationServer)
        Thread.sleep(31_000);

        // Check for expiry
        Response getRes2 = exchange(request1);
        assertEquals(404, getRes2.statusCode, "Station should expire after 30s");
    }

}



package org;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConcurrencyGETsAndStalePUTsTest
 * Integration tests for concurrency handling and Lamport clock behavior
 * in the AggregationServer.
 * Covers:
 * Multiple GET requests are issued concurrently while a PUT is happening
 * with no torn reads.
 * Rejection of stale PUTs (Lamport clock too old).
 * These tests use raw sockets to simulate real clients, not using real classes.
 */

class ConcurrencyGETsAndStalePUTsTest {

    private static ExecutorService serverExecutor;
    private static int testPort;
    private static AggregationServer server;
    private static File mainFile;
    private static File tempFile;
    private static AtomicInteger lamportCounter;

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

        lamportCounter = new AtomicInteger(1);

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

    // Send a PUT with an auto-incrementing Lamport clock
    private Response sendPut(String id, String name) throws IOException {
        int lamport = lamportCounter.getAndIncrement();
        String body = String.format("{\"id\":\"%s\",\"name\":\"%s\"}", id, name);
        String request = "PUT /weather.json HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/json\r\n" +
                "Lamport-Clock: " + lamport + "\r\n" +
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

    // Test case to check if concurrent GETs are handled correctly
    // Race condition should be prevented as GETs either get "old" or "new"
    // Without any broken text
    @Test
    void concurrentGetsWhilePut() throws Exception {
        // Initial PUT with "old" value
        sendPut("1", "old");

        // Concurrent GETs using countdown -> fire at the same time
        int numGets = 10;
        CountDownLatch ready = new CountDownLatch(numGets);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(numGets);

        List<Response> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < numGets; i++) {
            pool.submit(() -> {
                try {
                    // Wait for all threads to be ready
                    ready.countDown();
                    start.await();
                    // All GETs launch at the same time
                    results.add(exchange(
                            "GET /weather.json?id=1 HTTP/1.1\r\n" +
                                    "Host: localhost\r\n" +
                                    "Accept: application/json\r\n\r\n"));
                } catch (Exception e) {
                    fail(e);
                }
            });
        }

        // Wait and release all threads at the same time
        ready.await();
        start.countDown();

        // Release a concurrent put
        Response put = sendPut("1", "new");
        assertTrue(put.statusCode == 200 || put.statusCode == 201);

        // Ensure all GETs completed
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // Ensure all GETs returned non-corrupted results
        assertEquals(numGets, results.size());
        for (Response r : results) {
            assertEquals(200, r.statusCode);
            assertTrue(r.body.contains("old") || r.body.contains("new"));
        }
    }

    // Test case to check if stale PUTs are rejected
    @Test
    void stalePutRejected() throws Exception {
        // Fresh insert (lamport=1)
        Response fresh = sendPut("1", "Phong");
        assertTrue(fresh.statusCode == 200 || fresh.statusCode == 201);

        // Craft stale request with an artificially small Lamport clock
        // Contains the same ID with the same clock as fresh insert
        String body = "{\"id\":\"1\",\"name\":\"Ingrid\"}";
        String request = "PUT /weather.json HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/json\r\n" +
                "Lamport-Clock: 1\r\n" +   // deliberately lower than counter
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body;

        // Stale PUT should be rejected
        Response stale = exchange(request);
        assertEquals(409, stale.statusCode, "Stale PUT should be rejected");
    }
}



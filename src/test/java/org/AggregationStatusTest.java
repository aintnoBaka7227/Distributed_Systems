package org;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AggregationStatusTest {

    private static ExecutorService testServerExecutor;
    private static int testPort;
    private static AggregationServer server;

    @BeforeAll
    static void start() throws Exception {
        testServerExecutor = Executors.newSingleThreadExecutor();

        // Let the OS pick a free port
        try (ServerSocket s = new ServerSocket(0)) {
            testPort = s.getLocalPort();
        }

        // Start the server in a separate thread for mock test
        File main = File.createTempFile("weather", ".db");
        File temp = File.createTempFile("weather-temp", ".db");
        server = new AggregationServer(testPort, main.getAbsolutePath(), temp.getAbsolutePath());
        testServerExecutor.submit(() -> {
            try {
                server.startRunning();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(1000);
    }

    @AfterAll
    static void stopServer() throws IOException {
        server.stopRunning();
        testServerExecutor.shutdownNow();
    }

    // Replicate sendRequest for testing only
    private HttpResponse sendRequest(String method, String resource, String body, int clock) throws Exception {
        // Mock client socket
        try (Socket socket = new Socket("localhost", testPort)) {
            BufferedWriter wr = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader rd = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            Map<String, String> headers = new HashMap<>();
            if (body != null) headers.put("Content-Type", "application/json");
            HttpRequest req = new HttpRequest(method, resource, headers, body, clock);
            req.write(wr, "localhost");

            return HttpResponse.parse(rd);
        }
    }


    @Test
    void testPutInFirstInsertReturns201O() throws Exception {
        String body = "{ \"id\": \"IDS60901\", \"name\": \"Adelaide\", \"air_temp\": \"13\" }";
        HttpResponse res = sendRequest("PUT", "/weather.json", body, 1);
        assertEquals(201, res.getStatusCode());
    }

    @Test
    void testPutOnUpdateReturns200O() throws Exception {
        String body = "{ \"id\": \"IDS60902\", \"name\": \"Adelaide\", \"air_temp\": \"15\" }";
        HttpResponse res = sendRequest("PUT", "/weather.json", body, 1);
        assertEquals(201, res.getStatusCode());
        body = "{ \"id\": \"IDS60902\", \"name\": \"Adelaide\", \"air_temp\": \"14\" }";
        sendRequest("PUT", "/weather.json", body, 1); // first insert
        res = sendRequest("PUT", "/weather.json", body, 2); // update
        assertEquals(200, res.getStatusCode());
    }

    @Test
    void testPutEmptyBodyReturns204() throws Exception {
        HttpResponse res = sendRequest("PUT", "/weather.json", "", 1);
        assertEquals(204, res.getStatusCode());
    }

    @Test
    void testPutInvalidJsonReturns500() throws Exception {
        String badBody = "{ not valid json }";
        HttpResponse res = sendRequest("PUT", "/weather.json", badBody, 1);
        assertEquals(500, res.getStatusCode());
    }

    @Test
    void testPutMissingIdReturns400() throws Exception {
        String body = "{ \"name\": \"NoIdStation\" }";
        HttpResponse res = sendRequest("PUT", "/weather.json", body, 1);
        assertEquals(400, res.getStatusCode());
    }

    @Test
    void testStalePutReturns409() throws Exception {
        String body = "{ \"id\": \"IDS60903\", \"name\": \"Melbourne\" }";
        sendRequest("PUT", "/weather.json", body, 10); // new
        HttpResponse res = sendRequest("PUT", "/weather.json", body, 5);  // stale
        assertEquals(409, res.getStatusCode());
    }

    @Test
    void testGetExistingStationReturns200() throws Exception {
        String body = "{ \"id\": \"IDS60904\", \"name\": \"Perth\" }";
        sendRequest("PUT", "/weather.json", body, 1);

        HttpResponse res = sendRequest("GET", "/weather.json?id=IDS60904", null, 2);
        assertEquals(200, res.getStatusCode());
    }

    @Test
    void testGetNonexistentStationReturns404() throws Exception {
        HttpResponse res = sendRequest("GET", "/weather.json?id=DOES_NOT_EXIST", null, 1);
        assertEquals(404, res.getStatusCode());
    }

    @Test
    void testGetAllWhenEmptyReturns204() throws Exception {
        HttpResponse res = sendRequest("GET", "/weather.json", null, 1);
        // Could be 204 if server is empty
        assertTrue(res.getStatusCode() == 204 || res.getStatusCode() == 200);
    }

    @Test
    void testInvalidMethodReturns400() throws Exception {
        HttpResponse res = sendRequest("DELETE", "/weather.json", null, 1);
        assertEquals(400, res.getStatusCode());
    }
}


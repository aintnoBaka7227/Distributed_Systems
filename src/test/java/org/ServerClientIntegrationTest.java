package org;

import org.junit.jupiter.api.*;

import java.io.*;
import java.net.ServerSocket;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ServerClientIntegrationTest
 * Integration-level tests:
 * Spins up a real AggregationServer instance on a free port.
 * Uses ContentServer to PUT weather station data into the server.
 * Uses GETClient to GET data from the server.
 * Verifies persistence and recovery from disk after restart.
 * These tests ensure that the three main components AggregationServer,
 * ContentServer, and GETClient can communicate correctly.
 */

class ServerClientIntegrationTest {

    private static ExecutorService serverExecutor;
    private static int testPort;
    private static AggregationServer server;
    private static File mainFile;
    private static File tempFile;

    @BeforeAll
    static void startServer() throws Exception {
        serverExecutor = Executors.newSingleThreadExecutor();

        // Let the OS pick a free port
        try (ServerSocket s = new ServerSocket(0)) {
            testPort = s.getLocalPort();
        }

        // Use test persistence files
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

        // Wait for the server to start
        Thread.sleep(1000);
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.stopRunning();
        serverExecutor.shutdownNow();
        mainFile.delete();
        tempFile.delete();
    }

    //
    @Test
    void testContentServerPUTThenGetClientGET() throws Exception {
        // Mock data for PUT
        File stationFile = File.createTempFile("station", ".txt");
        try (PrintWriter out = new PrintWriter(stationFile)) {
            out.println("id:IDS60901");
            out.println("name:Adelaide");
            out.println("state:SA");
            out.println("lat:-34.9");
            out.println("lon:138.6");
            out.println("air_temp:21.5");
        }

        String serverUrl = "http://localhost:" + testPort;

        // Run content server and PUT data
        ContentServer cs = new ContentServer(stationFile.getAbsolutePath(), serverUrl);
        var records = cs.readStationData();
        for (var record : records) {
            cs.sendPUTRobustly(record);
        }

        // GET Client retry GET for mock data (no concurrency test here, solely for checking if PUT and GET work)
        GETClient client = new GETClient(serverUrl, "IDS60901");
        boolean success = false;
        long deadline = System.currentTimeMillis() + 5000; // 5s max wait
        while (System.currentTimeMillis() < deadline) {
            if (client.getDataRobustly()) {
                success = true;
                break;
            }
            Thread.sleep(200); // backoff
        }

        assertTrue(success, "GETClient should eventually retrieve inserted data");

        stationFile.delete();
    }



    // Test case for persistence recovery
    @Test
    void persistenceRecovery() throws Exception {
        String serverUrl = "http://localhost:" + testPort;

        // Insert a record
        File stationFile = File.createTempFile("station100", ".txt");
        try (PrintWriter wr = new PrintWriter(stationFile)) {
            wr.println("id:1");
            wr.println("name:PersistentStation1");
        }
        ContentServer cs = new ContentServer(stationFile.getAbsolutePath(), serverUrl);
        for (var rec : cs.readStationData()) {
            cs.sendPUTRobustly(rec);
        }
        stationFile.delete();

        // Restart and load the persistent file
        server.stopRunning();
        serverExecutor.shutdownNow();

        serverExecutor = Executors.newSingleThreadExecutor();
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
        Thread.sleep(1000);

        // Check if the recovery record is available
        GETClient client = new GETClient(serverUrl, "1");
        boolean ok = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (client.getDataRobustly()) {
                ok = true;
                break;
            }
            Thread.sleep(200);
        }

        assertTrue(ok, "GETClient should retrieve persisted record after restart");
    }

}

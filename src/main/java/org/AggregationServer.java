package org;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.google.gson.*;

/**
 * AggregationServer:
 *  Accepts PUTs from ContentServers and GETs from GETClients.
 *  Stores at most 20 station records in memory.
 *  Rejects stale PUTs using Lamport clocks.
 *  Expires data after 30 seconds of inactivity with periodically cleans expired records.
 *  Persists data to disk (atomic writes).
 * Usage:
 *   java AggregationServer -p <port>
 *   Default port = 4567
 */
public class AggregationServer {
    private final int PORT;                              // Server port
    private final Map<String, StationData> weatherData;  // Station data store
    private final ReentrantReadWriteLock rwLock;         // RW lock for thread safety
    private final LamportClock clock;                    // Logical clock
    private final ExecutorService pool;                  // Thread pool for client requests
    private final ScheduledExecutorService scheduler;    // Scheduler for expiry cleanup
    private final PersistenceManager persistenceManager; // Disk persistence
    private final int expiryMillis = 30_000;             // Expiry window (30s)
    private final Gson gson;                             // JSON serializer
    private static final Logger logger = Logger.getLogger(AggregationServer.class.getName());
    private volatile boolean running = true;             // Graceful shutdown flag
    private ServerSocket serverSocket;

    // Constructors
    public AggregationServer(int port) {
        this.PORT = port;
        this.weatherData = new HashMap<>();
        this.rwLock = new ReentrantReadWriteLock();
        this.clock = new LamportClock();
        this.pool = Executors.newFixedThreadPool(10);
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.persistenceManager = new PersistenceManager("main.db", "temp.db", logger);
    }

    // Constructors with persistence files for testing
    public AggregationServer(int port, String mainFile, String tempFile) {
        this.PORT = port;
        this.weatherData = new HashMap<>();
        this.rwLock = new ReentrantReadWriteLock();
        this.clock = new LamportClock();
        this.pool = Executors.newFixedThreadPool(10);
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.persistenceManager = new PersistenceManager(mainFile, tempFile, logger);
    }

    // Keep opening server socket to listen for client requests
    public void startRunning() throws IOException {
        weatherData.putAll(persistenceManager.load()); // recover persisted data
        serverSocket = new ServerSocket(PORT);

        // Periodic cleanup for expired stations
        scheduler.scheduleAtFixedRate(this::clearExpiredData, 5, 5, TimeUnit.SECONDS);

        logger.info("Aggregation Server running on port " + PORT);

        // Accept incoming clients
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                pool.submit(() -> handleRequest(clientSocket));
            } catch (SocketException e) {
                if (!running) break; // allow shutdown
                throw e;
            }
        }
    }

    // shutdown hook, clean up resources
    public void stopRunning() throws IOException {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close(); // unblocks accept()
        }
        pool.shutdownNow();
        scheduler.shutdownNow();
        logger.info("Aggregation Server stopped.");
    }

    // Request handling
    private void handleRequest(Socket clientSocket) {
        try (BufferedReader rd = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter wr = new BufferedWriter(
                     new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8))) {

            HttpRequest req = HttpRequest.parse(rd);

            // Synchronize Lamport clock
            synchronized (clock) {
                if (req.getClock() >= 0) {
                    clock.update(req.getClock());
                }
            }

            // Dispatch by method/resource
            if ("PUT".equals(req.getMethod()) && "/weather.json".equals(req.getResource())) {
                handlePUT(req, wr);
            } else if ("GET".equals(req.getMethod()) && req.getResource().startsWith("/weather.json")) {
                handleGET(req, wr);
            } else {
                sendResponse(wr, 400, "Bad Request", "{\"error\":\"Invalid HTTP method\"}");
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Client handling error", e);
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    // PUT handler (ContentServer uploads)
    private void handlePUT(HttpRequest req, BufferedWriter wr) {
        int statusCode;
        String statusMessage;
        String responseBody;

        rwLock.writeLock().lock();
        try {
            if (req.getBody() == null || req.getBody().trim().isEmpty()) {
                // No body → 204 No Content
                statusCode = 204;
                statusMessage = "No Content";
                responseBody = null;
            } else {
                JsonObject json = JsonParser.parseString(req.getBody()).getAsJsonObject();
                if (!json.has("id") || json.get("id").getAsString().isEmpty()) {
                    statusCode = 400;
                    statusMessage = "Bad Request";
                    responseBody = "{\"error\":\"Missing station ID\"}";
                } else {
                    String id = json.get("id").getAsString();
                    StationData existing = weatherData.get(id);

                    // Reject stale PUTs using Lamport clock
                    if (existing != null && req.getClock() <= existing.lamportClockValue) {
                        statusCode = 409;
                        statusMessage = "Conflict";
                        responseBody = "{\"error\":\"Stale PUT rejected\"}";
                    } else {
                        boolean isNew = (existing == null);
                        weatherData.put(id, new StationData(json, req.getClock(), System.currentTimeMillis()));

                        // Keep at most 20 records → evict oldest
                        while (weatherData.size() > 20) {
                            weatherData.entrySet().stream()
                                    .min(Comparator.comparingLong(e -> e.getValue().timestamp))
                                    .map(Map.Entry::getKey)
                                    .ifPresent(weatherData::remove);
                        }

                        // Persist after update
                        persistenceManager.save(gson.toJson(weatherData));

                        statusCode = isNew ? 201 : 200;
                        statusMessage = isNew ? "Created" : "OK";
                        responseBody = "{\"status\":\"Data stored successfully\"}";
                    }
                }
            }
        } catch (Exception e) {
            statusCode = 500;
            statusMessage = "Internal Server Error";
            responseBody = "{\"error\":\"Invalid JSON or Persistence failure\"}";
        } finally {
            rwLock.writeLock().unlock();
        }

        sendResponse(wr, statusCode, statusMessage, responseBody);
    }

    // GET handler (GETClient queries)
    private void handleGET(HttpRequest req, BufferedWriter wr) {
        int statusCode;
        String statusMessage;
        String responseBody;

        rwLock.readLock().lock();
        try {
            String resource = req.getResource();
            String queryParam = resource.contains("?") ? resource.split("\\?")[1] : null;

            if (queryParam != null && queryParam.startsWith("id=")) {
                // Specific station id request
                String stationId = queryParam.split("=")[1];
                StationData stationData = weatherData.get(stationId);

                if (stationData != null) {
                    if (System.currentTimeMillis() - stationData.timestamp > expiryMillis) {
                        // Expired
                        statusCode = 404;
                        statusMessage = "Not Found";
                        responseBody = "{\"error\":\"Station expired\"}";
                    } else {
                        statusCode = 200;
                        statusMessage = "OK";
                        responseBody = gson.toJson(stationData.data);
                    }
                } else {
                    statusCode = 404;
                    statusMessage = "Not Found";
                    responseBody = "{\"error\": \"Station ID not found\"}";
                }
            } else {
                // Request for all valid stations
                long now = System.currentTimeMillis();
                // Check for expired stations again
                Map<String, JsonObject> validStations = new HashMap<>();
                for (Map.Entry<String, StationData> entry : weatherData.entrySet()) {
                    if (now - entry.getValue().timestamp <= expiryMillis) {
                        validStations.put(entry.getKey(), entry.getValue().data);
                    }
                }

                if (validStations.isEmpty()) {
                    statusCode = 204;
                    statusMessage = "No Content";
                    responseBody = null;
                } else {
                    statusCode = 200;
                    statusMessage = "OK";
                    responseBody = gson.toJson(validStations);
                }
            }
        } catch (Exception e) {
            statusCode = 500;
            statusMessage = "Internal Server Error";
            responseBody = "{\"error\": \"Internal server error\"}";
        } finally {
            rwLock.readLock().unlock();
        }

        sendResponse(wr, statusCode, statusMessage, responseBody);
    }

    // Helper: build & send HTTP response
    private void sendResponse(BufferedWriter wr, int statusCode, String statusMessage, String body) {
        int clockValue;
        // Synchronize a Lamport clock to prevent race condition
        synchronized (clock) {
            clock.increment();       // tick on response
            clockValue = clock.getValue();
        }
        try {
            new HttpResponse(statusCode, statusMessage, body, clockValue).write(wr);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error sending response", e);
        }
    }

    // Persistence helpers
    private void persistentAtomicRewrite() throws IOException {
        String jsonData;
        rwLock.readLock().lock();
        try {
            jsonData = gson.toJson(weatherData);
        } finally {
            rwLock.readLock().unlock();
        }
        persistenceManager.save(jsonData);
    }

    private void recoverDisk() {
        weatherData.clear();
        weatherData.putAll(persistenceManager.load());
    }

    // xpiry cleanup
    private void clearExpiredData() {
        long now = System.currentTimeMillis();
        rwLock.writeLock().lock();
        try {
            // Look for expired stations and remove them using local timestamp
            Iterator<Map.Entry<String, StationData>> it = weatherData.entrySet().iterator();
            boolean removed = false;
            while (it.hasNext()) {
                Map.Entry<String, StationData> entry = it.next();
                if (now - entry.getValue().timestamp > expiryMillis) {
                    it.remove();
                    logger.info("Expired station " + entry.getKey());
                    removed = true;
                }
            }
            if (removed) {
                persistentAtomicRewrite();
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error persisting after expiry cleanup", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // Entry point
    public static void main(String[] args) throws IOException {
        int port = 4567;
        for (int i = 0; i < args.length; i++) {
            if ("-p".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            }
        }
        AggregationServer as = new AggregationServer(port);
        as.startRunning();
    }
}

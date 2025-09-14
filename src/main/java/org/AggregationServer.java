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

public class AggregationServer {
    private final int PORT;
    private final Map<String, StationData> weatherData;
    private final ReentrantReadWriteLock rwLock;
    private final LamportClock clock;
    private final ExecutorService pool;
    private final ScheduledExecutorService scheduler;
    private final PersistenceManager persistenceManager;
    private final int expiryMillis = 30_000;
    private final Gson gson;
    private static final Logger logger = Logger.getLogger(AggregationServer.class.getName());

    AggregationServer(int port) {
        this.PORT = port;
        this.weatherData = new HashMap<>();
        this.rwLock = new ReentrantReadWriteLock();
        this.clock = new LamportClock();
        this.pool = Executors.newFixedThreadPool(10);
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.persistenceManager = new PersistenceManager("main.db", "temp.db", logger);
    }

    public void startRunning() throws IOException {
        weatherData.putAll(persistenceManager.load());

        ServerSocket serverSocket = new ServerSocket(PORT);
        scheduler.scheduleAtFixedRate(this::clearExpiredData, 5, 5, TimeUnit.SECONDS);

        logger.info("Aggregation Server running on port " + PORT);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            pool.submit(() -> handleRequest(clientSocket));
        }
    }

    private void handleRequest(Socket clientSocket) {
        try (BufferedReader rd = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter wr = new BufferedWriter(
                     new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8))) {

            HttpRequest req = HttpRequest.parse(rd);

            synchronized (clock) {
                if (req.getClock() >= 0) {
                    clock.update(req.getClock());
                }
            }

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

    private void handlePUT(HttpRequest req, BufferedWriter wr) {
        rwLock.writeLock().lock();
        try {
            if (req.getBody() == null || req.getBody().trim().isEmpty()) {
                // No content in the PUT request
                sendResponse(wr, 204, "No Content", null);
                return;
            }

            JsonObject json = JsonParser.parseString(req.getBody()).getAsJsonObject();
            if (!json.has("id") || json.get("id").getAsString().isEmpty()) {
                sendResponse(wr, 400, "Bad Request", "{\"error\":\"Missing station ID\"}");
                return;
            }

            String id = json.get("id").getAsString();
            StationData existing = weatherData.get(id);

            if (existing != null && req.getClock() < existing.lamportClockValue) {
                sendResponse(wr, 409, "Conflict", "{\"error\":\"Stale PUT rejected\"}");
                return;
            }

            boolean isNew = (existing == null);
            weatherData.put(id, new StationData(json, req.getClock(), System.currentTimeMillis()));

            if (weatherData.size() > 20) {
                weatherData.entrySet().stream()
                        .min(Comparator.comparingLong(e -> e.getValue().timestamp))
                        .map(Map.Entry::getKey).ifPresent(weatherData::remove);
            }

            persistenceManager.save(gson.toJson(weatherData));

            sendResponse(wr, isNew ? 201 : 200, isNew ? "Created" : "OK",
                    "{\"status\":\"Data stored successfully\"}");

        } catch (Exception e) {
            sendResponse(wr, 500, "Internal Server Error", "{\"error\":\"Invalid JSON or Persistence failure\"}");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void handleGET(HttpRequest req, BufferedWriter wr) {
        rwLock.readLock().lock();
        try {
            String resource = req.getResource();
            String queryParam = resource.contains("?") ? resource.split("\\?")[1] : null;

            if (queryParam != null && queryParam.startsWith("id=")) {
                String stationId = queryParam.split("=")[1];
                StationData stationData = weatherData.get(stationId);

                if (stationData != null) {
                    if (System.currentTimeMillis() - stationData.timestamp > expiryMillis) {
                        weatherData.remove(stationId);
                        sendResponse(wr, 404, "Not Found", "{\"error\":\"Station expired\"}");
                    } else {
                        sendResponse(wr, 200, "OK", gson.toJson(stationData.data));
                    }
                } else {
                    sendResponse(wr, 404, "Not Found", "{\"error\": \"Station ID not found\"}");
                }
                return;
            }

            long now = System.currentTimeMillis();
            Map<String, JsonObject> validStations = new HashMap<>();
            for (Map.Entry<String, StationData> entry : weatherData.entrySet()) {
                if (now - entry.getValue().timestamp <= expiryMillis) {
                    validStations.put(entry.getKey(), entry.getValue().data);
                }
            }

            if (validStations.isEmpty()) {
                sendResponse(wr, 204, "No Content", null);
            } else {
                sendResponse(wr, 200, "OK", gson.toJson(validStations));
            }

        } catch (Exception e) {
            sendResponse(wr, 500, "Internal Server Error", "{\"error\": \"Internal server error\"}");
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private void sendResponse(BufferedWriter wr, int statusCode, String statusMessage, String body) {
        int clockValue;
        synchronized (clock) {
            clock.increment();
            clockValue = clock.getValue();
        }
        try {
            new HttpResponse(statusCode, statusMessage, body, clockValue).write(wr);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error sending response", e);
        }
    }


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


    private void clearExpiredData() {
        long now = System.currentTimeMillis();
        rwLock.writeLock().lock();
        try {
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

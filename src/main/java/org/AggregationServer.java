package org;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
        try {
            // Recover persisted data before starting
            recoverDisk();

            ServerSocket serverSocket = new ServerSocket(PORT);

            // Schedule expiry task every 5 seconds
            scheduler.scheduleAtFixedRate(this::clearExpiredData, 5, 5, java.util.concurrent.TimeUnit.SECONDS);

            logger.info("Aggregation Server running on port " + PORT);

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    pool.submit(() -> handleRequest(clientSocket));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private void handleRequest(Socket clientSocket) {
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter wr = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8))) {

            String requestLine = rd.readLine();
            if (requestLine == null || requestLine.split(" ").length < 2) {
                sendResponse(wr, 400, "Bad Request", "{\"error\":\"Invalid request line\"}");
                return;
            }

            String method = requestLine.split(" ")[0];
            String resource = requestLine.split(" ")[1];

            // --- Read headers ---
            int contentLength = 0;
            int clientClock = -1;
            String header;
            while ((header = rd.readLine()) != null && !header.isEmpty()) {
                if (header.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(header.split(":")[1].trim());
                } else if (header.toLowerCase().startsWith("clock:")) {
                    clientClock = Integer.parseInt(header.split(":")[1].trim());
                }
            }

            // --- Update server Lamport clock on receive ---
            synchronized (clock) {
                if (clientClock >= 0) {
                    clock.update(clientClock);
                }
                // no increment yet — increment only in sendResponse
                logger.info("Received request from " + clientSocket.getInetAddress() + ", update clock value: " + clock.getValue());
            }

            // --- Dispatch by method ---
            if ("PUT".equals(method) && "/weather.json".equals(resource)) {
                char[] buf = new char[contentLength];
                int read = rd.read(buf);
                if (read <= 0) {
                    sendResponse(wr, 204, "No Content", null);
                    return;
                }
                String body = new String(buf, 0, read);
                handlePUT(body, wr, clientClock);

            } else if ("GET".equals(method) && resource.startsWith("/weather.json")) {
                String query = resource.contains("?") ? resource.substring(resource.indexOf("?") + 1) : null;
                handleGET(query, wr);

            } else {
                sendResponse(wr, 400, "Bad Request", "{\"error\":\"Invalid HTTP method\"}");
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Client handling error", e);
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void handlePUT(String body, BufferedWriter wr, int clientClock) {
        rwLock.writeLock().lock();
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            // Validate station id
            if (!json.has("id") || json.get("id").getAsString().isEmpty()) {
                sendResponse(wr, 400, "Bad Request", "{\"error\":\"Missing station ID\"}");
                return;
            }

            String id = json.get("id").getAsString();
            StationData existing = weatherData.get(id);

            // Check for stale update
            if (existing != null && clientClock < existing.lamportClockValue) {
                sendResponse(wr, 409, "Conflict", "{\"error\":\"Stale PUT rejected\"}");
                return;
            }

            boolean isNew = (existing == null);
            weatherData.put(id, new StationData(json, clientClock, System.currentTimeMillis()));

            int storageLimit = 20;
            if (weatherData.size() > storageLimit) {
                weatherData.entrySet().stream()
                        .min(Comparator.comparingLong(e -> e.getValue().timestamp))
                        .map(Map.Entry::getKey).ifPresent(weatherData::remove);
            }

            persistentAtomicRewrite();

            sendResponse(wr, isNew ? 201 : 200, isNew ? "Created" : "OK",
                    "{\"status\":\"Data stored successfully\"}");

        } catch (JsonSyntaxException e) {
            sendResponse(wr, 500, "Internal Server Error", "{\"error\":\"Invalid JSON\"}");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Persistence error", e);
            sendResponse(wr, 500, "Internal Server Error", "{\"error\":\"Persistence failed\"}");
        } finally {
            rwLock.writeLock().unlock();
        }
    }




    private void handleGET(String resource, BufferedWriter wr) {
        rwLock.readLock().lock();
        try {
            String[] parts = (resource != null) ? resource.split("\\?") : new String[0];
            String queryParam = (parts.length > 1) ? parts[1] : null;


            if (queryParam != null && queryParam.startsWith("id=")) {
                String stationId = queryParam.split("=")[1];

                if (!stationId.isEmpty()) {
                    StationData stationData = weatherData.get(stationId);

                    if (stationData != null) {
                        if (System.currentTimeMillis() - stationData.timestamp > expiryMillis) {
                            weatherData.remove(stationId); // remove expired
                            sendResponse(wr, 404, "Not Found", "{\"error\":\"Station expired\"}");
                        } else {
                            String jsonResponse = gson.toJson(stationData.data);
                            sendResponse(wr, 200, "OK", jsonResponse);
                        }
                    } else {
                        sendResponse(wr, 404, "Not Found",
                                "{\"error\": \"Station ID not found\"}");
                    }
                    return;
                }
            }

            // Case 2: return all stations (no query or empty id=)
            long now = System.currentTimeMillis();
            Map<String, JsonObject> validStations = new HashMap<>();
            for (Map.Entry<String, StationData> entry : weatherData.entrySet()) {
                if (now - entry.getValue().timestamp <= expiryMillis) {
                    validStations.put(entry.getKey(), entry.getValue().data);
                }
            }

            if (validStations.isEmpty()) {
                sendResponse(wr, 204, "No Content", null);
                return;
            }

            String jsonResponse = gson.toJson(validStations);
            sendResponse(wr, 200, "OK", jsonResponse);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error processing GET request", e);
            sendResponse(wr, 500, "Internal Server Error",
                    "{\"error\": \"Internal server error\"}");
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private void sendResponse(BufferedWriter wr, int statusCode, String statusMessage, String body) {
        int clockValue;
        synchronized (clock) {
            clock.increment();              // increment once per response
            clockValue = clock.getValue();  // snapshot
            logger.info("Sending response, update clock value: " + clockValue);
        }

        try {
            wr.write("HTTP/1.1 " + statusCode + " " + statusMessage + "\r\n");
            wr.write("Content-Type: application/json\r\n");
            wr.write("Clock: " + clockValue + "\r\n");
            wr.write("\r\n");
            if (body != null) wr.write(body);
            wr.flush();
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

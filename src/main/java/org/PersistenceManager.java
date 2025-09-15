package org;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PersistenceManager handles saving and loading station data
 * to and from the disk in a safe and atomic way.
 * Uses a temporary file when writing to prevent corruption if
 * a crash occurs during save before renaming to a main file.
 *
 */
public class PersistenceManager {
    private final File mainFile;   // main persistence file
    private final File tempFile;   // temporary file for updating
    private final Logger logger;   // logger for info/warnings/errors
    private final Gson gson;       // JSON library for (de)serialization

    /**
     * Constructor
     * @param mainFileName Path to the main file
     * @param tempFileName Path to the temporary file
     * @param logger       Logger instance for logging operations
     */
    public PersistenceManager(String mainFileName, String tempFileName, Logger logger) {
        this.mainFile = new File(mainFileName);
        this.tempFile = new File(tempFileName);
        this.logger = logger;
        this.gson = new GsonBuilder().setPrettyPrinting().create(); // pretty JSON formatting
    }

    /**
     * Store the weather data to disk atomically.
     * Process:
     *  1. Write data to a temporary file.
     *  2. Force sync to disk for safeness.
     *  3. Atomically replace the outdated main file with the temp file.
     * @param jsonData JSON string representing station data
     * @throws IOException if writing to disk fails
     */
    public void save(String jsonData) throws IOException {
        logger.info("Saving data to " + mainFile.getAbsolutePath());

        // Step 1: write to a temporary file
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(jsonData);
            writer.flush();
            fos.getFD().sync(); // ensure durability on disk
        }

        // Step 2: atomically replace the main file with the temp file
        Files.move(tempFile.toPath(), mainFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

        logger.info("Data saved successfully (" + jsonData.length() + " chars).");
    }

    /**
     * Load saved weather data from the main file.
     * @return A map of station IDs to StationData objects.
     * If the main file does not exist, it returns an empty map.
     * Always discard the temp file if it exists.
     */
    public Map<String, StationData> load() {
        if (!mainFile.exists()) {
            logger.warning("No persistence file found at " + mainFile.getAbsolutePath() + ". Starting fresh.");
            return new HashMap<>();
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(mainFile), StandardCharsets.UTF_8))) {

            // Read the file content into a string
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            // Convert raw JSON into a Map<String, StationData>
            Type type = new TypeToken<Map<String, StationData>>() {}.getType();
            Map<String, StationData> restored = gson.fromJson(sb.toString(), type);

            if (restored != null) {
                logger.info("Recovered " + restored.size() + " records from " + mainFile.getName());
                return restored;
            } else {
                logger.warning("Persistence file was empty or invalid JSON.");
            }

        } catch (JsonSyntaxException e) {
            logger.log(Level.SEVERE, "Persistence file contains malformed JSON. Starting fresh.", e);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error reading persistence file " + mainFile.getAbsolutePath(), e);
        }

        // Return an empty map if main file is corrupted or empty
        return new HashMap<>();
    }
}

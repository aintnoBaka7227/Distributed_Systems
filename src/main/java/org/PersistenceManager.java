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

public class PersistenceManager {
    private final File mainFile;
    private final File tempFile;
    private final Logger logger;
    private final Gson gson;

    public PersistenceManager(String mainFileName, String tempFileName, Logger logger) {
        this.mainFile = new File(mainFileName);
        this.tempFile = new File(tempFileName);
        this.logger = logger;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /** Save the weather data atomically */
    public void save(String jsonData) throws IOException {
        logger.info("Saving data to " + mainFile.getAbsolutePath());

        // Write to temp file first
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(jsonData);
            writer.flush();
            fos.getFD().sync(); // ensure durability
        }

        // Replace main file atomically
        Files.move(tempFile.toPath(), mainFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);

        logger.info("Data saved successfully (" + jsonData.length() + " chars).");
    }

    /** Load saved weather data from disk */
    public Map<String, StationData> load() {
        if (!mainFile.exists()) {
            logger.warning("No persistence file found at " + mainFile.getAbsolutePath() + ". Starting fresh.");
            return new HashMap<>();
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(mainFile), StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

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

        return new HashMap<>();
    }
}



package org;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
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

        // Write to temp
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(jsonData);
            writer.flush();
            fos.getFD().sync();
        }

        // Replace atomically
        Files.move(tempFile.toPath(), mainFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** Load saved weather data from disk */
    public Map<String, StationData> load() {
        if (!mainFile.exists()) return new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(mainFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            Type type = new TypeToken<Map<String, StationData>>() {}.getType();
            Map<String, StationData> restored = gson.fromJson(sb.toString(), type);

            if (restored != null) {
                logger.info("Recovered " + restored.size() + " records from disk");
                return restored;
            }
        } catch (IOException e) {
            logger.severe("Error recovering from disk: " + e.getMessage());
        }
        return new HashMap<>();
    }
}


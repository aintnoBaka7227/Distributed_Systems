package org;

import org.junit.jupiter.api.*;

import java.io.File;
import java.io.FileWriter;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PersistenceManagerTest
 * Unit tests for PersistenceManager to ensure safe saving and loading
 * of station data with atomic writes:
 * Ensures valid JSON is saved and restored correctly
 * Handles missing files by return empty map
 * Handles malformed JSON safely
 * Overwrites previous data properly
 * Uses a temporary file to guarantee atomic persistence
 */

class PersistenceManagerTest {

    private File mainFile;
    private File tempFile;
    private PersistenceManager pm;

    // Setup main and temp files before each test
    // Delete main to simulate a clean start
    @BeforeEach
    void setup() throws Exception {
        mainFile = File.createTempFile("weather", ".db");
        tempFile = File.createTempFile("weather_temp", ".db");

        mainFile.delete();
        pm = new PersistenceManager(mainFile.getAbsolutePath(),
                tempFile.getAbsolutePath(),
                Logger.getAnonymousLogger());
    }

    // Delete main and temp files after each test
    @AfterEach
    void clear() {
        if (mainFile.exists()) mainFile.delete();
        if (tempFile.exists()) tempFile.delete();
    }

    // Test cases for saving and loading valid JSON
    @Test
    void testSaveAndLoadValidJson() throws Exception {
        String json = "{ \"IDS60901\": { \"data\": {\"id\":\"IDS60901\",\"name\":\"Adelaide\"}, " +
                "\"lamportClockValue\": 0, \"timestamp\": 0 } }";
        pm.save(json);

        Map<String, StationData> result = pm.load();
        assertEquals(1, result.size());
        assertTrue(result.containsKey("IDS60901"));
        StationData adelaide = result.get("IDS60901");
        assertEquals("IDS60901", adelaide.data.get("id").getAsString());
        assertEquals("Adelaide", adelaide.data.get("name").getAsString());

    }

    // Test cases for loading empty JSON
    @Test
    void testLoadWhenNoFileExists() {
        Map<String, StationData> result = pm.load();
        assertTrue(result.isEmpty(), "Should return empty map if no persistence file");
    }

    // Test cases for restoring malfunction JSON from the main file
    @Test
    void testLoadInvalidJson() throws Exception {
        try (FileWriter writer = new FileWriter(mainFile)) {
            writer.write("{ invalid json !!! ");
        }

        Map<String, StationData> result = pm.load();
        assertTrue(result.isEmpty(), "Should return empty map if JSON malformed");
    }

    // Test cases for overwriting previous data
    @Test
    void testOverwritePreviousData() throws Exception {
        String json1 = "{ \"1\": { \"id\": \"1\", \"name\": \"Phong\" } }";
        String json2 = "{ \"2\": { \"id\": \"2\", \"name\": \"Ingrid\" } }";

        pm.save(json1);
        assertTrue(pm.load().containsKey("1"));

        pm.save(json2);
        assertTrue(pm.load().containsKey("2"));
    }

    // Test cases for saving to temp file -> write to the main file when done
    // The temp file should be deleted after save
    @Test
    void testTempFileIsUsed() throws Exception {
        String json = "{ \"1\": { \"id\": \"1\", \"name\": \"Phong\" } }";
        pm.save(json);

        assertFalse(tempFile.exists(), "Temp file should be moved to main file");
        assertTrue(mainFile.exists(), "Main file should exist after save");
    }


}

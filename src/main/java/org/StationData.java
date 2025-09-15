package org;

import com.google.gson.JsonObject;

/**
 * A class to represent each weather station's data.
 * Stores station content as raw JSON data, with associated Lamport clock value,
 * and a timestamp when the data was recorded.
 */

public class StationData {
    public JsonObject data;
    public int lamportClockValue;
    long timestamp;

    /**
     * Constructor to initialize a station data record.
     * @param data              The weather station data as a JSON object
     * @param lamportClockValue The Lamport clock value associated with this record
     * @param timestamp         The timestamp in the currentTimeMillis ()
     */
    StationData(JsonObject data, int lamportClockValue, long timestamp) {
        this.data = data;
        this.lamportClockValue = lamportClockValue;
        this.timestamp = timestamp;
    }
}

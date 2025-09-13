package org;

import com.google.gson.JsonObject;

public class StationData {
    public JsonObject data;
    public int lamportClockValue;
    long timestamp;

    StationData(JsonObject data, int lamportClockValue, long timestamp) {
        this.data = data;
        this.lamportClockValue = lamportClockValue;
        this.timestamp = timestamp;
    }
}

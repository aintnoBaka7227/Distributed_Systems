package org;

import java.util.Random;

/**
 * Simulates network conditions for a member: latency, drops, and crash trigger
 * Can be updated at runtime
 */
public class MemberProfile {
    private volatile int minLatencyMs;
    private volatile int maxLatencyMs;
    private volatile double dropRate;
    private volatile String latencyType;
    private final Random random = new Random();

    private MemberProfile(String latencyType, int minLatencyMs, int maxLatencyMs, double dropRate) {
        this.latencyType = latencyType;
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.dropRate = dropRate;
    }

    /**
     * Create a profile by name
     * reliable: near-zero latency, no drops
     * latent: high variable latency, occasional drops
     * failure: moderate latency, higher drops with crash command
     * standard: moderate latency, low drops
     * @param name profile name
     * @return profile
     */
    public static MemberProfile setLatency(String latencyName) {
        String n = latencyName == null ? "standard" : latencyName.toLowerCase();
        switch (n) {
            case "reliable":
                return new MemberProfile("reliable", 0, 10, 0.0);
            case "latent":
                return new MemberProfile("latent", 200, 1500, 0.05);
            case "failure":
                return new MemberProfile("failure", 100, 600, 0.15);
            case "standard":
            default:
                return new MemberProfile("standard", 50, 400, 0.02);
        }
    }

    /**
     * Copy values from another profile (runtime reconfiguration helper).
     * @param other other profile
     */
    public void updateFrom(MemberProfile other) {
        this.latencyType = other.latencyType;
        setLatency(other.minLatencyMs, other.maxLatencyMs);
        setDropRate(other.dropRate);
    }

    /** Simulate latency and determine if a message should be dropped */
    public boolean beforeNetworkAction() {
        // drop first to avoid wasting time
        if (random.nextDouble() < dropRate) return false;
        int delay = minLatencyMs;
        if (maxLatencyMs > minLatencyMs) {
            delay += random.nextInt(maxLatencyMs - minLatencyMs + 1);
        }
        if (delay > 0) {
            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
        }
        return true;
    }

    /** Update latency range at runtime */
    public void setLatency(int minMs, int maxMs) {
        if (minMs < 0 || maxMs < minMs) throw new IllegalArgumentException("Invalid latency range");
        this.minLatencyMs = minMs;
        this.maxLatencyMs = maxMs;
    }

    /** Update drop probability at runtime */
    public void setDropRate(double rate) {
        if (rate < 0.0 || rate > 1.0) throw new IllegalArgumentException("Drop must be 0..1");
        this.dropRate = rate;
    }

    public String getLatencyType() {
        return latencyType;
    }

    public int getMinLatencyMs() { return minLatencyMs; }
    public int getMaxLatencyMs() { return maxLatencyMs; }
    public double getDropRate() { return dropRate; }
}

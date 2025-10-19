package org;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemberProfileTest:
 * Validates latency/drop configuration behavior and runtime effects of MemberProfile
 * Test preset selection, latency bounds, drop probabilities, and live updates
 */
class MemberProfileTest {
    
    /** preset profiles initialize expected latency and drop parameters; null falls back to 'standard' */
    @Test
    void profileIsSetupAsExpected() {
        MemberProfile reliable = MemberProfile.setLatency("reliable");
        assertEquals("reliable", reliable.getLatencyType());
        assertEquals(0, reliable.getMinLatencyMs());
        assertEquals(10, reliable.getMaxLatencyMs());
        assertEquals(0.0, reliable.getDropRate(), 1e-9);

        MemberProfile standard = MemberProfile.setLatency("standard");
        assertEquals("standard", standard.getLatencyType());

        MemberProfile fallback = MemberProfile.setLatency(null);
        assertEquals("standard", fallback.getLatencyType());
    }

    /**
     * beforeNetworkAction delays fall within configured [min,max] bounds when dropRate=0
     * Adds a modest sample size to reduce timing noise
     */
    @Test
    void delaysStayWithinConfiguredBounds() {
        MemberProfile p = MemberProfile.setLatency("standard");
        p.setLatency(10, 30);
        p.setDropRate(0.0);

        long minObs = Long.MAX_VALUE, maxObs = Long.MIN_VALUE;

        // Call beforeNetworkAction repeatedly, measure elapsed time in ms
        for (int i = 0; i < 80; i++) {
            long t0 = System.nanoTime();
            boolean proceed = p.beforeNetworkAction();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertTrue(proceed, "dropRate=0 should never drop");

            minObs = Math.min(minObs, elapsedMs);
            maxObs = Math.max(maxObs, elapsedMs);
        }
        // Observed range stays within configured envelope
        assertTrue(minObs >= 10 && maxObs <= 30, "Observed delay should be within [10,30] ms");
    }

    /** Zero-latency configuration returns quickly and does not oversleep */
    @Test
    void zeroLatencyDoesNotSleepTooLong() {
        MemberProfile p = MemberProfile.setLatency("standard");
        p.setLatency(0, 0);
        p.setDropRate(0.0);

        long t0 = System.nanoTime();
        boolean proceed = p.beforeNetworkAction();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertTrue(proceed);
        assertTrue(elapsedMs <= 5, "With latency 0..0, elapsed should be near 0ms (allow small jitter)");
    }

    /** With dropRate=0.0, no calls to beforeNetworkAction are dropped */
    @Test
    void noDropForRateZero() {
        MemberProfile p = MemberProfile.setLatency("standard");
        p.setLatency(0, 0);
        p.setDropRate(0.0);

        int drops = 0;
        for (int i = 0; i < 200; i++) {
            if (!p.beforeNetworkAction()) drops++;
        }
        assertEquals(0, drops, "dropRate=0.0 should never drop");
    }

    /** With dropRate=1.0, all calls to beforeNetworkAction are dropped */
    @Test
    void mustDropForRateOne() {
        MemberProfile p = MemberProfile.setLatency("standard");
        p.setLatency(0, 0);
        p.setDropRate(1.0);

        int keeps = 0;
        for (int i = 0; i < 50; i++) {
            if (p.beforeNetworkAction()) keeps++;
        }
        assertEquals(0, keeps, "dropRate=1.0 should always drop");
    }

    /** updateFrom copies latency type, range, and drop rate at runtime */
    @Test
    void updateFromCopiesValues() {
        MemberProfile base = MemberProfile.setLatency("reliable");
        MemberProfile src  = MemberProfile.setLatency("latent");

        base.updateFrom(src);
        assertEquals(src.getLatencyType(), base.getLatencyType());
        assertEquals(src.getMinLatencyMs(), base.getMinLatencyMs());
        assertEquals(src.getMaxLatencyMs(), base.getMaxLatencyMs());
        assertEquals(src.getDropRate(), base.getDropRate(), 1e-9);
    }

    /** setLatency enforces non-negative, ordered bounds; accepts valid ranges */
    @Test
    void setLatencyValidatesRange() {
        MemberProfile p = MemberProfile.setLatency("standard");
        p.setLatency(0, 0);
        assertEquals(0, p.getMinLatencyMs());
        assertEquals(0, p.getMaxLatencyMs());

        assertThrows(IllegalArgumentException.class, () -> p.setLatency(-1, 0));
    }
}

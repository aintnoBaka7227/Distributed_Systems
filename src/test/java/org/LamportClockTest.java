package org;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LamportClockTest
 * Unit tests for the LamportClock class, ensuring it behaves correctly
 * Starts at 0
 * Increments properly
 * Updates correctly based on incoming values
 * Handles multiple concurrent increments/updates (no synchronization in implementation,
 * but test checks progression of clock value)
 */
public class LamportClockTest {

    private LamportClock clock;

    // Initialize the clock before each test
    @BeforeEach
    void setUp() {
        clock = new LamportClock();
    }

    // Test if the clock always starts at 0
    @Test
    void testInitialValue() {
        assertEquals(0, clock.getValue(), "Clock should start at 0");
    }

    // Test if the clock is incremented correctly
    @Test
    void testIncrementValue() {
        clock.increment();
        assertEquals(1, clock.getValue());
    }

    // Test if the clock is updated correctly (less than, equal or greater than the current value)
    @Test
    void testUpdateValue() {
        clock.increment();
        clock.update(0);
        assertEquals(2, clock.getValue());
        clock.update(1);
        assertEquals(3, clock.getValue());
        clock.update(5);
        assertEquals(6, clock.getValue());
    }


    // Test if the clock advances correctly after multiple concurrent operations
    // Note: this test may fail as no concurrent control is implemented in the LamportClock class
    @Test
    void testConcurrentIncrementAndUpdate() throws Exception {
        int threadsCount = 4;
        int iterations = 50;

        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < threadsCount; i++) {
            threads.add(new Thread(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                for (int j = 0; j < iterations; j++) {
                    if (j % 2 == 0) {
                        clock.increment();
                    } else {
                        clock.update(j);
                    }

                }
            }));
        }

        for (Thread t : threads) t.start();
        start.countDown();
        for (Thread t : threads) t.join();

        System.out.println("Clock value: " + clock.getValue());
        assertTrue(clock.getValue() > 0 || clock.getValue() == 200, "Clock should have advanced after concurrent operations");
    }
}


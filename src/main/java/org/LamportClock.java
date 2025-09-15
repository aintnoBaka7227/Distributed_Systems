package org;

/**
 * Implementation of a Lamport logical clock.
 * It ensures that causally related events are given consistent ordering numbers.
 */
public class LamportClock {
    /**
     * Current clock value
     */
    private int value;

    /**
     * Initialize the clock with a value of zero.
     */
    public LamportClock() {
        this.value = 0;
    }

    /**
     * @return the current Lamport clock value
     */
    public int getValue() {
        return value;
    }

    /**
     * Increment the clock for a local event by 1
     */
    public void increment() {
        value++;
    }

    /**
     * Update the clock based on an incoming Lamport clock value.
     * new clock value = max(local, incoming) + 1
     *
     * @param incomingValue the Lamport clock value received from another process
     */
    public void update(int incomingValue) {
        value = Math.max(incomingValue, value) + 1;
    }
}



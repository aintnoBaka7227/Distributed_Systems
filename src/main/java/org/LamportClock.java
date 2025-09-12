package org;

public class LamportClock {
    private int value;

    public LamportClock() {
        this.value = 0;
    }

    public int getValue() {
        return value;
    }

    public void increment() {
        value++;
    }

    public void update(int incomingValue) {
        value = Math.max(incomingValue, value) + 1;
    }
}


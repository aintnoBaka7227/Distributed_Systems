package org.example;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;

/*
    Server-side implementation of the Calculator interface.
    Currently, it will only use a single stack for all clients.
*/

public class CalculatorImplementation extends UnicastRemoteObject implements Calculator {

    private final Deque<Integer> storage;

    public CalculatorImplementation() throws RemoteException {
        super();
        storage = new ArrayDeque<>();
    }

    @Override
    public synchronized void pushValue(int val) throws RemoteException {
        storage.push(val);
        System.out.println("Pushed " + storage.getFirst() + " to calculator");
    }

    @Override
    public synchronized boolean isEmpty() throws RemoteException {
        return storage.isEmpty();
    }

    @Override
    public synchronized int pop() throws RemoteException {
        return storage.pop();
    }

    @Override
    public synchronized void pushOperation(String operator) throws RemoteException {
        int result = pop();
        while (!isEmpty()) {
            int topValue = pop();
            result = switch (operator) {
                case "min" -> Math.min(result, topValue);
                case "max" -> Math.max(result, topValue);
                case "lcm" -> lcm(result, topValue);
                case "gcd" -> gcd(result, topValue);
                default -> throw new RemoteException("Unknown operator: " + operator);
            };
        }

        pushValue(result);
        System.out.println("Pushed " + storage.getLast() + " to calculator");
    }

    private int gcd(int a, int b) {
        if (b == 0) {
            return a;
        }
        return gcd(b, a % b);
    }

    private int lcm(int a, int b) {
        return Math.max(a, b) / gcd(a, b);
    }

    @Override
    public synchronized int delayPop(int millis) throws RemoteException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RemoteException(ie.getMessage());
        }

        return pop();
    }

}

package org.example;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Server-side implementation of the Calculator interface.
 * Currently, it will only use a single stack for all clients.
 */

public class CalculatorImplementation extends UnicastRemoteObject implements Calculator {

    /**
     * Defining the stack using concurrent linked deque for thread safety
     * Use to store the input values from multiple clients
     */

    private final ConcurrentLinkedDeque<Integer> storage;

    public CalculatorImplementation() throws RemoteException {
        super();
        storage = new ConcurrentLinkedDeque<>();
    }

    // synchronization is used to ensure only one thread can access the stack at one time -> prevent race condition
    /**
     * Push a number onto the stack
     * @param val integer value to add to stack
     * @throws RemoteException if RMI communication fails
     */

    @Override
    public synchronized void pushValue(int val) throws RemoteException {
        storage.push(val);
        System.out.println("Pushed " + storage.getFirst() + " to calculator");
    }

    /**
     * Check if the stack is empty
     * @return true if empty, false otherwise
     * @throws RemoteException if RMI communication fails
     */

    @Override
    public synchronized boolean isEmpty() throws RemoteException {
        if (storage.isEmpty()) {
            System.out.println("Stack is empty");
        } else {
            System.out.println("Stack is not empty");
        }
        return storage.isEmpty();
    }

    /**
     * Pop the top value from the stack
     * @return the popped integer
     * @throws RemoteException if RMI communication fails
     */

    @Override
    public synchronized int pop() throws RemoteException {
        System.out.println("Popped " + storage.getLast() + " from calculator");
        return storage.pop();
    }

    /**
     * Push an operation onto the stack, then consume the stack and push the result
     * Supported operators: "min", "max", "lcm", "gcd"
     * @param operator operator to apply to all values on the stack
     * @throws RemoteException if RMI communication fails
     */

    @Override
    public synchronized void pushOperation(String operator) throws RemoteException {
        int result = pop();
        while (!isEmpty()) {
            switch (operator) {
                case "min" -> result = Math.min(result, pop());
                case "max" -> result = Math.max(result, pop());
                case "lcm" -> result = lcm(result, pop());
                case "gcd" -> result = gcd(result, pop());
                default -> {
                    pushValue(result);
                    throw new RemoteException("Unknown operator. Try again");
                }
            }
        }

        pushValue(result);
        System.out.println("Pushed " + storage.getLast() + " to calculator");
    }

    /**
     * Compute the greatest common divisor of two integers
     * @param a first integer
     * @param b second integer
     * @return gcd(a, b)
     */

    private int gcd(int a, int b) {
        if (b == 0) {
            return a;
        }
        return gcd(b, a % b);
    }

    /**
     * Compute the least common multiple of two integers
     * @param a first integer
     * @param b second integer
     * @return lcm(a, b)
     */

    private int lcm(int a, int b) {
        return Math.abs(a / gcd(a, b) * b);
    }

    /**
     * Pop the top value from the stack after a delay using Thread.sleep()
     * @param millis delay in milliseconds before popping
     * @return the popped integer
     * @throws RemoteException if RMI communication fails
     */

    @Override
    public synchronized int delayPop(int millis) throws RemoteException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RemoteException(ie.getMessage());
        }
        System.out.println("Popped " + storage.getLast() + " from calculator");
        return pop();
    }

}

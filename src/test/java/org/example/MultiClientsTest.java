package org.example;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;
import java.util.concurrent.*;

/**
 * Multi-clients integration tests for the Calculator.
 * Description:
 * Starts a local RMI registry once for the entire class and binds a single remote object that all clients share.
 * Use multiple threads to simulate multiple clients pushing values concurrently while each thread has its own stub.
 * TestInstance with PER_CLASS allows 1 single class object used across all test methods, which serves the above purpose.
 * pushValue and delayPop are run concurrently.
 * pushOperation is executed sequentially due to each operation drains the whole stack.
 */

@TestInstance(TestInstance.Lifecycle.PER_CLASS)

class MultiClientsTest {
    // Share a server that its shared stack needs to be emptied before each test
    private CalculatorImplementation serverImpl;

    /**
     * Set up a stub for each client (thread)
     * @return a remote Calculator proxy
     * @throws Exception if the lookup fails due to registry not started or name not bound
     */
    private Calculator getCalculator() throws Exception {
        return (Calculator) LocateRegistry.getRegistry("localhost", 1099).lookup("CalculatorTest");
    }

    /**
     * Replicate a single "CalculatorServer" used across all tests to avoid depending on an external server process.
     * @throws Exception if registry creation or binding fails
     */
    @BeforeAll
    void testServer() throws Exception {
        Registry registry = LocateRegistry.createRegistry(1099);
        serverImpl = new CalculatorImplementation();
        registry.rebind("CalculatorTest", serverImpl);
    }

    /**
     * Reset the server's shared stack before each test through a direct call to the serverImpl.
     * @throws Exception if a remote call fails (not expected when using serverImpl directly)
     */
    @BeforeEach
    void resetStack() throws Exception {
        while (true) {
            // Synchronize on the serverImpl to ensure no other accesses
            synchronized (serverImpl) {
                if (serverImpl.isEmpty()) break;
                serverImpl.pop();
            }
        }
    }

    /**
     * Test pushValue concurrently from multiple threads.
     * Description:
     * Initialize a fixed-size thread pool, each thread has its own stub
     * Each client pushes a unique set of integers.
     * Compare what each task claims it pushed with the expected set.
     * Reset the server stack and assert that all pushed values are present.
     */
    @Test
    void testMultiClientPushValue() throws Exception {
        // In this test, we simulate 5 clients pushing 20 values each concurrently
        int clients = 5;
        int numsValue = 20;
        // Create a thread pool of 5 threads
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        // Use future to track each client's pushed values'
        List<Future<List<Integer>>> futures = new ArrayList<>();

        for (int i = 1; i <= clients; i++) {
            // Each client has a unique id
            final int clientID = i;
            // Submit a callable task to the thread pool and collect values
            futures.add(pool.submit(() -> {
                Calculator calc = getCalculator();
                // Track what each client pushed
                List<Integer> pushedVals = new ArrayList<>();
                // Generate a unique set of values for each client as the order is not fixed due to concurrency
                for (int j = 0; j < numsValue; j++) {
                    int val = clientID * 10_000 + j;
                    calc.pushValue(val);
                    pushedVals.add(val);
                }
                return pushedVals;
            }));
        }

        // Build the expected multiset of values pushed by all threads
        Set<Integer> expectedVals = new HashSet<>();
        for (int i = 1; i <= clients; i++) {
            for (int j = 0; j < numsValue; j++) {
                expectedVals.add(i * 10_000 + j);
            }
        }

        // Record the values reported by each concurrent thread
        Set<Integer> actualVals = new HashSet<>();
        try {
            for (Future<List<Integer>> future : futures) {
                // Timeout to prevent test hanging due to a thread freezes
                actualVals.addAll(future.get(20, TimeUnit.SECONDS));
            }
        } finally {
            // Clean up pool resources
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        // Check if actual pushed values match expected
        assertEquals(expectedVals, actualVals, "All pushed values must match expected");

        // Check, if values in stacked are as expected,
        // Order does not matter due to concurrency
        Set<Integer> stackValues = new HashSet<>();
        while (!serverImpl.isEmpty()) {
            stackValues.add(serverImpl.pop());
        }
        assertEquals(expectedVals, stackValues, "Stack values must match expected");
    }

    /**
     * Verify all pushOperation includes min/max/gcd/lcm.
     * Test for each operator is in sequential order to avoid interference due to concurrency.
     */
    @Test
    void testPushOperationSequences_sequentialToAvoidInterference() throws Exception {
        Calculator calc = getCalculator();

        // Test 1: Min operator with values 10, -5, -20 -> min = -20
        calc.pushValue(10);
        calc.pushValue(-5);
        calc.pushValue(-20);
        calc.pushOperation("min");
        assertEquals(-20, calc.pop());

        // Test 2: Max operator with values 0, -6, 25 -> max = 25
        calc.pushValue(0);
        calc.pushValue(-6);
        calc.pushValue(25);
        calc.pushOperation("max");
        assertEquals(25, calc.pop());

        // Test 3: GCD operator with values 12, 24, 36 -> gcd = 12
        calc.pushValue(24);
        calc.pushValue(36);
        calc.pushValue(48);
        calc.pushOperation("gcd");
        assertEquals(12, calc.pop());

        // Test 4: LCM operator with values 6, 8, 12 -> lcm = 24
        calc.pushValue(6);
        calc.pushValue(8);
        calc.pushValue(12);
        calc.pushOperation("lcm");
        assertEquals(24, calc.pop());
    }

    /**
     * Test concurrent delayPop with multiple clients (similar to testing pushValue).
     * Description:
     * Load n values to the stack.
     * Start n concurrent delayPop tasks with decrease delay time.
     * Verify if delayPop returns distinct values.
     * This validates that concurrent delayed pops do not deadlock and properly return distinct values.
     */
    @Test
    void testDelayPopWithMultipleClients() throws Exception {
        // Preload values for the concurrent delayPop operations
        Calculator setup = getCalculator();
        setup.pushValue(7);
        setup.pushValue(2);
        setup.pushValue(10);
        setup.pushValue(4);

        // 4 threads concurrently pop values with varying delays
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Integer>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> getCalculator().delayPop(400)));
        futures.add(pool.submit(() -> getCalculator().delayPop(300)));
        futures.add(pool.submit(() -> getCalculator().delayPop(200)));
        futures.add(pool.submit(() -> getCalculator().delayPop(100)));

        Set<Integer> popped = new HashSet<>();
        try {
            for (Future<Integer> f : futures) {
                popped.add(f.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        // Each concurrent delayPop should return a unique value
        assertEquals(4, popped.size(), "Each client should pop a unique value");
        assertTrue(popped.containsAll(Set.of(7, 2, 10, 4)));
        assertTrue(serverImpl.isEmpty());
    }
}
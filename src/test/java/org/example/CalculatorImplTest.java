package org.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This file is for unit testing the CalculatorImplementation class.
 * It includes tests for individual methods or between methods.
 */

class CalculatorImplTest {

    @Test
    /*
     * Test the addValue, pop and isEmpty method of CalculatorImplementation
     */
    void testAddAndPop() throws Exception {
        CalculatorImplementation calcImpl = new CalculatorImplementation();

        // Verify that the stack is empty
        assertTrue(calcImpl.isEmpty());
        // Push 3 values to the stack in order -1, 0, 10
        calcImpl.pushValue(-1);
        calcImpl.pushValue(0);
        calcImpl.pushValue(10);
        // Verify that the stack is not empty
        assertFalse(calcImpl.isEmpty());
        // Verify that pop order is 10, 0, -1
        assertEquals(10, calcImpl.pop());
        assertEquals(0, calcImpl.pop());
        assertEquals(-1, calcImpl.pop());
        // Stack should be empty now
        assertTrue(calcImpl.isEmpty());
    }

    @Test
    /*
     * Test the delayPop method
     */
    void testDelayPop() throws Exception {
        CalculatorImplementation calcImpl = new CalculatorImplementation();

        // Testing Thread.sleep() is tricky due to non-deterministic behavior
        // I will only test that the actual pop still happens after the delay
        calcImpl.pushValue(10);
        int testValue = calcImpl.delayPop(1000);
        assertEquals(10, testValue);
    }
    
    @Test
    /*
     * Test Min-Max operations
     */
    void testMinMax() throws Exception {
        CalculatorImplementation calcImpl = new CalculatorImplementation();
        
        // The first test block is for min operations
        // Test 1: All positive numbers: 1, 2, 3 -> expected result: 1
        calcImpl.pushValue(1);
        calcImpl.pushValue(2);
        calcImpl.pushValue(3);
        calcImpl.pushOperation("min");
        assertEquals(1, calcImpl.pop());
        // Test 2: Mixing negatives and 0: -1, 0, 1 -> expected result: -1
        calcImpl.pushValue(-1);
        calcImpl.pushValue(0);
        calcImpl.pushValue(1);
        calcImpl.pushOperation("min");
        assertEquals(-1, calcImpl.pop());
        // Test 3: All 0 -> expected result: 0
        calcImpl.pushValue(0);
        calcImpl.pushValue(0);
        calcImpl.pushValue(0);
        calcImpl.pushOperation("min");
        assertEquals(0, calcImpl.pop());
        // Test 4: only one value in the stack: 1 -> expected result: 1
        calcImpl.pushValue(1);
        calcImpl.pushOperation("min");
        assertEquals(1, calcImpl.pop());
        
        // The second test block is for max operations
        // Test 1: All negative numbers: -2, -100, -1 -> expected result: -1
        calcImpl.pushValue(-2);
        calcImpl.pushValue(-100);
        calcImpl.pushValue(-1);
        calcImpl.pushOperation("max");
        assertEquals(-1, calcImpl.pop());
        // Test 2: Mixing negatives and 0: -2, 0, 100 -> expected result: 100
        calcImpl.pushValue(-2);
        calcImpl.pushValue(100);
        calcImpl.pushValue(0);
        calcImpl.pushOperation("max");
        assertEquals(100, calcImpl.pop());
        // Test 3: All 0 -> expected result: 0
        calcImpl.pushValue(0);
        calcImpl.pushValue(0);
        calcImpl.pushValue(0);
        calcImpl.pushOperation("max");
        assertEquals(0, calcImpl.pop());
        // Test 4: only one value in the stack: -1 -> expected result: -1
        calcImpl.pushValue(-1);
        calcImpl.pushOperation("max");
        assertEquals(-1, calcImpl.pop());
    }

    @Test
    /*
     * Test lcm and gcd operations
     */
    void testLcmAndGcd() throws Exception {
        CalculatorImplementation calcImpl = new CalculatorImplementation();

        // The second test block is for gcd operations
        // Test 1: primes and non-primes: 6, 12, 3 -> expected result: 3
        calcImpl.pushValue(6);
        calcImpl.pushValue(12);
        calcImpl.pushValue(3);
        calcImpl.pushOperation("gcd");
        assertEquals(3, calcImpl.pop());
        // Test 2: Mixing negatives: -4, -16, 8 -> expected result: 4
        calcImpl.pushValue(-4);
        calcImpl.pushValue(-16);
        calcImpl.pushValue(8);
        calcImpl.pushOperation("gcd");
        assertEquals(4, calcImpl.pop());
        // Test 3: includes 0: -5, 15, 0 -> expected result: 5
        calcImpl.pushValue(-5);
        calcImpl.pushValue(15);
        calcImpl.pushValue(0);
        calcImpl.pushOperation("gcd");
        assertEquals(5, calcImpl.pop());
        // Test 4: only one value in the stack
        calcImpl.pushValue(-1);
        calcImpl.pushOperation("gcd");
        assertEquals(-1, calcImpl.pop());

        // The first test block is for lcm operations
        // Test 1: includes negatives: -7, 1, 3 ->expected result: 21
        calcImpl.pushValue(-7);
        calcImpl.pushValue(1);
        calcImpl.pushValue(3);
        calcImpl.pushOperation("lcm");
        assertEquals(21, calcImpl.pop());
        // Test 2: Mixing negatives and 0
        calcImpl.pushValue(-1);
        calcImpl.pushValue(0);
        calcImpl.pushValue(1);
        calcImpl.pushOperation("lcm");
        assertEquals(0, calcImpl.pop());
        // Test 3: All 1
        calcImpl.pushValue(1);
        calcImpl.pushValue(1);
        calcImpl.pushValue(1);
        calcImpl.pushOperation("lcm");
        assertEquals(1, calcImpl.pop());
        // Test 4: only one value in the stack
        calcImpl.pushValue(0);
        calcImpl.pushOperation("lcm");
        assertEquals(0, calcImpl.pop());
    }
}



package org.example;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;


/**
 * Create an interactive terminal interface for testing the calculator service
 * This includes commands:
 *  pushValue <int>
 *  pushOperation <string>
 *  pop
 *  delayPop <int>
 *  isEmpty
 *  quit
 **/


public class CalculatorClient1 {
    /**
     * Connect to the RMI registry and interact with the Calculator service
     * @param args [host, port?, name?]
     */

    public static void main(String[] args) {
        try {
            // Connect to rmi registry on localhost port 1099
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            // Look up the remote object named "Calculator" and cast to Calculator interface
            Calculator calc = (Calculator) registry.lookup("Calculator");
            // Scanner to scan inputs and parse type
            java.util.Scanner scanner = new java.util.Scanner(System.in);

            // Loop user input with a scanner
            while (true) {
                System.out.print("Input command: ");
                String input = scanner.nextLine().trim();
                if (input.equals("quit")) {
                    break;
                }
                String[] tokens = input.split(" ");
                try {
                    switch (tokens[0]) {
                        case "pushValue" -> {
                            if (tokens.length != 2) {
                                System.out.println("Input value invalid. Try again \n");
                                break;
                            }
                            calc.pushValue(Integer.parseInt(tokens[1]));
                            System.out.println("Value pushed");
                            break;
                        }
                        case "pushOperation" -> {
                            if (tokens.length != 2) {
                                System.out.println("Input operator invalid. Try again");
                                break;
                            }

                            calc.pushOperation(tokens[1]);
                            System.out.println("Operation pushed");
                            break;
                        }
                        case "pop" -> {
                            System.out.println("Popped " + calc.pop());
                            break;
                        }

                        case "delayPop" -> {
                            if (tokens.length != 2) {
                                System.out.println("Input delay invalid. Try again");
                            }
                            System.out.println("Popped " + calc.delayPop(Integer.parseInt(tokens[1])));
                            break;
                        }
                        case "isEmpty" -> {
                            System.out.println("Stack is " + (calc.isEmpty() ? "empty" : "not empty"));
                            break;
                        }
                        default -> System.out.println("Unknown command. Try again");
                    }
                } catch (Exception ie) {
                    ie.printStackTrace(System.err);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }
}


package org.example;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class CalculatorServer {
    /**
     * Bootstrap the RMI server using port 1099
     * Bind the Calculator service
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        try {
            // Create the remote Calculator object
            CalculatorImplementation calc = new CalculatorImplementation();

            // Start the RMI registry on port 1099
            Registry registry = LocateRegistry.createRegistry(1099);

            // Bind the Calculator object to the name "Calculator"
            registry.rebind("Calculator", calc);

            System.out.println("Calculator server running ...");
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }
}

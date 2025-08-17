package org.example;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The Calculator interface defines all remote methods of the calculator using Remote
 *  that can be invoked across the network.
 */

public interface Calculator extends Remote {

    // Each input value from the client is pushed on the stack
    void pushValue(int val) throws RemoteException;

    // An operator is pushed last before popping the entire stack for the operation
    void pushOperation(String operator)  throws RemoteException;

    // Pop the top value of the stack and return it
    int pop()  throws RemoteException;

    // Check if the stack is empty
    boolean isEmpty()  throws RemoteException;

    // Same as the pop method above but is delayed for millis milliseconds
    int delayPop(int millis)  throws RemoteException;

}

package org.example;

import java.rmi.Remote;
import java.rmi.RemoteException;

/*
    The Calculator interface defines all remote methods of the calculator using Remote
    that can be invoked across the network.
*/

public interface Calculator extends Remote {

    // each input value from client is pushed on the stack
    void pushValue(int val) throws RemoteException;

    // an operator is pushed last before popping the entire stack for the operation
    void pushOperation(String operator)  throws RemoteException;

    // pop the top value of the stack and return it
    int pop()  throws RemoteException;

    // check if the stack is empty
    boolean isEmpty()  throws RemoteException;

    // same as the pop method above but is delayed for millis milliseconds
    int delayPop(int millis)  throws RemoteException;

}

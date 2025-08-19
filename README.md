# Assignment 1: Creating a simple Java RMI Calculator server

## Description: 
For this assignment, I created a remote Calculator using Java RMI that can 
handle multiple clients concurrently accessing the server using a shared stack
and can perform basic operations such as Min, Max, Lcm and Gcd.

## Project layout: 
./
├─ Makefile
├─ pom.xml
├─ README.md
├─ lib/
│  └─ junit-platform-console-standalone.jar
└─ src/
├─ main/
│  ├─ java/
│  │  └─ org/
│  │     └─ example/
│  │        ├─ Calculator.java
│  │        ├─ CalculatorImplementation.java
│  │        ├─ CalculatorServer.java
│  │        ├─ CalculatorClient.java
│  │        ├─ CalculatorClient1.java
│  │        └─ CalculatorClient2.java
│  └─ resources/
└─ test/
└─ java/
└─ org/
└─ example/
├─ CalculatorImplTest.java
└─ MultiClientsTest.java

## Submitted files:
1. Java source files:
Calculator.java: The remote Calculator interface.
CalculatorImplementation.java: The remote Calculator implementation.
CalculatorClient.java: Set up a client interface to the remote Calculator.
CalculatorServer.java: Server bootstrap.
2. Automated testing files: 
CalculatorImplTest.java: Unit tests for the remote Calculator implementation.
MultiClientsTest.java: Integration test for client-server communication (multiple clients).
3. README.md: This file contains the implementation description.

## How it works:
The server binds a single Calculator object to a registry on port 1099. 
The client connects to the registry and requests a Calculator object. 
The client can then perform operations on the Calculator object.
It works for multiple clients concurrently accessing the server (treat the clients as threads).
Currently, the server only supports a share stack. 


## Requirements:
1. IntelliJ IDEA (optional)
2. Java 21+
3. Maven 3.9+

## Project setup: 
1. Create a project with Maven
2. In main/java/org.example, Add the Java source files. 
3. In test/java/org.example, Create an "org.example" package and add the automated testing files. 
4. In pom.xml, add the following dependencies:
```pom.xml
<dependencies>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.0</version>
        <scope>test</scope>
    </dependency>
  </dependencies>
```
Navigating to Properties, change source and target to 21. Additional plugins may be required depending 
on machines.
5. Run the project.

## How to run: 
There are two ways to run the project: using the run button that is built into IntelliJ IDEA or using the 
terminal. The following steps are for using the terminal: 
1. Download JDK 21+ and set up the environment variables.
2. Compile all files (The "main" folder)
```bash
javac *.java
```
3. Start the server
```bash
java CalculatorServer.java
```
4. Run the client (single client with terminal interface)
```bash
java CalculatorClient.java
```
## Using the client interface:
After you start the server and run the client, in the terminal, A prompt line with "Input command" will appear. 
You can enter the command and the server will respond with the result. 
The following commands are supported:
- pushValue <int>: enter an integer value to the stack.
- pop: pop the top value from the stack.
- pushOperation <string>: enter an operator and perform the operation on the entire stack (min, max, lcm, gcd).
- delayPop <int>: delay the pop operation for <int> milliseconds.
- isEmpty: check if the stack is empty.
- quit: end the session. 

example: 
- pushValue 10
- pop
- pushOperation min
- delayPop 1000
- isEmpty
- quit

## Automation Testing: 
- Automation test scripts are done with JUnit 5. Since automated test scripts are created with Maven and JUnit 5 
using IntelliJ IDEA, it is recommended to use IntelliJ IDEA to run the test scripts.
- Navigate to the run button in the top right corner of the IDE or the first button in the bottom left corner of 
the IDE. Select the "current file" option and click the button to run the tests. 

## Notes
- If the registry is not on PATH, use its absolute path from your JDK bin folder.
- Ensure only one registry is running on port 1099.
- If you change package or class names, update the java -cp commands accordingly.

## Troubleshooting:
- ConnectionException:Connection refused: start the server first before running the client.
- Port number 1099 already in use: using another port (modify scripts) or kill the running process.












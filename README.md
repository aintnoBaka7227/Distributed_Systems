# Assignment 1: Java RMI Calculator server

## Description: 
For this assignment, I created a remote Calculator using Java RMI that can 
handle multiple clients concurrently accessing the server using a shared stack
and can perform basic operations such as Min, Max, Lcm, and Gcd.

## Project layout (Please ensure the layout is matched for Makefile to work): 
Distributed_Systems/
├── lib/
│ ├── gson-2.13.2.jar
│ └── junit-platform-console-standalone.jar
├── src/
│ ├── main/java/org/
│ │ ├── AggregationServer.java
│ │ ├── ContentServer.java
│ │ ├── GETClient.java
│ │ ├── LamportClock.java
│ │ ├── StationData.java
│ │ └── test/ (sample input station files)
│ └── test/java/org/
│ ├── LamportClockTest.java
│ └── AggregationServerTest.java
├── Makefile
├── main.db
└── README.md

## Submitted files:
- **Java sources**
    - `Calculator.java`: Remote Calculator interface
    - `CalculatorImplementation.java`: Implementation of remote Calculator
    - `CalculatorServer.java`: Server bootstrap
    - `CalculatorClient.java`: Basic client
- **Automated tests**
    - `CalculatorImplTest.java`: Unit tests for Calculator
    - `MultiClientsTest.java`: Integration test with multiple clients
- **Others**
    - `README.md`: Project documentation
    - `Makefile`: Automation for build, test, and run 

## How it works:
The server binds a single Calculator object to a registry on port 1099. 
The client connects to the registry and requests a Calculator object. 
The client can then perform operations on the Calculator object.
It works for multiple clients concurrently accessing the server (treat the clients as threads).
Currently, the server only supports a share stack. 

## Requirements:
1. JDK 21+ 
2. Junit Platform console standalone version 1.10.0
3. IntelliJ IDEA (optional)
4. Maven 3.9+ (optional)


## Project setup: 
1. Create a Maven project.
2. In main/java/org.example, Add the Java source files. 
3. In test/java/org.example, Create an "org.example" package and add the automated testing files.
4. Create a "lib" folder and install the Junit Platform console standalone version 1.10.0 jar file.
5. In pom.xml, add the following dependency after the "properties" tag:
```pom.xml
<properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
<dependencies>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.0</version>
        <scope>test</scope>
    </dependency>
  </dependencies>
```

## How to run: 
There are two ways to run the project:
1. Run the server and client in IntelliJ IDEA (Done easily with the run button).
2. Run the server and client using the Makefile (terminal command: make).

### Makefile usage
Use Linux or run "bash" command in windows to open the Ubuntu terminal.

#### Build
```bash
# Clean the output directory "out"
# All compiled files will be deleted to avoid conflicts.
make clean

# Compile main application files
make app
```

#### Testings
CalculatorImplTest: Unit tests for the remote Calculator implementation.
MultiClientsTest: Integration test for client-server communication (multiple clients).

```bash
# Run all tests: CalculatorImplTest and MultiClientsTest
make tests
# or 
make all-tests

/*************** Run individual tests ***************/

# Print out the list of test files available
make list-tests
# example output: org.example.CalculatorImplTest

# Run test based on the test compiled class name
make run-test TEST=...
# e.g: make run-test TEST=org.example.CalculatorImplTest
```

#### Running the application
Manually run the server and client. You must run the server first to allow the client 
to connect to it. Use two separate terminals for this.

```bash
# Run the server
make server

# Run the client
# An interactive prompt will appear. Description for it is below.
make client
```
 
## Using the client interface:
After you start the server and launch the client, the terminal will display Input command: as a prompt. 
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

## Notes
- Ensure only one registry is running on port 1099.
- If you change package or class names, update the Makefile accordingly.
- If you change the port number, update CalculatorServer.java and CalculatorClient.java accordingly.

## Troubleshooting:
- ConnectionException:Connection refused: start the server first before running the client.
- Port number 1099 already in use: using another port (modify scripts) or release port 1099.












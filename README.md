# Assignment 2: Building an aggregation server with consistency management and a RESTful API

## Description: 
For this assignment, I build a simple distributed aggregation server that stores weather station data 
from multiple content servers can send PUT requests to the aggregation server and return requested data
to multiple GET clients.

## Project layout (Please ensure the layout is matched for Makefile to work): 
Distributed_Systems/
├── lib/                                # External libraries
│   ├── gson-2.13.2.jar                 # Gson (JSON parsing)
│   └── junit-platform-console-standalone-*.jar   # JUnit 5 Console
├── src/
│   ├── main/java/org/                  # Application source files
│   │   ├── AggregationServer.java       # Central server
│   │   ├── ContentServer.java           # Uploads station data
│   │   ├── GETClient.java               # Queries weather data
│   │   ├── HttpRequest.java             # HTTP request parsing/writing
│   │   ├── HttpResponse.java            # HTTP response parsing/writing
│   │   ├── LamportClock.java            # Lamport clock logic
│   │   ├── PersistenceManager.java      # Atomic file persistence
│   │   ├── StationData.java             # Station data container
│   │   └── test/                        # Example weather station input files
│   │       ├── station1
│   │       └── station2
│   └── test/java/org/UnitTestings/      # JUnit 5 test classes
│       ├── HttpRequestTest.java
│       ├── HttpResponseTest.java
│       └── ...
├── out/                                # Compiled class files (created by make)
├── Makefile                            # Build, test, run automation
└── README.md                           # Documentation

## How it works:
### Content Server:
1. Reads weather station data from a local text file.
2. Converts the data into JSON format.
3. Sends the JSON to the AggregationServer via HTTP PUT.
4. Retries failed uploads (Server 500) with fault-tolerant mechanisms: exponential backoff, retry after not receiving response for 5s.
5. Dedicated to a single stationDedicated to a single station, requests can be asynchronous.

### GET Client:
1. Sends HTTP GET requests to the AggregationServer for single station/all stations data.
2. Parses the returned JSON and displays the data in a readable format (key-value pairs).
3. Maintains a Lamport clock to ensure causal consistency with server responses.
4. Retries failed requests (Server 500) with fault-tolerant mechanisms: exponential backoff, retry after not receiving a response for 5s.

### Aggregation Server:
1. Accepts HTTP PUT/GET requests from Content Server and GET Client.
2. Maintains a Lamport clock to ensure causal consistency with client requests and reject stale PUTs.
3. Stores data persistently in atomic file rewrites. 
4. Maintains at most 20 records, evicting the oldest when full.
5. Expires data if no update is received from a ContentServer within 30 seconds.
6. Handles multiple concurrent clients/servers with concurrency control (read/write locks).

### Available status code: 
#### GETClient
200 OK – Station data (single or all) returned successfully.
204 No Content – No valid stations available (all expired or none uploaded).
400 Bad Request – Returned if request uses an invalid method.
404 Not Found – Station ID not found or station data expired.
500 Internal Server Error – Server error while processing GET.

#### Content Server
200 OK – Existing station updated successfully.
201 Created – New station record created successfully.
204 No Content – PUT request had an empty body.
400 Bad Request – Missing station ID or invalid HTTP method.
409 Conflict – PUT rejected as stale (a Lamport clock too low).
500 Internal Server Error – Invalid JSON or persistence failure on server.

## How to build: 
1. unzip the project
2. cd into the project directory

## Requirements:
1. JDK 21+ 
2. Junit Platform console standalone version 1.10.0
3. Gson 2.13.2
3. IntelliJ IDEA (optional)
4. Maven 3.9+ (optional)
5. Linux 

## How to run: 
1. Build the project: 
make app
2. Run all tests: 
make tests
3. Run a specific test case:
make run-test TEST=org.HttpRequestTest
4. Search all possible tests:
make list-tests
5. Run hardcode server (port 4567):
make run-server
6. Run server with a custom port:
make run-server-flex ARGS="-p 5000"
7. Run content server with hardcode test data files:
make run-content1                                                                      # station 1 data (server port 4567)
make run-content2                                                                      # station 2 data (server port 4567)
8. Run content server with custom test data files:
make run-content ARGS="-url http://localhost:4567 -f src/main/java/org/test/station1"  # remember to type all parameters -url ... -sid ... inside ""
9. Run client with hardcode test data files:
make run-client1                                                                       # fetch IDS60901, server port 4567                 
make run-client2                                                                       # no ID, fetch all stations, server port 4567
10. Run client with custom test data files:
make run-client ARGS="-url http://localhost:4567 -sid IDS60901"                        # single station (remember to type all parameters -url ... -sid ... inside "")
make run-client ARGS="-url http://localhost:4567"                                      # all stations (remember to type -url ... inside "")
11. Clean all build files:
make clean

# Testing
## Test coverage:
### Unit testings: HTTPRequestTest, HTTPResponseTest, LamportClockTest, PersistenceManagerTest.
### Integration testings: ServerClientIntegrationTest, AggregationStatusTest, ServerCapacityAndExpiryTest, ConcurrencyGETAndStalePUTsTest. 
## Fail to cover:
### Integration testings: LamportClockConcurrencyTest










# Paxos Council Election (Java, TCP)

This project implements a single-decree Paxos consensus protocol over TCP sockets to elect a council president among nine members (M1–M9). Each member is a standalone Java process acting as Proposer, Acceptor, and Learner. Network profiles simulate latency and failures and can be changed at runtime.

## Project Structure

Overal structure:

``` bash
Distributed_Systems/
├── .idea/                                # IDE metadata (optional)
├── .vscode/                              # Editor settings (optional)
├── lib/                                  # External libraries (optional)
├── src/
│   ├── main/java/org/                   # Java sources (package org)
│   │   ├── CouncilMember.java           # Process entrypoint and CLI/admin handling
│   │   ├── PaxosNode.java               # Paxos roles coordination and shared state
│   │   ├── Proposer.java                # Proposer role
│   │   ├── Acceptor.java                # Acceptor role
│   │   ├── Learner.java                 # Learner role
│   │   ├── Message.java                 # Message model and codec
│   │   ├── MessageServer.java           # TCP server for messages + admin
│   │   ├── NetworkClient.java           # TCP client with profile simulation
│   │   ├── NetworkConfig.java           # Cluster membership/ports loader
│   │   ├── MemberProfile.java           # Latency/drop behavior definitions
│   │   └── Logger.java                  # Simple logging utility
│   └── test/java/org/                   # Unit and integration tests
│       ├── AcceptorTest.java
│       ├── LearnerTest.java
│       ├── MemberProfileTest.java
│       ├── MessageServerTest.java
│       ├── MessageTest.java
│       ├── NetworkClientTest.java
│       ├── NetworkConfigTest.java
│       ├── PaxosIntegrationTest.java
│       └── ProposerTest.java
├── Makefile                              # Build, run, test automation
├── pom.xml                               # Maven descriptor (optional build)
├── network.config                        # Member host:port mapping
├── run_tests.sh                          # Run all scenarios (bash)
├── run_scenario.sh                       # Run a single scenario (bash)
├── out/                                  # Compiled classes (generated)
├── out_test/                             # Test or scratch outputs (generated)
├── logs/                                 # Scenario/member logs (generated)
├── target/                               # Maven build output (generated)
└── README.md
```

## Logs Structure

The test runners write high-level scenario logs and archive per-member logs.

``` bash
logs/
├── scenario1.log                      # Stdout/stderr for Scenario 1 run
├── scenario2.log                      # Stdout/stderr for Scenario 2 run
├── scenario3.log                      # Stdout/stderr for Scenario 3 (3a–3c)
├── s1/                                # Archived per-member logs after Scenario 1
│   ├── log_M1.txt
│   ├── log_M2.txt
│   ├── log_M3.txt
│   ├── log_M4.txt
│   ├── log_M5.txt
│   ├── log_M6.txt
│   ├── log_M7.txt
│   ├── log_M8.txt
│   └── log_M9.txt
├── s2/                                # Archived per-member logs after Scenario 2
│   ├── log_M1.txt
│   └── ... log_M9.txt
├── s3/                                # Archived per-member logs after Scenario 3 (final state)
│   ├── log_M1.txt
│   └── ... log_M9.txt
├── s3a/                               # Subcase 3a archived per-member logs
│   ├── log_M1.txt
│   └── ... log_M9.txt
├── s3b/                               # Subcase 3b archived per-member logs
│   ├── log_M1.txt
│   └── ... log_M9.txt
└── s3c/                               # Subcase 3c archived per-member logs
    ├── log_M1.txt
    └── ... log_M9.txt
```

Notes
- Per-member files follow a single canonical pattern: `log_M<ID>.txt` (e.g., `log_M3.txt`).
- `run_tests.sh` runs all scenarios in order, teeing top-level output to `logs/scenarioX.log`, archiving per-member logs after each scenario (and for 3a/3b/3c individually).
- `run_scenario.sh` runs one scenario, writes `logs/scenarioX.log`, archives its per-member logs, and clears live per-member logs afterward.

## Requirements

- JDK 21+ with `javac` and `java` on PATH
- Bash (Linux/macOS)
- GNU Make (for `Makefile` targets)
- Netcat (`nc` or `ncat`) to send admin commands in test scripts
- For unit tests: `junit-platform-console-standalone*.jar` placed in `lib/`
- Convert files from CRLF -> LF for bash execution

## How to Run

```
Build the project:                      make build
Run all scenarios:                      make tests / bash run_tests.sh
Run a single scenario:                  bash run_scenario.sh s1|s2|s3|all [config]
Run a member (generic):                 make member ID=M4 PROFILE=reliable CONFIG=network.config
Run member shortcuts:                   make m1  (or m2..m9) PROFILE=standard CONFIG=network.config

Run all unit tests:                     make unit
Run a specific test class:              make test-class TEST=org.ProposerTest
Search all possible tests:              make list-tests

Clean all build files:                  make clean                
```

## Configuration

- `network.config` maps member IDs to host and port. Example:

```
M1,localhost,9001
M2,localhost,9002
...
M9,localhost,9009
```

Majority is computed as N/2 + 1 from the config size.

## Runtime Commands

After running a single member, Type commands into a live console, or send them over TCP (e.g., with `nc` to the member’s port):

- `propose <Candidate>`: Start a Paxos round proposing the candidate (e.g., `M5`).
- `profile <reliable|latent|failure|standard>`: Switch runtime profile.
- `latency <minMs> <maxMs>`: Set runtime latency window.
- `drop <rate>`: Set message drop rate 0.0–1.0.
- `ids`: List members from the config.
- `state`: Print internal node state snapshot.
- `crash`: Terminate the process (simulates failure).
- `help`, `exit`.

Consensus is printed as:

```
CONSENSUS: M5 has been elected Council President!
```

## Message Design

Text-based, one message per line, colon-delimited:

```
TYPE:from:proposalId:value:acceptedId:acceptedValue
```

Where `TYPE` in {`PREPARE`, `PROMISE`, `ACCEPT_REQUEST`, `ACCEPTED`}.

- `PREPARE`: proposer -> all, with `proposalId`.
- `PROMISE`: acceptor -> proposer, echoes `proposalId`, includes prior `acceptedId/acceptedValue` if any.
- `ACCEPT_REQUEST`: proposer -> all, with `proposalId` and chosen `value`.
- `ACCEPTED`: acceptor -> all, confirms `proposalId` and `value` for learner aggregation.

Proposal numbers are `seq*100 + memberNumericId` to maintain monotonic and unique proposalID for tie-breaking situations.

## Testing Scripts

- `run_tests.sh`: Bash runner that builds and executes all three scenarios (1, 2, 3a–3c) sequentially.
- `run_scenario.sh`: Bash runner to execute a single scenario: `bash run_scenario.sh s1|s2|s3|all [config]`.
- Netcat (`nc` or `ncat`) is recommended for sending admin commands; the runner attempts a Java fallback if unavailable.

Examples:

```
# Run everything
bash run_tests.sh

# Run a single scenario
./run_scenario.sh s2
./run_scenario.sh s3 custom_network.config
```

Outputs per-member logs `log_M*.txt` and scenario summaries under `logs/`.

## Unit/Integration Testing (Junit5) 

Unit testings:
- `AcceptorTest.java` 
- `ProposerTest.java` 
- `LearnerTest.java` 
- `MessageTest.java` 
- `MessageServerTest.java` 
- `NetworkClientTest.java` 
- `NetworkConfigTest.java` 
- `MemberProfileTest.java`

Integration testings:
- `PaxosIntegrationTest.java` — End-to-end Paxos run across members reaching consensus.



## Design Notes

- Members run a TCP server and accept plain-text admin commands on the same port (for automation/testing).
- Profiles simulate latency/drop on both send/receive paths; failure profile requires an explicit `crash` command.
- Proposers retry on timeouts with backoff and, when Phase 1 stalls, bump their proposal number.
- Acceptors maintain `highestPromised` and never accept proposals lower than promised; they broadcast `ACCEPTED` on every accept to drive learner convergence.
- Post-decision reinforcement: After the Learner decides, Acceptors only re-accept `ACCEPT_REQUEST(n, v)` when `v` equals the decided value and `n >= highestPromised`, then re-broadcast `ACCEPTED` to help late peers converge. This supports crash/restart without risking a second decision.
- Phase 2 retries: Proposers safely resend `ACCEPT_REQUEST(n, v)` when Phase 2 is active but quorum isn’t reached; Acceptors’ post-decision guard ensures safety while aiding convergence.
- Recovery after partial progress: Acceptors continue responding to higher `PREPARE(n)` with PROMISE (including prior accepted pairs). Proposers pick the highest previously accepted value seen in PROMISEs; otherwise they use their candidate. This recovers correctly from crashes/competing rounds.
- Competing leaders: If Phase 1 stalls, Proposers escalate `n` (monotonic per-member) to avoid starvation. 
- Learner convergence: Acceptors call `learner.trackAccepts` on every accept, and Proposers forward `ACCEPTED` to the Learner. The Learner prints `CONSENSUS:` once a majority is observed and ignores further accepts.

## Notes
- Runtime has no external dependencies. Unit tests require the JUnit console jar in `lib/`.
- No hardcoded participants; everything is driven by `network.config`. Configurable during run time for members.
- Current implementation can cause starvation for Proposer in phase 2, where it resends `ACCEPT_REQUEST(n, v)` after timeout while Acceptor may already established a higher `highestPromised` from others' proposals, leading to an infinite loop. One solution is to set another timeout for `ACCEPT_REQUEST(n, v)`, if it can not gather enough `ACCEPTED` then fallback to Phase 1 Prepare. 

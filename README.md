# Paxos Council Election (Java, TCP)

This project implements a single-decree Paxos consensus protocol over TCP sockets to elect a council president among nine members (M1–M9). Each member is a standalone Java process acting as Proposer, Acceptor, and Learner. Network profiles simulate latency and failures and can be changed at runtime.

## Project Structure

```
Distributed_Systems/
├── lib/                                 # External libraries (optional)
├── src/
│   └── main/java/org/                   # Java sources (package org)
│       ├── CouncilMember.java
│       ├── Endpoint.java
│       ├── Message.java
│       ├── MessageServer.java
│       ├── MessageType.java
│       ├── MemberProfile.java
│       ├── NetworkClient.java
│       ├── NetworkConfig.java
│       └── PaxosNode.java
├── out/                                  # Compiled classes (created by Makefile)
├── Makefile                              # Build, run, test automation
├── network.config                        # Member host:port mapping
├── run_tests.sh                          # Example test harness
└── README.md
```

## Build

- Requires JDK 8+
- Build classes into `out/`:

```
make build
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

## Run Members

Launch members in separate terminals/shells:

```
make member ID=M1 PROFILE=reliable CONFIG=network.config
make member ID=M2 PROFILE=latent   CONFIG=network.config
make member ID=M3 PROFILE=failure  CONFIG=network.config
make member ID=M4 PROFILE=standard CONFIG=network.config
```

Or directly:

```
java -cp out org.CouncilMember M1 --profile reliable --config network.config
```

Options:

- `--profile <reliable|latent|failure|standard>`: initial profile (default: `standard`)
- `--config <path>`: path to config file (default: `network.config`)

## Runtime Commands

Type commands into a member’s console, or send them over TCP (e.g., with `nc` to the member’s port):

- `propose <Candidate>`: Start a Paxos round proposing the candidate (e.g., `M5`).
- `ids`: List members from the config.
- `state`: Print internal node state snapshot.
- `crash`: Terminate the process (simulates failure).
- `help`, `exit`.

Note: Profiles and network timing/drop behavior are set only at startup via the `--profile` CLI flag. No runtime reconfiguration.

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

Proposal numbers are `seq*1000 + memberNumericId` (monotonic, unique per proposer).

## Testing Scripts

- Unix/macOS: `run_tests.sh` requires bash and `nc` (netcat).
- Windows PowerShell: use `run_tests.ps1` (no external tools needed).

Run (Unix/macOS):

```
bash run_tests.sh
```

Outputs per-member logs `logs_M*.txt` and scenario summaries under `logs/`.

Run (Windows PowerShell):

```
pwsh -File run_tests.ps1
```

The PS script builds, launches members, sends admin commands using a tiny TCP helper, and captures logs similarly.

## Design Notes

- Each member runs a TCP server for messages and also accepts plain-text admin commands over the same port to facilitate automation.
- Profiles simulate network conditions on both send and receive. Failure profile does not auto-crash; issue `crash` command to terminate.
- Proposers retry with higher proposal numbers if they do not reach quorum within a time window derived from the profile’s latency.
- Acceptors follow Paxos safety: promise and accept only for non-decreasing proposal IDs. Acceptors broadcast `ACCEPTED` to all; learners decide when any (proposalId, value) gathers a majority.

## Code Overview

- `src/main/java/org/CouncilMember.java:1`: main entry; starts server, console; handles runtime commands.
- `src/main/java/org/PaxosNode.java:1`: Paxos logic for proposer/acceptor/learner and state.
- `src/main/java/org/Message.java:1`: message model and encoding/decoding (colon-delimited).
- `src/main/java/org/NetworkConfig.java:1`: config loader and endpoint mapping (Endpoint co-located).
- `src/main/java/org/MessageServer.java:1`: TCP listener to receive messages and admin commands.
- `src/main/java/org/NetworkClient.java:1`: TCP sender with profile simulation.
- `src/main/java/org/MemberProfile.java:1`: latency/drop simulation (profile set at startup only).

## Notes

- No external dependencies. No hardcoded scenarios; everything is driven by runtime commands and config.
- You may adjust `network.config` to change participants or ports. Majority adjusts automatically.

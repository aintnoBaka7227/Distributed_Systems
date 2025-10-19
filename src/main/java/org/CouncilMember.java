package org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * Each council member runs as a process, have a TCP server and can act
 * as Proposer, Acceptor, and Learner. Profiles and proposals are configurable at runtime
 * through stdin commands
 */
public class CouncilMember {

    // Identifier for this member (e.g., "M1")
    public static String memberID;
    // Initial latency name (reliable|latent|failure|standard)
    public static String profileStatus;
    // Path to network configuration file
    public static String configFilePath;

    /**
     * Main: Initialize a single council member process
     * Responsibilities:
     * Parse CLI args (member ID, profile, config path)
     * Resolve endpoints for all members
     * Initialize logging, profile, network client, and Paxos node
     * Sparse the message server thread
     * Interactive command loop for operational control
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java org.CouncilMember <memberID> [--profile <reliable|latent|failure|standard>] [--config <path>]");
            return;
        }

        parseArgs(args);

        try {
            NetworkConfig config = NetworkConfig.loadConfigFile(configFilePath);
            Endpoint self = config.getEndpoint(memberID);
            if (self == null) {
                System.err.println("Member " + memberID + " not found in config " + configFilePath);
                return;
            }

            MemberProfile profile = MemberProfile.setLatency(profileStatus);
            NetworkClient client = new NetworkClient(config, profile);
            PaxosNode node = new PaxosNode(memberID, config, client, profile);

            Logger.init(memberID);
            Logger.enableFile("logs");

            startServer(self, profile, node);
            commandLoop(config, profile, node);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * startServer: launches the TCP MessageServer on this member's port
     * Notes:
     * Use daemon thread so the JVM can exit quick
     * Logs the listening host:port and active latency profile
     */
    private static void startServer(Endpoint self, MemberProfile profile, PaxosNode node) {
        MessageServer server = new MessageServer(self.port, profile, node);
        Thread serverThread = new Thread(server, "Server-" + memberID);
        serverThread.setDaemon(true);
        serverThread.start();

        Logger.info("Listening on " + self.host + ":" + self.port + " profile=" + profile.getLatencyType());
        System.out.println("Type 'help' for commands.");
    }

    /**
     * commandLoop: interactive terminal to control the node at runtime
     * Syntax:
     * propose <candidate>
     * profile <name> / latency <min> <max> / drop <rate>
     * ids / state / crash / quit|exit
     * Behavior:
     * Non-blocking on null lines; slight sleep avoids busy looping
     */
    private static void commandLoop(NetworkConfig config, MemberProfile profile, PaxosNode node) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while (true) {
            line = reader.readLine();
            if (line == null) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                continue;
            }
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.equalsIgnoreCase("help")) {
                printHelp();
                continue;
            }
            if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                System.out.println("Exiting " + memberID);
                break;
            }
            handleCommand(line, config, profile, node);
        }
    }

    /**
     * handleCommand: parses and executes a single console command
     * Design:
     * Case-insensitive command keywords; preserves original casing for values
     * Safe parsing handle invalid input
     * Logs changes (profile/latency/drop)
     */
    private static void handleCommand(String line, NetworkConfig config, MemberProfile profile, PaxosNode node) {
        try {
            String lower = line.toLowerCase(Locale.ROOT);

            if (lower.startsWith("propose ")) {
                String candidate = line.substring("propose ".length()).trim();
                node.initiateProposal(candidate);

            } else if (lower.startsWith("profile ")) {
                String name = line.substring("profile ".length()).trim();
                MemberProfile np = MemberProfile.setLatency(name);
                profile.updateFrom(np);
                Logger.admin("profile set to " + profile.getLatencyType());

            } else if (lower.startsWith("latency ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    int min = Integer.parseInt(parts[1]);
                    int max = Integer.parseInt(parts[2]);
                    profile.setLatency(min, max);
                    Logger.admin("latency set to " + min + "-" + max + " ms");
                } else {
                    System.out.println("Usage: latency <minMs> <maxMs>");
                }

            } else if (lower.startsWith("drop ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    double rate = Double.parseDouble(parts[1]);
                    profile.setDropRate(rate);
                    Logger.admin("drop rate set to " + rate);
                } else {
                    System.out.println("Usage: drop <rate 0.0-1.0>");
                }

            } else if (lower.equals("crash")) {
                Logger.admin("crash requested");
                System.exit(1);

            } else if (lower.equals("ids")) {
                Logger.info("Known members: " + config.getMemberIds());

            } else if (lower.equals("state")) {
                String snap = node.getStateSnapshot();
                Logger.info("STATE " + snap);

            } else {
                System.out.println("Unknown command. Type 'help'.");
            }
        } catch (Exception e) {
            System.err.println("Error handling command: " + e.getMessage());
        }
    }

    /**
     * parseArgs: extracts memberID, profile, and config path from CLI args
     * Conventions:
     * arg 0: memberID ("M1".."M9")
     * Flags: --profile <name>, --config <path>
     * Defaults:
     * profile=standard, config=network.config
     */
    private static void parseArgs(String[] args) {
        memberID = args[0];
        profileStatus = "standard";
        configFilePath = "network.config";

        for (int i = 1; i < args.length; i++) {
            if ("--profile".equals(args[i]) && i + 1 < args.length) {
                profileStatus = args[++i];
            } else if ("--config".equals(args[i]) && i + 1 < args.length) {
                configFilePath = args[++i];
            }
        }
    }

    /**
     * printHelp: prints supported commands and brief descriptions
     * Keep this in sync with handleCommand to avoid drift
     */
    private static void printHelp() {
        System.out.println("Commands:\n" +
                "  help                      Show this help\n" +
                "  propose <Candidate>       Start Paxos to elect candidate (e.g., M5)\n" +
                "  profile <name>            Set profile: reliable|latent|failure|standard\n" +
                "  latency <min> <max>       Set artificial latency range in ms\n" +
                "  drop <rate>               Set message drop rate 0.0-1.0\n" +
                "  ids                       List members from config\n" +
                "  state                     Print internal node state\n" +
                "  crash                     Terminate this process\n" +
                "  quit|exit                 Stop this member\n");
    }
}

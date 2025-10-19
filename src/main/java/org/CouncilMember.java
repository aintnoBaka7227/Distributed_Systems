package org;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * Entry point for a council member process. Each member runs a TCP server and can act
 * as Proposer, Acceptor, and Learner. Profiles and proposals can be controlled at runtime
 * via stdin commands.
 */
public class CouncilMember {

    public static String memberID;
    public static String profileStatus;
    public static String configFilePath;
    /**
     * Main entry for a council member process.
     * Usage: java -cp out org.CouncilMember M1 --profile reliable [--config network.config]
     * Arguments:
     *  - memberID: e.g., M1..M9
     *  - --profile <reliable|latent|failure|standard>: initial timing/drop profile
     *  - --config <path>: network configuration file
     * @param args command-line args
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java org.CouncilMember <memberID> [--profile <reliable|latent|failure|standard>] [--config <path>]");
            return;
        }

        parseArgs(args);

        try {
            NetworkConfig config = NetworkConfig.load(configFilePath);
            Endpoint self = config.getEndpoint(memberID);
            if (self == null) {
                System.err.println("Member " + memberID + " not found in config " + configFilePath);
                return;
            }

            MemberProfile profile = MemberProfile.fromName(profileStatus);
            NetworkClient client = new NetworkClient(config, profile);
            PaxosNode node = new PaxosNode(memberID, config, client, profile);
            Logger.init(memberID);
            Logger.enableFile("logs");

            MessageServer server = new MessageServer(self.port, profile, node);
            Thread serverThread = new Thread(server, "Server-" + memberID);
            serverThread.setDaemon(true);
            serverThread.start();

            Logger.info("Listening on " + self.host + ":" + self.port + " profile=" + profile.getName());
            System.out.println("Type 'help' for commands.");

            // Console command loop for runtime control
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while (true) {
                line = reader.readLine();
                if (line == null) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    continue; // keep running headless if stdin is closed
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
                try {
                    if (line.toLowerCase(Locale.ROOT).startsWith("propose ")) {
                        String candidate = line.substring("propose ".length()).trim();
                        node.initiateProposal(candidate);
                    } else if (line.equalsIgnoreCase("crash")) {
                        Logger.admin("crash requested");
                        System.exit(1);
                    } else if (line.equalsIgnoreCase("ids")) {
                        Logger.info("Known members: " + config.getMemberIds());
                    } else if (line.equalsIgnoreCase("state")) {
                        String snap = node.getStateSnapshot();
                        Logger.info("STATE " + snap);
                    } else {
                        System.out.println("Unknown command. Type 'help'.");
                    }
                } catch (Exception e) {
                    System.err.println("Error handling command: " + e.getMessage());
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

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

    private static void printHelp() {
        System.out.println("Commands:\n" +
                "  help                      Show this help\n" +
                "  propose <Candidate>       Start Paxos to elect candidate (e.g., M5)\n" +
                "  ids                       List members from config\n" +
                "  state                     Print internal node state\n" +
                "  crash                     Terminate this process\n" +
                "  quit|exit                 Stop this member\n");
    }
}

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

    /**
     * Main entry for a council member process.
     * Usage: java -cp out org.CouncilMember M1 --profile reliable [--config network.config]
     * Arguments:
     *  - memberId: e.g., M1..M9
     *  - --profile <reliable|latent|failure|standard>: initial timing/drop profile
     *  - --config <path>: network configuration file
     * @param args command-line args
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java org.CouncilMember <MemberId> [--profile <reliable|latent|failure|standard>] [--config <path>]");
            return;
        }

        String memberId = args[0];
        String profileArg = "standard";
        String configPath = "network.config";
        for (int i = 1; i < args.length; i++) {
            if ("--profile".equals(args[i]) && i + 1 < args.length) {
                profileArg = args[++i];
            } else if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[++i];
            }
        }

        try {
            NetworkConfig config = NetworkConfig.load(configPath);
            Endpoint self = config.getEndpoint(memberId);
            if (self == null) {
                System.err.println("Member " + memberId + " not found in config " + configPath);
                return;
            }

            MemberProfile profile = MemberProfile.fromName(profileArg);
            NetworkClient client = new NetworkClient(config, profile);
            PaxosNode node = new PaxosNode(memberId, config, client, profile);
            Logger.init(memberId);
            Logger.enableFile("logs");

            MessageServer server = new MessageServer(self.port, profile, node);
            Thread serverThread = new Thread(server, "Server-" + memberId);
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
                    System.out.println("Exiting " + memberId);
                    break;
                }
                try {
                    if (line.toLowerCase(Locale.ROOT).startsWith("propose ")) {
                        String candidate = line.substring("propose ".length()).trim();
                        node.initiateProposal(candidate);
                    } else if (line.toLowerCase(Locale.ROOT).startsWith("profile ")) {
                        String name = line.substring("profile ".length()).trim();
                        MemberProfile np = MemberProfile.fromName(name);
                        profile.updateFrom(np);
                        Logger.admin("profile set to " + profile.getName());
                    } else if (line.toLowerCase(Locale.ROOT).startsWith("latency ")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 3) {
                            int min = Integer.parseInt(parts[1]);
                            int max = Integer.parseInt(parts[2]);
                            profile.setLatency(min, max);
                            Logger.admin("latency set to " + min + "-" + max + " ms");
                        } else {
                            System.out.println("Usage: latency <minMs> <maxMs>");
                        }
                    } else if (line.toLowerCase(Locale.ROOT).startsWith("drop ")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            double rate = Double.parseDouble(parts[1]);
                            profile.setDropRate(rate);
                            Logger.admin("drop rate set to " + rate);
                        } else {
                            System.out.println("Usage: drop <rate 0.0-1.0>");
                        }
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

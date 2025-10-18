package org;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Tiny helper to send a single admin command line over TCP to a member.
 * Usage: java -cp out org.AdminClient <host> <port> <command line>
 */
public class AdminClient {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java -cp out org.AdminClient <host> <port> <command>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) sb.append(' ');
            sb.append(args[i]);
        }
        String line = sb.toString();
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 2000);
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()));
            pw.println(line);
            pw.flush();
        }
    }
}


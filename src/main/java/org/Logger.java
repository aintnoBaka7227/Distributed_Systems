package org;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Lightweight console logger with per-member context. Designed to produce
 * clear, parseable logs for testing and assessment.
 */
public final class Logger {
    private static volatile String memberId = "?";
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static volatile PrintWriter fileOut = null;

    private Logger() {}

    /**
     * Initialize the logger with the current member ID.
     * @param id member identifier (e.g., M1)
     */
    public static void init(String id) {
        if (id != null) memberId = id;
    }

    /**
     * Also write logs to a per-member file inside the given directory.
     * Files are named as log_<MemberId>.txt using UTF-8 encoding.
     * @param dir directory path
     */
    public static synchronized void enableFile(String dir) {
        try {
            File d = new File(dir);
            if (!d.exists()) d.mkdirs();
            File f = new File(d, "log_" + memberId + ".txt");
            fileOut = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8), true);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { if (fileOut != null) fileOut.close(); } catch (Exception ignored) {}
            }));
        } catch (Exception e) {
            // fallback silently to console-only
            fileOut = null;
        }
    }

    /**
     * Log an informational message.
     * @param msg message text
     */
    public static void info(String msg) {
        println("INFO", msg);
    }

    /**
     * Log a warning message.
     * @param msg message text
     */
    public static void warn(String msg) {
        println("WARN", msg);
    }

    /**
     * Log an error message.
     * @param msg message text
     */
    public static void error(String msg) {
        println("ERROR", msg);
    }

    /**
     * Log a protocol message being sent.
     * @param toId destination member id
     * @param m message instance
     */
    public static void messageSent(String toId, Message m) {
        String text = String.format("SEND to=%s type=%s pid=%s val=%s", toId, m.type,
                String.valueOf(m.proposalID), String.valueOf(m.proposalVal));
        println("PROTO", text);
    }

    /**
     * Log a protocol message received.
     * @param fromId source member id
     * @param m message instance
     */
    public static void messageReceived(String fromId, Message m) {
        String text = String.format("RECV from=%s type=%s pid=%s val=%s accId=%s accVal=%s",
                fromId, m.type, String.valueOf(m.proposalID), String.valueOf(m.proposalVal),
                String.valueOf(m.acceptedID), String.valueOf(m.acceptedValue));
        println("PROTO", text);
    }

    /**
     * Log an administrative command.
     * @param cmd command text
     */
    public static void admin(String cmd) {
        println("ADMIN", cmd);
    }

    /**
     * Log a consensus decision.
     * @param value decided value
     */
    public static void consensus(String value) {
        println("DECIDE", "Consensus reached: " + value);
    }

    private static synchronized void println(String level, String msg) {
        String ts = TS.format(new Date());
        String thread = Thread.currentThread().getName();
        String line = String.format("[%s] [%s] [%s] [%s] %s", ts, level, memberId, thread, msg);
        System.out.println(line);
        if (fileOut != null) {
            fileOut.println(line);
            fileOut.flush();
        }
    }
}

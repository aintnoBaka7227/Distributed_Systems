package org;

import java.io.*;
import java.util.*;

/**
 * Represents a standard HTTP response with a Lamport clock value.
 * Supports:
 *  - Writing a response (aggregation server side).
 *  - Parsing a response (client side).
 */
public class HttpResponse {
    /** HTTP status code */
    private final int statusCode;

    /** Status message */
    private final String statusMessage;

    /** Map of response headers */
    private final Map<String, String> headers;

    /** Optional response body (JSON data) */
    private final String body;

    /** Lamport clock value sent with the response */
    private final int clock;

    /**
     * Construct a new HttpResponse object.
     * @param statusCode    HTTP status code
     * @param statusMessage HTTP status message
     * @param body          Response body (can be null)
     * @param clock         Lamport clock value
     */
    public HttpResponse(int statusCode, String statusMessage,
                        String body, int clock) {
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        this.body = body;
        this.clock = clock;

        // Initialize headers
        this.headers = new LinkedHashMap<>();
        this.headers.put("Content-Type", "application/json");
        if (body != null) {
            // Set Content-Length header automatically
            this.headers.put("Content-Length", String.valueOf(body.getBytes().length));
        }
    }

    // SERVER SIDE: Writing response

    /**
     * Write this HttpResponse to a stream.
     * @param wr BufferedWriter connected to the client socket
     * @throws IOException if writing fails
     */
    public void write(BufferedWriter wr) throws IOException {
        // Status line
        wr.write("HTTP/1.1 " + statusCode + " " + statusMessage + "\r\n");

        // Headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            wr.write(entry.getKey() + ": " + entry.getValue() + "\r\n");
        }

        // Lamport clock header
        wr.write("Clock: " + clock + "\r\n");
        wr.write("\r\n"); // end of headers

        // Body (optional)
        if (body != null) {
            wr.write(body);
        }
        wr.flush();
    }

    // CLIENT SIDE: Reading response

    /**
     * Parse an HttpResponse from an input stream.
     * @param in BufferedReader connected to the server socket
     * @return Parsed HttpResponse object
     * @throws IOException if parsing fails
     */
    public static HttpResponse parse(BufferedReader in) throws IOException {
        // Status line
        String statusLine = in.readLine();
        if (statusLine == null || !statusLine.startsWith("HTTP/1.1")) {
            throw new IOException("Invalid status line: " + statusLine);
        }

        String[] parts = statusLine.split(" ", 3);
        int statusCode = (parts.length >= 2) ? Integer.parseInt(parts[1]) : -1;
        String statusMessage = (parts.length == 3) ? parts[2] : "";

        // Headers
        Map<String, String> headers = new LinkedHashMap<>();
        int clock = -1;
        String headerLine;
        while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
            int idx = headerLine.indexOf(":");
            if (idx > 0) {
                String key = headerLine.substring(0, idx).trim();
                String value = headerLine.substring(idx + 1).trim();
                headers.put(key, value);

                // Find Lamport clock if present
                if (key.equalsIgnoreCase("Clock")) {
                    try {
                        clock = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // Body
        String body = null;
        if (headers.containsKey("Content-Length")) {
            // If Content-Length is known, read the number of chars
            int length;
            try {
                length = Integer.parseInt(headers.get("Content-Length"));
            } catch (NumberFormatException e) {
                length = 0;
            }
            // Safely read till based on Content-Length (not strict)
            if (length > 0) {
                char[] buf = new char[length];
                int totalRead = 0;
                while (totalRead < length) {
                    int n = in.read(buf, totalRead, length - totalRead);
                    if (n == -1) break;
                    totalRead += n;
                }
                body = new String(buf, 0, totalRead);
            }
        } else {
            // Fallback: read until EOF
            StringBuilder bodyBuilder = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                bodyBuilder.append(line).append("\n");
            }
            if (bodyBuilder.length() > 0) {
                body = bodyBuilder.toString().trim();
            }
        }

        return new HttpResponse(statusCode, statusMessage, body, clock);
    }


    // Getters
    public int getStatusCode() { return statusCode; }
    public String getStatusMessage() { return statusMessage; }
    public String getBody() { return body; }
    public int getClock() { return clock; }
    public Map<String, String> getHeaders() { return headers; }
}

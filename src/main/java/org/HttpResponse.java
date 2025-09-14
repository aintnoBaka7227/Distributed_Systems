package org;

import java.io.*;
import java.util.*;

public class HttpResponse {
    private final int statusCode;
    private final String statusMessage;
    private final Map<String, String> headers;
    private final String body;
    private final int clock;

    public HttpResponse(int statusCode, String statusMessage,
                        String body, int clock) {
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        this.body = body;
        this.clock = clock;
        this.headers = new LinkedHashMap<>();
        this.headers.put("Content-Type", "application/json");
        if (body != null) {
            this.headers.put("Content-Length", String.valueOf(body.getBytes().length));
        }
    }

    // --- Server side: write response ---
    public void write(BufferedWriter wr) throws IOException {
        wr.write("HTTP/1.1 " + statusCode + " " + statusMessage + "\r\n");

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            wr.write(entry.getKey() + ": " + entry.getValue() + "\r\n");
        }

        wr.write("Clock: " + clock + "\r\n");
        wr.write("\r\n");

        if (body != null) {
            wr.write(body);
        }
        wr.flush();
    }

    // --- Client side: read response ---
    public static HttpResponse parse(BufferedReader in) throws IOException {
        // --- Status line ---
        String statusLine = in.readLine();
        if (statusLine == null || !statusLine.startsWith("HTTP/1.1")) {
            throw new IOException("Invalid status line: " + statusLine);
        }

        String[] parts = statusLine.split(" ", 3);
        int statusCode = (parts.length >= 2) ? Integer.parseInt(parts[1]) : -1;
        String statusMessage = (parts.length == 3) ? parts[2] : "";

        // --- Headers ---
        Map<String, String> headers = new LinkedHashMap<>();
        int clock = -1;
        String headerLine;
        while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
            int idx = headerLine.indexOf(":");
            if (idx > 0) {
                String key = headerLine.substring(0, idx).trim();
                String value = headerLine.substring(idx + 1).trim();
                headers.put(key, value);

                if (key.equalsIgnoreCase("Clock")) {
                    try {
                        clock = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // --- Body ---
        String body = null;
        if (headers.containsKey("Content-Length")) {
            int length;
            try {
                length = Integer.parseInt(headers.get("Content-Length"));
            } catch (NumberFormatException e) {
                length = 0;
            }

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
            // Fallback: read until EOF (non-persistent connections only)
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

    // --- Getters ---
    public int getStatusCode() { return statusCode; }
    public String getStatusMessage() { return statusMessage; }
    public String getBody() { return body; }
    public int getClock() { return clock; }
    public Map<String, String> getHeaders() { return headers; }
}

package org;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HttpRequest {
    private final String method;
    private final String resource;
    private final Map<String, String> headers;
    private final String body;
    private final int clock;

    public HttpRequest(String method, String resource,
                       Map<String, String> headers, String body, int clock) {
        this.method = method;
        this.resource = resource;
        this.headers = headers != null ? headers : new HashMap<>();
        this.body = body;
        this.clock = clock;
    }

    public static HttpRequest parse(BufferedReader rd) throws IOException {
        // --- Request line ---
        String requestLine = rd.readLine();
        if (requestLine == null || requestLine.split(" ").length < 2) {
            throw new IOException("Invalid request line: " + requestLine);
        }

        String[] parts = requestLine.split(" ");
        String method = parts[0];
        String resource = parts[1];

        // --- Headers ---
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = rd.readLine()) != null && !line.isEmpty()) {
            int idx = line.indexOf(":");
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim().toLowerCase(),
                        line.substring(idx + 1).trim());
            }
        }

        // --- Body ---
        int contentLength = 0;
        if (headers.containsKey("content-length")) {
            try {
                contentLength = Integer.parseInt(headers.get("content-length"));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length header: " + headers.get("content-length"), e);
            }
        }

        String body = null;
        if (contentLength > 0) {
            char[] buf = new char[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int n = rd.read(buf, totalRead, contentLength - totalRead);
                if (n == -1) break; // premature end
                totalRead += n;
            }
            body = new String(buf, 0, totalRead);
        }

        // --- Clock ---
        int clock = -1;
        if (headers.containsKey("clock")) {
            try {
                clock = Integer.parseInt(headers.get("clock"));
            } catch (NumberFormatException e) {
                clock = -1; // fallback if malformed
            }
        }

        return new HttpRequest(method, resource, headers, body, clock);
    }

    public void write(BufferedWriter wr, String host) throws IOException {
        // --- Request line ---
        wr.write(method + " " + resource + " HTTP/1.1\r\n");

        // --- Required headers ---
        wr.write("Host: " + host + "\r\n");
        wr.write("User-Agent: ATOMClient/1.0\r\n");
        wr.write("Clock: " + clock + "\r\n");

        // --- Custom headers ---
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            wr.write(entry.getKey() + ": " + entry.getValue() + "\r\n");
        }

        // --- Body ---
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            wr.write("Content-Length: " + bytes.length + "\r\n");
            wr.write("\r\n");
            wr.write(body);
        } else {
            wr.write("\r\n"); // blank line separates headers from body
        }

        wr.flush();
    }

    public String getMethod() { return method; }
    public String getResource() { return resource; }
    public Map<String, String> getHeaders() { return headers; }
    public String getBody() { return body; }
    public int getClock() { return clock; }
}

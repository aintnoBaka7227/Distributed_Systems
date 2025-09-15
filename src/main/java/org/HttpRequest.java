package org;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Represents an HTTP request, include a Lamport clock.
 * Supports:
 *  - Parsing an incoming request (server side).
 *  - Writing/sending a request (client side).
 */
public class HttpRequest {
    /** HTTP method */
    private final String method;

    /** Resource being requested */
    private final String resource;

    /** Headers */
    private final Map<String, String> headers;

    /** Optional body */
    private final String body;

    /** Lamport clock value carried with this request */
    private final int clock;

    /**
     * Construct a HttpRequest.
     * @param method   HTTP method (e.g., GET, PUT)
     * @param resource Requested path/resource
     * @param headers  Request headers (nullable, replaced with an empty map if null)
     * @param body     Request body (nullable)
     * @param clock    Lamport clock value
     */
    public HttpRequest(String method, String resource,
                       Map<String, String> headers, String body, int clock) {
        this.method = method;
        this.resource = resource;
        this.headers = headers != null ? headers : new HashMap<>();
        this.body = body;
        this.clock = clock;
    }

    // SERVER SIDE: Parsing request

    /**
     * Parse an HttpRequest from an input stream.
     * @param rd BufferedReader connected to the client socket
     * @return Parsed HttpRequest object
     * @throws IOException if the request is malformed or incomplete
     */
    public static HttpRequest parse(BufferedReader rd) throws IOException {
        // Request line
        String requestLine = rd.readLine();
        if (requestLine == null || requestLine.split(" ").length < 2) {
            throw new IOException("Invalid request line: " + requestLine);
        }

        String[] parts = requestLine.split(" ");
        String method = parts[0];
        String resource = parts[1];

        // Headers
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = rd.readLine()) != null && !line.isEmpty()) {
            int idx = line.indexOf(":");
            if (idx > 0) {
                headers.put(
                        line.substring(0, idx).trim().toLowerCase(),
                        line.substring(idx + 1).trim()
                );
            }
        }

        // Body
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

            if (totalRead < contentLength) {
                throw new IOException("Body shorter than Content-Length: expected "
                        + contentLength + " but got " + totalRead);
            }

            // Read body as string
            body = new String(buf, 0, totalRead);
        }

        // parse clock, handle case-sensitive and two versions of clock header
        int clock = -1;
        String clockStr = null;
        if (headers.containsKey("lamport-clock")) {
            clockStr = headers.get("lamport-clock");
        } else if (headers.containsKey("clock")) {
            clockStr = headers.get("clock");
        }

        if (clockStr != null) {
            try {
                clock = Integer.parseInt(clockStr);
            } catch (NumberFormatException e) {
                clock = -1; // fallback if malformed
            }
        }

        return new HttpRequest(method, resource, headers, body, clock);
    }

    // CLIENT SIDE: Writing request

    /**
     * Write this HttpRequest to a stream.
     * @param wr   BufferedWriter connected to the server socket
     * @param host The Host header value (e.g., "localhost")
     * @throws IOException if writing fails
     */
    public void write(BufferedWriter wr, String host) throws IOException {
        // Request line
        wr.write(method + " " + resource + " HTTP/1.1\r\n");

        // Required headers
        wr.write("Host: " + host + "\r\n");
        wr.write("User-Agent: ATOMClient/1.0\r\n");
        wr.write("Clock: " + clock + "\r\n");

        // Optional headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            wr.write(entry.getKey() + ": " + entry.getValue() + "\r\n");
        }

        // Body
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            wr.write("Content-Length: " + bytes.length + "\r\n");
            wr.write("\r\n");
            wr.write(body);
        } else {
            wr.write("\r\n"); // a blank line separates headers from the body
        }

        wr.flush();
    }


    // Getters
    public String getMethod() { return method; }
    public String getResource() { return resource; }
    public Map<String, String> getHeaders() { return headers; }
    public String getBody() { return body; }
    public int getClock() { return clock; }
}

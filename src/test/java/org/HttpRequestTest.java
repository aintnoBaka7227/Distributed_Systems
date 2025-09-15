package org;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HttpRequestTest {

    // Test cases for parsing a simple GET request
    @Test
    void testParseSimpleGetRequest() throws Exception {
        String raw =
                "GET /weather HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "User-Agent: ATOMClient/1.0\r\n" +
                        "Clock: 10\r\n" +
                        "\r\n";

        BufferedReader rd = new BufferedReader(
                new StringReader(raw));

        HttpRequest req = HttpRequest.parse(rd);

        assertEquals("GET", req.getMethod());
        assertEquals("/weather", req.getResource());
        assertEquals("ATOMClient/1.0", req.getHeaders().get("user-agent"));
        assertEquals(10, req.getClock());
        assertNull(req.getBody());
        assertEquals("localhost", req.getHeaders().get("host"));
    }

    // Test cases for parsing a simple POST request with no body
    @Test
    void testParsePostWithBody() throws Exception {
        String body = "{\"station\":\"Adelaide\"}";
        String raw =
                "POST /update HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "User-Agent: ATOMClient/1.0\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                        "Clock: 10\r\n" +
                        "\r\n" +
                        body;

        BufferedReader rd = new BufferedReader(
                new StringReader(raw));

        HttpRequest req = HttpRequest.parse(rd);

        assertEquals("POST", req.getMethod());
        assertEquals("/update", req.getResource());
        assertEquals(body, req.getBody());
        assertEquals(10, req.getClock());
        assertEquals("localhost", req.getHeaders().get("host"));
        assertEquals("ATOMClient/1.0", req.getHeaders().get("user-agent"));
        assertEquals("application/json", req.getHeaders().get("content-type"));
        assertEquals(String.valueOf(body.getBytes(StandardCharsets.UTF_8).length), req.getHeaders().get("content-length"));
        assertEquals("10", req.getHeaders().get("clock"));
    }

    // Test cases for parsing invalid requests
    @Test
    void testParseInvalidRequestLine() {
        // Case 1: wrong format
        String raw = "BADREQUEST\r\n\r\n";
        BufferedReader rd = new BufferedReader(new StringReader(raw));
        assertThrows(IOException.class, () -> HttpRequest.parse(rd));
        // Case 2: null
        BufferedReader rd2 = new BufferedReader(new StringReader(""));
        assertThrows(IOException.class, () -> HttpRequest.parse(rd2));
    }

    // Test cases for parsing invalid Content-Length
    @Test
    void testParseInvalidContentLength() {
        // Case 1: Non-integer Content-Length
        String rawNonInteger =
                "POST /data HTTP/1.1\r\n" +
                        "Content-Length: notanumber\r\n" +
                        "\r\n";

        BufferedReader reader1 = new BufferedReader(new StringReader(rawNonInteger));
        assertThrows(IOException.class, () -> HttpRequest.parse(reader1));

        // Case 2: Wrong count
        String rawWrongCount =
                "POST /data HTTP/1.1\r\n" +
                        "Content-Length: 3\r\n" +
                        "\r\n";

        BufferedReader reader2 = new BufferedReader(new StringReader(rawWrongCount));
        assertThrows(IOException.class, () -> HttpRequest.parse(reader2));
    }

    // Test cases for writing requests: GET without a body
    @Test
    void testWriteRequestWithoutBody() throws Exception {
        HttpRequest req = new HttpRequest(
                "GET",
                "/ping",
                new HashMap<>(),
                null,
                7
        );

        StringWriter sw = new StringWriter();
        BufferedWriter writer = new BufferedWriter(sw);
        req.write(writer, "localhost");

        String out = sw.toString();
        assertTrue(out.startsWith("GET /ping HTTP/1.1"));
        assertTrue(out.contains("Host: localhost"));
        assertTrue(out.contains("Clock: 7"));
        assertTrue(out.endsWith("\r\n\r\n"));
    }

    // Test cases for writing requests: POST with a body
    @Test
    void testWriteRequestWithBody() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        HttpRequest req = new HttpRequest(
                "POST",
                "/update",
                headers,
                "{\"msg\":\"hi\"}",
                42
        );

        StringWriter sw = new StringWriter();
        BufferedWriter writer = new BufferedWriter(sw);
        req.write(writer, "server");

        String out = sw.toString();
        assertTrue(out.startsWith("POST /update HTTP/1.1"));
        assertTrue(out.contains("Host: server"));
        assertTrue(out.contains("User-Agent: ATOMClient/1.0"));
        assertTrue(out.contains("Clock: 42"));
        assertTrue(out.contains("Content-Type: application/json"));
        assertTrue(out.contains("Content-Length: 12"));
        assertTrue(out.endsWith("{\"msg\":\"hi\"}"));
    }

    // Test cases for parsing invalid Clock header
    @Test
    void testParseMalformedClock() throws Exception {
        String raw =
                "GET /data HTTP/1.1\r\n" +
                        "Clock: notanumber\r\n" +
                        "\r\n";

        BufferedReader reader = new BufferedReader(new StringReader(raw));
        HttpRequest req = HttpRequest.parse(reader);

        // Fallback behavior: -1
        assertEquals(-1, req.getClock());
    }
}


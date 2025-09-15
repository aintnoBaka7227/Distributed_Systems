package org;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpResponseTest
 * Unit tests for the HttpResponse class.
 * Covers writing responses to output streams and parsing responses from input streams.
 * Test cases:
 * Writing a response
 * Parsing a valid response
 * Fallback parsing when Content-Length is missing
 * Error handling for invalid/malformed responses
 * Edge cases: malformed Content-Length, malformed Clock, truncated body
 */

class HttpResponseTest {

    // Test case for writing a response with a body
    @Test
    void testWriteResponseWithBody() throws Exception {
        String body = "{\"msg\":\"hi\"}";
        HttpResponse resp = new HttpResponse(200, "OK", body, 7);

        StringWriter sw = new StringWriter();
        BufferedWriter wr = new BufferedWriter(sw);
        resp.write(wr);

        String out = sw.toString();
        assertTrue(out.startsWith("HTTP/1.1 200 OK"));
        assertTrue(out.contains("Content-Type: application/json"));
        assertTrue(out.contains("Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length));
        assertTrue(out.contains("Clock: 7"));
        assertTrue(out.endsWith(body));
    }

    // Test case for writing a response without a body
    @Test
    void testWriteResponseWithoutBody() throws Exception {
        HttpResponse resp = new HttpResponse(204, "No Content", null, 100);

        StringWriter sw = new StringWriter();
        BufferedWriter wr = new BufferedWriter(sw);
        resp.write(wr);

        String out = sw.toString();
        assertTrue(out.startsWith("HTTP/1.1 204 No Content"));
        assertTrue(out.contains("Content-Type: application/json"));
        assertFalse(out.contains("Content-Length"));
        assertTrue(out.contains("Clock: 100"));
        assertTrue(out.endsWith("\r\n\r\n"));
    }

    // Test case for parsing a response with a body
    @Test
    void testParseResponseWithBody() throws Exception {
        String body = "{\"station\":\"Adelaide\"}";
        String raw =
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: " + body.length() + "\r\n" +
                        "Clock: 11\r\n" +
                        "\r\n" +
                        body;

        BufferedReader rd = new BufferedReader(new StringReader(raw));
        HttpResponse resp = HttpResponse.parse(rd);

        assertEquals(200, resp.getStatusCode());
        assertEquals("OK", resp.getStatusMessage());
        assertEquals(body, resp.getBody());
        assertEquals(11, resp.getClock());
        assertEquals("application/json", resp.getHeaders().get("Content-Type"));
        assertEquals(String.valueOf(body.length()), resp.getHeaders().get("Content-Length"));
    }

    // Test case for parsing a response without a body
    @Test
    void testParseResponseWithoutBody() throws Exception {
        String raw =
                "HTTP/1.1 204 No Content\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Clock: 5\r\n" +
                        "\r\n";

        BufferedReader rd = new BufferedReader(new StringReader(raw));
        HttpResponse resp = HttpResponse.parse(rd);

        assertEquals(204, resp.getStatusCode());
        assertEquals("No Content", resp.getStatusMessage());
        assertNull(resp.getBody());
        assertEquals(5, resp.getClock());
    }

    // Test case for parsing a response with a body that is without a content-length header -> fallback to read until EOF
    @Test
    void testParseResponseFallbackBody() throws Exception {
        String raw =
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Clock: 2\r\n" +
                        "\r\n" +
                        "{\"msg\":\"hi\"}";

        BufferedReader rd = new BufferedReader(new StringReader(raw));
        HttpResponse resp = HttpResponse.parse(rd);

        assertEquals(200, resp.getStatusCode());
        assertEquals("{\"msg\":\"hi\"}", resp.getBody());
    }


    // Test cases for parsing invalid status line
    @Test
    void testParseInvalidStatusLine() {
        String raw = "NOT_HTTP_RESPONSE\r\n\r\n";
        BufferedReader rd = new BufferedReader(new StringReader(raw));
        assertThrows(IOException.class, () -> HttpResponse.parse(rd));
    }

    // Test cases for parsing invalid Content-Length -> back to 0
    @Test
    void testParseInvalidContentLength() throws Exception {
        String raw =
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: notnum\r\n" +
                        "Clock: 1\r\n" +
                        "\r\n" +
                        "{\"msg\":\"oops\"}";

        BufferedReader rd = new BufferedReader(new StringReader(raw));
        HttpResponse resp = HttpResponse.parse(rd);
        assertNull(resp.getBody());
    }

    // Test cases for parsing invalid Clock -> back to -1
    @Test
    void testParseMalformedClock() throws Exception {
        String raw =
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: 10\r\n" +
                        "Clock: notanumber\r\n" +
                        "\r\n" +
                        "1234567890";

        BufferedReader rd = new BufferedReader(new StringReader(raw));
        HttpResponse resp = HttpResponse.parse(rd);

        assertEquals(-1, resp.getClock());
    }

    // Test cases for parsing truncated body -> back to truncated body
    @Test
    void testParseTruncatedBody() throws Exception {
        String raw =
                "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: 10\r\n" +
                        "Clock: 1\r\n" +
                        "\r\n" +
                        "short";

        BufferedReader reader = new BufferedReader(new StringReader(raw));
        HttpResponse resp = HttpResponse.parse(reader);
        assertEquals("short", resp.getBody());
    }
}


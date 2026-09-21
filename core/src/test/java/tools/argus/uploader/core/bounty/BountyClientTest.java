package tools.argus.uploader.core.bounty;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BountyClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(int status, String body, List<String> seen) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/needed-regions", exchange -> {
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.getRequestHeaders().forEach((name, values) -> seen.add("header:" + name.toLowerCase()));
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/needed-regions";
    }

    @Test
    @Timeout(20)
    void sendsOnlyAPlainGetWithNoCredentials() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        String url = serve(200, "{\"needed\":[]}", seen);

        BountyClient.Result result = new BountyClient().fetch(url, 50);

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertTrue(seen.contains("GET /api/needed-regions?limit=50"), seen.toString());
        assertFalse(seen.contains("header:authorization"), "no token goes with this request");
        assertFalse(seen.contains("header:cookie"));
        assertFalse(seen.contains("header:x-region-modified"));
    }

    @Test
    @Timeout(20)
    void reportsAServerErrorWithoutThrowing() throws Exception {
        String url = serve(503, "{\"needed\":[]}", new ArrayList<>());

        BountyClient.Result result = new BountyClient().fetch(url, 50);

        assertFalse(result.ok());
        assertEquals(503, result.statusCode());
        assertEquals("HTTP 503", result.error());
    }

    @Test
    @Timeout(20)
    void reportsAReplyItCannotReadWithoutThrowing() throws Exception {
        String url = serve(200, "<html>maintenance</html>", new ArrayList<>());

        BountyClient.Result result = new BountyClient().fetch(url, 50);

        assertFalse(result.ok());
        assertTrue(result.error().startsWith("couldn't read the reply"), result.error());
    }

    @Test
    @Timeout(20)
    void reportsAnUnreachableServerWithoutThrowing() {
        BountyClient.Result result = new BountyClient().fetch("http://127.0.0.1:1/api/needed-regions", 50);

        assertFalse(result.ok());
        assertEquals(-1, result.statusCode());
    }

    @Test
    void refusesUrlsThatAreNotHttp() {
        assertFalse(new BountyClient().fetch("file:///etc/passwd", 50).ok());
        assertFalse(new BountyClient().fetch("not a url", 50).ok());
    }

    @Test
    @Timeout(20)
    void appendsTheLimitToAUrlThatAlreadyHasAQuery() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        String url = serve(200, "{\"needed\":[]}", seen) + "?dimension=overworld";

        new BountyClient().fetch(url, 10);

        assertTrue(seen.contains("GET /api/needed-regions?dimension=overworld&limit=10"), seen.toString());
    }
}

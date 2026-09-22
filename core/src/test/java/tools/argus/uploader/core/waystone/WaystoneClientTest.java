package tools.argus.uploader.core.waystone;

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

class WaystoneClientTest {

    private HttpServer server;
    private final List<String> seen = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private WaystoneClient serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            seen.add("auth:" + auth);
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            seen.add("type:" + type);
            seen.add("body:" + new String(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new WaystoneClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    @Timeout(20)
    void theListAndBotStatusAreFetchedWithNoCredentials() throws Exception {
        WaystoneClient client = serve(200, "{\"waystones\":[],\"online\":true,\"busy\":false,\"queued\":0,\"secondsUntilReady\":0}");

        assertTrue(client.waystones().ok());
        assertTrue(client.systemStatus().ok());

        assertTrue(seen.contains("GET /api/waystones"), seen.toString());
        assertTrue(seen.contains("GET /api/waystone/system-status"), seen.toString());
        assertFalse(seen.stream().anyMatch(s -> s.startsWith("auth:Bearer")), "public calls carry no token: " + seen);
    }

    @Test
    @Timeout(20)
    void tokenCallsCarryTheBearerTokenAndTheTeleportPostsJson() throws Exception {
        WaystoneClient client = serve(200, "{\"ok\":true,\"ign\":\"Me\",\"balance\":3,\"id\":\"r1\",\"status\":\"queued\"}");

        assertEquals(3, client.tokenInfo("cm_secret").value().balance());
        WaystoneClient.Reply<WaystoneParser.Started> started = client.requestTeleport("cm_secret", "My \"Base\"");
        assertTrue(started.ok());
        assertEquals("r1", started.value().id());
        assertTrue(client.teleportStatus("cm_secret", "r 1&x").ok());

        assertTrue(seen.contains("auth:Bearer cm_secret"), seen.toString());
        assertTrue(seen.contains("POST /api/waystone/tp"), seen.toString());
        assertTrue(seen.contains("type:application/json"), seen.toString());
        assertTrue(seen.contains("body:{\"waystone\":\"My \\\"Base\\\"\"}"), seen.toString());
        assertTrue(seen.contains("GET /api/waystone/tp/status?id=r+1%26x"), seen.toString());
    }

    @Test
    @Timeout(20)
    void explainsTheDocumentedErrorsInPlainWords() throws Exception {
        WaystoneClient client = serve(402, "{\"error\":\"insufficient_tokens\",\"balance\":0}");
        WaystoneClient.Reply<WaystoneParser.Started> broke = client.requestTeleport("t", "Group");
        assertFalse(broke.ok());
        assertEquals(402, broke.statusCode());
        assertEquals("insufficient_tokens", broke.errorCode());
        assertTrue(broke.message().contains("balance 0"), broke.message());

        assertTrue(WaystoneClient.explain(409, "already_queued", -1).contains("already have a teleport"));
        assertTrue(WaystoneClient.explain(400, "no_ign", -1).contains("my-tokens"));
        assertTrue(WaystoneClient.explain(401, "not_authenticated", -1).contains("token"));
        assertTrue(WaystoneClient.explain(404, "no_waystone", -1).contains("isn't available"));
        assertEquals("ARGUS answered HTTP 500.", WaystoneClient.explain(500, null, -1));
    }

    @Test
    @Timeout(20)
    void failuresComeBackAsRepliesNotExceptions() throws Exception {
        assertFalse(serve(200, "<html>oops</html>").waystones().ok());
        assertFalse(new WaystoneClient("http://127.0.0.1:1").waystones().ok());
        assertFalse(new WaystoneClient("file:///etc").waystones().ok());
        assertFalse(new WaystoneClient("not a url").systemStatus().ok());
        assertTrue(new WaystoneClient("http://127.0.0.1:1").tokenInfo("t").message().startsWith("Couldn't reach ARGUS"));
    }
}

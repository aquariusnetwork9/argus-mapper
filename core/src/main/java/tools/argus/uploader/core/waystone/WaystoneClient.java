package tools.argus.uploader.core.waystone;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Talks to the waystone API. The waystone list and the bot's status are public and sent with no
 * credentials; the balance and the teleport calls carry the player's upload token as a bearer token.
 * Every method blocks, never throws, and reports a failure as a {@link Reply} with a message that can
 * be shown to the player as it is.
 */
public final class WaystoneClient {

    /** @param message why it failed, in words for the player; null when it worked */
    public record Reply<T>(T value, int statusCode, String errorCode, String message) {

        public boolean ok() {
            return message == null;
        }
    }

    private final String baseUrl;
    private final String urlProblem;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WaystoneClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String problem = null;
        try {
            String scheme = URI.create(this.baseUrl).getScheme();
            if (scheme == null || !(scheme.equals("https") || scheme.equals("http"))) {
                problem = "The waystone URL must start with https://.";
            }
        } catch (IllegalArgumentException e) {
            problem = "The waystone URL isn't valid.";
        }
        this.urlProblem = problem;
    }

    public Reply<List<Waystone>> waystones() {
        return send(() -> get("/api/waystones", null), WaystoneParser::waystones);
    }

    public Reply<SystemStatus> systemStatus() {
        return send(() -> get("/api/waystone/system-status", null), WaystoneParser::systemStatus);
    }

    public Reply<TokenInfo> tokenInfo(String token) {
        return send(() -> get("/api/token-info", token), WaystoneParser::tokenInfo);
    }

    public Reply<WaystoneParser.Started> requestTeleport(String token, String waystoneName) {
        String body = "{\"waystone\":" + jsonString(waystoneName) + "}";
        return send(() -> builder("/api/waystone/tp", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), WaystoneParser::started);
    }

    public Reply<TeleportStatus> teleportStatus(String token, String requestId) {
        String path = "/api/waystone/tp/status?id=" + URLEncoder.encode(requestId, StandardCharsets.UTF_8);
        return send(() -> get(path, token), WaystoneParser::teleportStatus);
    }

    private HttpRequest.Builder get(String path, String token) {
        return builder(path, token).GET();
    }

    private HttpRequest.Builder builder(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private <T> Reply<T> send(Supplier<HttpRequest.Builder> request, Function<String, T> parse) {
        if (urlProblem != null) {
            return new Reply<>(null, -1, null, urlProblem);
        }
        try {
            HttpResponse<String> response = http.send(request.get().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() / 100 != 2) {
                String code = WaystoneParser.errorCode(body);
                return new Reply<>(null, response.statusCode(), code,
                        explain(response.statusCode(), code, WaystoneParser.balanceOf(body)));
            }
            return new Reply<>(parse.apply(body), response.statusCode(), null, null);
        } catch (IllegalArgumentException e) {
            return new Reply<>(null, 200, null, "ARGUS sent a reply this couldn't read (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply<>(null, -1, null, "Interrupted.");
        } catch (IOException e) {
            return new Reply<>(null, -1, null, "Couldn't reach ARGUS (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ").");
        }
    }

    /** What an error from the waystone API means, for the player. */
    public static String explain(int status, String code, int balance) {
        if (code == null) {
            return "ARGUS answered HTTP " + status + ".";
        }
        return switch (code) {
            case "not_authenticated" -> "ARGUS didn't accept your token. Check it on the Token tab.";
            case "no_ign" -> "Set your Minecraft username at map.argus.tools/my-tokens first.";
            case "no_waystone" -> "That waystone isn't available any more.";
            case "already_queued" -> "You already have a teleport in progress (here or on the website).";
            case "insufficient_tokens" -> "You don't have a Waystone token to spend"
                    + (balance >= 0 ? " (balance " + balance + ")" : "") + ". Uploading more regions earns them.";
            default -> "ARGUS said: " + code + ".";
        };
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}

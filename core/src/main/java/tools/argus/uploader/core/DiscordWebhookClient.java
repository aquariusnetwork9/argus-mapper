package tools.argus.uploader.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Posts a plain Discord webhook embed - no bot, no gateway connection, just
 * one HTTP POST to a URL the user creates in their own Discord channel's
 * Integrations settings. Deliberately hand-rolls the tiny bit of JSON this
 * needs rather than pulling in a JSON library, matching core's zero-dependency
 * design (see README "How the code is organised").
 */
public final class DiscordWebhookClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public record Field(String name, String value, boolean inline) {
    }

    public record Result(boolean success, int statusCode, String error) {
    }

    public Result postEmbed(String webhookUrl, String title, String description, List<Field> fields) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return new Result(false, -1, "No webhook URL configured.");
        }
        String json = buildEmbedPayload(title, description, fields);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = response.statusCode() / 100 == 2;
            return new Result(ok, response.statusCode(), ok ? null : response.body());
        } catch (IOException e) {
            return new Result(false, -1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, -1, "Interrupted");
        }
    }

    static String buildEmbedPayload(String title, String description, List<Field> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"embeds\":[{");
        sb.append("\"title\":").append(quote(title)).append(',');
        sb.append("\"description\":").append(quote(description));
        if (fields != null && !fields.isEmpty()) {
            sb.append(",\"fields\":[");
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"name\":").append(quote(f.name()))
                        .append(",\"value\":").append(quote(f.value()))
                        .append(",\"inline\":").append(f.inline())
                        .append('}');
            }
            sb.append(']');
        }
        sb.append("}]}");
        return sb.toString();
    }

    static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
        sb.append('"');
        return sb.toString();
    }

}

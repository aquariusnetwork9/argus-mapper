package tools.argus.uploader.core.bounty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fetches the public {@code needed-regions} list. A plain GET: no token, no identity, no body - the
 * only thing that leaves the machine is the request for the list itself.
 */
public final class BountyClient {

    private static final int MAX_BODY_CHARS = 1_000_000;

    public record Result(BountyResponse response, int statusCode, String error) {

        public boolean ok() {
            return response != null;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Blocking; never throws - a failure comes back as a {@link Result} with an {@code error}. */
    public Result fetch(String baseUrl, int limit) {
        URI uri;
        try {
            uri = URI.create(baseUrl + (baseUrl.contains("?") ? "&" : "?") + "limit=" + limit);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("https") || scheme.equals("http"))) {
                return new Result(null, -1, "the bounty URL must start with https://");
            }
        } catch (IllegalArgumentException e) {
            return new Result(null, -1, "the bounty URL isn't valid");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return new Result(null, response.statusCode(), "HTTP " + response.statusCode());
            }
            String body = response.body();
            if (body == null || body.length() > MAX_BODY_CHARS) {
                return new Result(null, response.statusCode(), "the reply was empty or too large");
            }
            return new Result(BountyParser.parse(body), response.statusCode(), null);
        } catch (IllegalArgumentException e) {
            return new Result(null, 200, "couldn't read the reply: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, -1, "interrupted");
        } catch (IOException e) {
            return new Result(null, -1, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}

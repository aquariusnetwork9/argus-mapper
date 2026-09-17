package tools.argus.uploader.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ArgusUploadClient {

    private final HttpClient http;
    private final ArgusConfig config;
    private final BlackzoneStore blackzoneStore;

    public ArgusUploadClient(ArgusConfig config) {
        this(config, BlackzoneStore.empty());
    }

    /**
     * @param blackzoneStore checked here as well as by {@link UploadRunner#filterRegions}
     *                       before a region ever reaches this point - same double-enforcement
     *                       shape as {@link CoordLimits}, so this is the one method that talks
     *                       to the network and a bug in the earlier filter alone can't defeat a
     *                       blackzone.
     */
    public ArgusUploadClient(ArgusConfig config, BlackzoneStore blackzoneStore) {
        this.config = config;
        this.blackzoneStore = blackzoneStore;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public record UploadResult(int statusCode, String body, IOException ioError) {
        public boolean success() {
            return ioError == null && statusCode / 100 == 2;
        }

        public boolean isAuthError() {
            return ioError == null && (statusCode == 401 || statusCode == 403);
        }

        public boolean isRateLimited() {
            return ioError == null && statusCode == 429;
        }

        public boolean isServerError() {
            return ioError == null && statusCode >= 500;
        }
    }

    public UploadResult upload(RegionFile region, String batchId) {
        if (!CoordLimits.inRange(region.regionX(), region.regionZ())) {
            // Enforced here too, independent of XaeroScanner already filtering these
            // out - see CoordLimits. This is the only method that talks to the
            // network, so this guard alone is enough to make the cap unconditional.
            return new UploadResult(-1, null, new IOException(
                    "Refusing to upload " + region.filename() + ": coordinate exceeds the +/-"
                            + CoordLimits.MAX_ABS_COORD + " limit."));
        }
        if (blackzoneStore.isBlackzoned(region.dimension(), config.layer, region)) {
            return new UploadResult(-1, null, new IOException(
                    "Refusing to upload " + region.filename() + ": covered by a blackzone."));
        }
        try {
            String url = config.apiBaseUrl
                    + "?layer=" + enc(config.layer)
                    + "&filename=" + enc(region.filename())
                    + "&dimension=" + enc(region.dimension())
                    + "&batchId=" + enc(batchId);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + config.token)
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofFile(region.path()))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new UploadResult(response.statusCode(), response.body(), null);
        } catch (IOException e) {
            return new UploadResult(-1, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new UploadResult(-1, null, new IOException("Interrupted", e));
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

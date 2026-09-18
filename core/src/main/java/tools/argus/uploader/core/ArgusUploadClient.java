package tools.argus.uploader.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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

    /** Blocking wrapper around {@link #uploadAsync} - {@link UploadRunner} uses the async form
     *  directly so a slow/hanging request can't block cancellation; this is kept for callers
     *  (and tests) that just want a single synchronous call. */
    public UploadResult upload(RegionFile region, String batchId) {
        return uploadAsync(region, batchId).join();
    }

    /**
     * Same request {@link #upload} makes, but as a cancellable {@link CompletableFuture} -
     * {@link UploadRunner} holds onto this so a real {@code cancel()} can abort an in-flight
     * request immediately (via {@link CompletableFuture#cancel}) instead of only skipping the
     * *next* one, which previously left cancellation waiting on however long the current request
     * happened to take (up to the 60s request timeout below, times retries) - confirmed live:
     * {@code /argus cancel} went unanswered for minutes while a slow request sat in flight.
     *
     * <p>Never completes exceptionally on its own - a network failure becomes a normal
     * {@link UploadResult} with {@link UploadResult#ioError()} set, same as {@link #upload}
     * always did, via {@link CompletableFuture#handle}. It only completes exceptionally if the
     * caller cancels the returned future directly, which {@link UploadRunner} checks for and
     * treats as "stop the run", not as a per-region failure to record.
     */
    public CompletableFuture<UploadResult> uploadAsync(RegionFile region, String batchId) {
        if (!CoordLimits.inRange(region.regionX(), region.regionZ())) {
            // Enforced here too, independent of XaeroScanner already filtering these
            // out - see CoordLimits. This is the only method that talks to the
            // network, so this guard alone is enough to make the cap unconditional.
            return CompletableFuture.completedFuture(new UploadResult(-1, null, new IOException(
                    "Refusing to upload " + region.filename() + ": coordinate exceeds the +/-"
                            + CoordLimits.MAX_ABS_COORD + " limit.")));
        }
        if (blackzoneStore.isBlackzoned(region.dimension(), config.layer, region)) {
            return CompletableFuture.completedFuture(new UploadResult(-1, null, new IOException(
                    "Refusing to upload " + region.filename() + ": covered by a blackzone.")));
        }
        HttpRequest request;
        try {
            String url = config.apiBaseUrl
                    + "?layer=" + enc(config.layer)
                    + "&filename=" + enc(region.filename())
                    + "&dimension=" + enc(region.dimension())
                    + "&batchId=" + enc(batchId);
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + config.token)
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofFile(region.path()))
                    .build();
        } catch (Exception e) {
            // e.g. the region file vanished from disk between scan and upload - ofFile() checks
            // existence eagerly (a checked FileNotFoundException) rather than deferring to send
            // time.
            return CompletableFuture.completedFuture(new UploadResult(-1, null, new IOException(e)));
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    if (throwable == null) {
                        return new UploadResult(response.statusCode(), response.body(), null);
                    }
                    if (throwable instanceof CancellationException) {
                        // Our own UploadRunner.cancel() aborting this exact request - let it
                        // propagate as a cancellation rather than masking it as a normal
                        // network failure the retry logic might otherwise act on.
                        throw (CancellationException) throwable;
                    }
                    Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
                    IOException ioError = cause instanceof IOException ie ? ie : new IOException(cause);
                    return new UploadResult(-1, null, ioError);
                });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

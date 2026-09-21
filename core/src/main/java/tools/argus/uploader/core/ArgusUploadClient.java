package tools.argus.uploader.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * @param retryAfterMillis the server's {@code Retry-After} in milliseconds, or 0 if it sent none
     * @param headers          the response headers, names to comma-joined values
     * @param bodySentMillis   request start until the body was handed to the network, -1 if it never was
     * @param totalMillis      request start until the reply (or failure)
     */
    public record UploadResult(int statusCode, String body, IOException ioError, long retryAfterMillis,
                               Map<String, String> headers, long bodySentMillis, long totalMillis) {

        public UploadResult(int statusCode, String body, IOException ioError) {
            this(statusCode, body, ioError, 0L, Map.of(), -1L, -1L);
        }

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
        return uploadAsync(region, batchId, () -> {
        });
    }

    /**
     * @param onBodySent run once, when the whole request body has been handed to the network -
     *                   before the server has replied. It is not called for a request refused
     *                   before anything is sent, so callers must not depend on it firing.
     */
    public CompletableFuture<UploadResult> uploadAsync(RegionFile region, String batchId, Runnable onBodySent) {
        long startedNanos = System.nanoTime();
        AtomicLong sentNanos = new AtomicLong(-1);
        Runnable bodySent = () -> {
            sentNanos.compareAndSet(-1, System.nanoTime());
            onBodySent.run();
        };
        if (!CoordLimits.inRange(region.regionX(), region.regionZ())) {
            // Enforced here too, independent of XaeroScanner already filtering these
            // out - see CoordLimits. This is the only method that talks to the
            // network, so this guard alone is enough to make the cap unconditional.
            return CompletableFuture.completedFuture(new UploadResult(-1, null, new IOException(
                    "Refusing to upload " + region.filename() + ": region coordinate exceeds the +/-"
                            + CoordLimits.MAX_ABS_REGION + " region limit.")));
        }
        if (blackzoneStore.isBlackzoned(region.dimension(), config.layer, region)) {
            return CompletableFuture.completedFuture(new UploadResult(-1, null, new IOException(
                    "Refusing to upload " + region.filename() + ": covered by a blackzone.")));
        }
        if (UploadHold.isHeld(region)) {
            return CompletableFuture.completedFuture(new UploadResult(-1, null, new IOException(
                    "Refusing to upload " + region.filename() + ": its area is still being auto-mapped.")));
        }
        HttpRequest request;
        try {
            String url = config.apiBaseUrl
                    + "?layer=" + enc(config.layer)
                    + "&filename=" + enc(region.filename())
                    + "&dimension=" + enc(region.dimension())
                    + "&batchId=" + enc(batchId);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(180))
                    .header("Authorization", "Bearer " + config.token)
                    .header("Content-Type", "application/octet-stream");
            // The zip's own entry time is a local-time DOS stamp with no time zone, so it can't be
            // compared across contributors; this is the same moment, in UTC epoch millis.
            if (region.lastModifiedMillis() > 0) {
                builder.header("X-Region-Modified", Long.toString(region.lastModifiedMillis()));
            }
            request = builder.POST(new SentNotifier(HttpRequest.BodyPublishers.ofFile(region.path()), bodySent)).build();
        } catch (Exception e) {
            // e.g. the region file vanished from disk between scan and upload - ofFile() checks
            // existence eagerly (a checked FileNotFoundException) rather than deferring to send
            // time.
            return CompletableFuture.completedFuture(new UploadResult(-1, null, new IOException(e)));
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    long totalMillis = (System.nanoTime() - startedNanos) / 1_000_000;
                    long sent = sentNanos.get();
                    long bodySentMillis = sent < 0 ? -1 : (sent - startedNanos) / 1_000_000;
                    if (throwable == null) {
                        return new UploadResult(response.statusCode(), response.body(), null, retryAfterMillis(response),
                                headersOf(response), bodySentMillis, totalMillis);
                    }
                    if (throwable instanceof CancellationException) {
                        // Our own UploadRunner.cancel() aborting this exact request - let it
                        // propagate as a cancellation rather than masking it as a normal
                        // network failure the retry logic might otherwise act on.
                        throw (CancellationException) throwable;
                    }
                    Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
                    IOException ioError = cause instanceof IOException ie ? ie : new IOException(cause);
                    return new UploadResult(-1, null, ioError, 0L, Map.of(), bodySentMillis, totalMillis);
                });
    }

    private static long retryAfterMillis(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(value -> {
            try {
                return Math.max(0L, Long.parseLong(value.trim()) * 1000L);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    private static Map<String, String> headersOf(HttpResponse<?> response) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));
        return headers;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Fires {@code onSent} once when the wrapped body has been fully published; a resent body (the client retrying a dead connection) fires it only the first time. */
    private static final class SentNotifier implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher inner;
        private final Runnable onSent;
        private final AtomicBoolean fired = new AtomicBoolean();

        SentNotifier(HttpRequest.BodyPublisher inner, Runnable onSent) {
            this.inner = inner;
            this.onSent = onSent;
        }

        @Override
        public long contentLength() {
            return inner.contentLength();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            inner.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    subscriber.onNext(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    subscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                    if (fired.compareAndSet(false, true)) {
                        onSent.run();
                    }
                }
            });
        }
    }
}

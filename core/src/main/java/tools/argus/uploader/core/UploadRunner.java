package tools.argus.uploader.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Sends region files to the ARGUS API one at a time, waiting
 * {@link ArgusConfig#paceMillis} between the end of one response and the
 * start of the next request (which keeps 200 requests comfortably inside
 * both the 200/10min rate limit and the ~1-per-3s pacing ask by
 * construction), rolling to a fresh batchId every
 * {@link ArgusConfig#maxPerBatch} regions.
 */
public final class UploadRunner {

    private final ArgusUploadClient client;
    private final ArgusConfig config;
    private final UploadManifest manifest;
    private final UploadProgressListener listener;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "argus-uploader");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger doneCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private volatile int totalCount = 0;
    // The request currently in flight, if any - cancel() aborts this directly rather than only
    // ever preventing the *next* one, so a slow/hanging request can't hold cancellation hostage.
    private final AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();

    public UploadRunner(ArgusUploadClient client, ArgusConfig config, UploadManifest manifest, UploadProgressListener listener) {
        this.client = client;
        this.config = config;
        this.manifest = manifest;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getDoneCount() {
        return doneCount.get();
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void cancel() {
        cancelled.set(true);
        CompletableFuture<?> current = inFlight.get();
        if (current != null) {
            current.cancel(true);
        }
    }

    /**
     * Splits {@code found} into what will and won't be uploaded. Pure and side-effect-free (does
     * not touch the manifest or network) so it's directly unit-testable; {@link #start} is the
     * only caller in production.
     */
    static FilterResult filterRegions(List<RegionFile> found, UploadManifest manifest, long maxFileSizeBytes,
                                       Predicate<RegionFile> blackzoneFilter, boolean reuploadChanged) {
        List<RegionFile> toUpload = new ArrayList<>();
        int alreadyUploaded = 0;
        int tooLarge = 0;
        int excludedByBlackzone = 0;
        for (RegionFile region : found) {
            // A changed region only stops counting as "already uploaded" - it still falls through
            // to the size and blackzone checks below, so re-uploading can never bypass either.
            if (!manifest.needsUpload(region, reuploadChanged)) {
                alreadyUploaded++;
            } else if (region.sizeBytes() > maxFileSizeBytes) {
                tooLarge++;
            } else if (!blackzoneFilter.test(region)) {
                excludedByBlackzone++;
            } else {
                toUpload.add(region);
            }
        }
        return new FilterResult(toUpload, alreadyUploaded, tooLarge, excludedByBlackzone);
    }

    record FilterResult(List<RegionFile> toUpload, int alreadyUploaded, int tooLarge, int excludedByBlackzone) {
    }

    /** Equivalent to {@link #start(List, String, Predicate)} with nothing blackzoned. */
    public void start(List<RegionFile> found, String runIdPrefix) {
        start(found, runIdPrefix, region -> true);
    }

    /**
     * Filters, reports a summary, and if there's anything to upload, starts the background run.
     *
     * @param blackzoneFilter returns false for a region a {@link BlackzoneStore} says must never
     *                        be uploaded; checked here as well as inside {@link ArgusUploadClient}
     *                        itself, same double-enforcement shape as {@link CoordLimits} - a bug
     *                        in one layer alone can't defeat a blackzone.
     */
    public void start(List<RegionFile> found, String runIdPrefix, Predicate<RegionFile> blackzoneFilter) {
        if (running.get()) {
            listener.onFatalError("A run is already in progress.");
            return;
        }
        try {
            manifest.adoptBaselines(found);
        } catch (IOException ignored) {
            // Best-effort: without a baseline an old region just isn't re-upload-eligible yet.
        }
        FilterResult filtered = filterRegions(found, manifest, config.maxFileSizeBytes, blackzoneFilter,
                config.reuploadChangedRegions);
        List<RegionFile> toUpload = filtered.toUpload();
        listener.onSummary(found.size(), filtered.alreadyUploaded(), filtered.tooLarge(), filtered.excludedByBlackzone(), toUpload.size());
        if (toUpload.isEmpty()) {
            return;
        }
        listener.onQueueBuilt(toUpload);

        Deque<RegionFile> queue = new ArrayDeque<>(toUpload);
        totalCount = toUpload.size();
        doneCount.set(0);
        failCount.set(0);
        cancelled.set(false);
        running.set(true);

        executor.submit(() -> processNext(queue, runIdPrefix, 0, 0));
    }

    private void processNext(Deque<RegionFile> queue, String runIdPrefix, int batchIndex, int inBatch) {
        if (cancelled.get() || queue.isEmpty()) {
            running.set(false);
            listener.onComplete(succeededCount(), failCount.get());
            return;
        }
        if (inBatch >= config.maxPerBatch) {
            batchIndex++;
            inBatch = 0;
        }
        String batchId = runIdPrefix + "-" + batchIndex;
        RegionFile region = queue.poll();

        int currentBatch = batchIndex;
        int currentInBatch = inBatch;
        attempt(region, batchId, 0, () -> {
            int nextInBatch = currentInBatch + 1;
            int nextBatch = currentBatch;
            executor.schedule(() -> processNext(queue, runIdPrefix, nextBatch, nextInBatch), config.paceMillis, TimeUnit.MILLISECONDS);
        });
    }

    private void attempt(RegionFile region, String batchId, int retryCount, Runnable onHandled) {
        if (cancelled.get()) {
            finishCancelled();
            return;
        }
        listener.onRegionStarted(region);
        CompletableFuture<ArgusUploadClient.UploadResult> future = client.uploadAsync(region, batchId);
        inFlight.set(future);
        // whenComplete's callback can run on whichever thread the HTTP client finishes the
        // request on, not necessarily this executor's own thread - hop back onto it so every
        // decision about retrying/proceeding/finishing still happens on one thread, same as
        // before this was made async, rather than needing doneCount/failCount alone to carry
        // all of the thread-safety burden.
        future.whenComplete((result, throwable) ->
                executor.execute(() -> onAttemptComplete(region, batchId, retryCount, onHandled, result)));
    }

    private void onAttemptComplete(RegionFile region, String batchId, int retryCount, Runnable onHandled,
                                    ArgusUploadClient.UploadResult result) {
        inFlight.set(null);
        if (cancelled.get()) {
            // Either cancelled between the request starting and finishing (result reflects
            // whatever actually happened, and is still meaningful to log) or cancel() aborted
            // this exact request (result is null - cancellation short-circuited it) - either
            // way, stop rather than continue the queue or retry.
            finishCancelled();
            return;
        }

        if (result.success()) {
            try {
                manifest.markUploaded(region);
            } catch (IOException e) {
                listener.onRegionFailed(region, "uploaded but failed to record in manifest: " + e.getMessage(), doneCount.get(), totalCount);
            }
            doneCount.incrementAndGet();
            listener.onRegionUploaded(region, doneCount.get(), totalCount);
            onHandled.run();
            return;
        }

        if (result.isAuthError()) {
            running.set(false);
            listener.onFatalError("Upload rejected (HTTP " + result.statusCode() + ") - check the token. Stopping run.");
            return;
        }

        boolean retryable = result.isServerError() || result.isRateLimited() || result.ioError() != null;
        if (retryable && retryCount < config.maxRetries) {
            long backoff = result.isRateLimited() ? Math.max(10_000L, config.paceMillis * 3) : config.paceMillis * 2L;
            executor.schedule(() -> attempt(region, batchId, retryCount + 1, onHandled), backoff, TimeUnit.MILLISECONDS);
            return;
        }

        failCount.incrementAndGet();
        String reason = result.ioError() != null
                ? result.ioError().getMessage()
                : "HTTP " + result.statusCode() + (result.body() != null ? ": " + truncate(result.body()) : "");
        doneCount.incrementAndGet();
        listener.onRegionFailed(region, reason, doneCount.get(), totalCount);
        onHandled.run();
    }

    private void finishCancelled() {
        running.set(false);
        listener.onComplete(succeededCount(), failCount.get());
    }

    /** {@code doneCount} is every *resolved* attempt (success or failure combined, used for the
     *  X/Y progress display) - {@link UploadProgressListener#onComplete}'s first parameter is
     *  documented as the succeeded count specifically, so it needs this subtraction, not
     *  {@code doneCount} directly. Passing doneCount there was a real bug: whenever a run failed
     *  end to end, "N ok, N failed" printed with equal numbers by coincidence (doneCount ==
     *  failCount when nothing succeeded), reading like a partial success that never happened -
     *  confirmed live, see MANUAL_TEST_PLAN.md. */
    private int succeededCount() {
        return doneCount.get() - failCount.get();
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    public void shutdown() {
        cancel();
        executor.shutdown();
    }
}

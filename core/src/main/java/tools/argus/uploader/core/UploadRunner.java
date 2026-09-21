package tools.argus.uploader.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Sends region files to the ARGUS API with several requests overlapping, rolling to a fresh
 * batchId every {@link ArgusConfig#maxPerBatch} regions.
 *
 * <p>At most {@link ArgusConfig#uploadConcurrency} request bodies are on the wire at once. A
 * request stops counting against that as soon as its body has been handed to the network - the
 * next region starts then, without waiting for the server to finish processing the previous one -
 * and at most four times that many requests are open awaiting a reply. All bookkeeping (manifest,
 * counters, listener calls) happens on the single executor thread; only the HTTP calls overlap.
 */
public final class UploadRunner {

    private static final int OUTSTANDING_PER_SLOT = 4;
    private static final long RETRY_BACKOFF_MILLIS = 6_000L;
    private static final long RATE_LIMIT_BACKOFF_MILLIS = 10_000L;
    private static final long MAX_RETRY_AFTER_MILLIS = 120_000L;

    private final ArgusUploadClient client;
    private final ArgusConfig config;
    private final UploadManifest manifest;
    private final UploadProgressListener listener;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "argus-uploader");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger doneCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private volatile int totalCount = 0;
    private volatile Predicate<RegionFile> readyCheck = region -> true;
    private volatile Run current;

    public UploadRunner(ArgusUploadClient client, ArgusConfig config, UploadManifest manifest, UploadProgressListener listener) {
        this.client = client;
        this.config = config;
        this.manifest = manifest;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Asked right before each region is sent; one it rejects is dropped from this run, unrecorded. */
    public void setReadyCheck(Predicate<RegionFile> readyCheck) {
        this.readyCheck = readyCheck;
    }

    public int getDoneCount() {
        return doneCount.get();
    }

    public int getTotalCount() {
        return totalCount;
    }

    /** Aborts every request in flight rather than only stopping new ones, so a hanging request can't hold cancellation hostage. */
    public void cancel() {
        Run run = current;
        if (run == null) {
            return;
        }
        run.cancelled = true;
        for (CompletableFuture<?> future : run.flying) {
            future.cancel(true);
        }
        post(() -> pump(run));
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
        int held = 0;
        for (RegionFile region : found) {
            // A changed region only stops counting as "already uploaded" - it still falls through
            // to the size and blackzone checks below, so re-uploading can never bypass either.
            if (!manifest.needsUpload(region, reuploadChanged)) {
                alreadyUploaded++;
            } else if (UploadHold.isHeld(region)) {
                held++;
            } else if (region.sizeBytes() > maxFileSizeBytes) {
                tooLarge++;
            } else if (!blackzoneFilter.test(region)) {
                excludedByBlackzone++;
            } else {
                toUpload.add(region);
            }
        }
        return new FilterResult(toUpload, alreadyUploaded, tooLarge, excludedByBlackzone, held);
    }

    record FilterResult(List<RegionFile> toUpload, int alreadyUploaded, int tooLarge, int excludedByBlackzone, int held) {
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
        start(found, runIdPrefix, blackzoneFilter, config.reuploadChangedRegions);
    }

    /** @param reuploadChanged overrides {@link ArgusConfig#reuploadChangedRegions} for this run */
    public void start(List<RegionFile> found, String runIdPrefix, Predicate<RegionFile> blackzoneFilter,
                      boolean reuploadChanged) {
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
                reuploadChanged);
        List<RegionFile> toUpload = filtered.toUpload();
        listener.onSummary(found.size(), filtered.alreadyUploaded(), filtered.tooLarge(), filtered.excludedByBlackzone(), toUpload.size());
        if (filtered.held() > 0) {
            listener.onHeldForMapping(filtered.held());
        }
        if (toUpload.isEmpty()) {
            return;
        }
        listener.onQueueBuilt(toUpload);

        UploadLog log = UploadLog.open(runIdPrefix, "queue=" + toUpload.size() + " uploadConcurrency=" + config.uploadConcurrency
                + " maxPerBatch=" + config.maxPerBatch + " maxRetries=" + config.maxRetries + " endpoint=" + config.apiBaseUrl
                + "\nfound=" + found.size() + " alreadyUploaded=" + filtered.alreadyUploaded() + " tooLarge=" + filtered.tooLarge()
                + " blackzoned=" + filtered.excludedByBlackzone() + " heldForMapping=" + filtered.held());
        Run run = new Run(new ArrayDeque<>(toUpload), runIdPrefix, log);
        totalCount = toUpload.size();
        doneCount.set(0);
        failCount.set(0);
        current = run;
        running.set(true);

        post(() -> pump(run));
    }

    private void pump(Run run) {
        if (run.finished) {
            return;
        }
        int slots = Math.max(1, Math.min(config.uploadConcurrency, ArgusConfig.MAX_UPLOAD_CONCURRENCY));
        while (!run.cancelled && run.transmitting < slots && run.outstanding < slots * OUTSTANDING_PER_SLOT) {
            long now = System.currentTimeMillis();
            if (now < run.gateUntil) {
                schedulePump(run, run.gateUntil - now);
                break;
            }
            Attempt next = run.retries.poll();
            if (next == null) {
                dropUnready(run);
                RegionFile region = run.queue.poll();
                if (region == null) {
                    break;
                }
                int batch = run.dispatched++ / Math.max(1, config.maxPerBatch);
                next = new Attempt(region, run.prefix + "-" + batch, 0);
            }
            launch(run, next);
        }
        boolean drained = run.queue.isEmpty() && run.retries.isEmpty() && run.pendingRetries == 0;
        if (run.outstanding == 0 && (run.cancelled || drained)) {
            run.finished = true;
            running.set(false);
            run.log.finish(succeededCount(), failCount.get(), run.cancelled);
            listener.onComplete(succeededCount(), failCount.get());
        }
    }

    private void dropUnready(Run run) {
        while (!run.queue.isEmpty() && !readyCheck.test(run.queue.peek())) {
            RegionFile deferred = run.queue.poll();
            run.log.note("SKIP", deferred.filename() + " changed again, left for a later run");
            listener.onRegionDeferred(deferred);
            totalCount--;
        }
    }

    private void schedulePump(Run run, long delayMillis) {
        if (run.pumpScheduled) {
            return;
        }
        run.pumpScheduled = true;
        schedule(() -> {
            run.pumpScheduled = false;
            pump(run);
        }, delayMillis);
    }

    private void launch(Run run, Attempt attempt) {
        run.outstanding++;
        run.transmitting++;
        attempt.transmitting = true;
        run.log.requestStarted(attempt.region, attempt.batchId, attempt.retryCount, run.outstanding, run.transmitting);
        listener.onRegionStarted(attempt.region);
        CompletableFuture<ArgusUploadClient.UploadResult> future = client.uploadAsync(attempt.region, attempt.batchId,
                () -> post(() -> onBodySent(run, attempt)));
        run.flying.add(future);
        // whenComplete can run on whichever thread the HTTP client finishes the request on - hop
        // back onto the executor so every decision below still happens on one thread.
        future.whenComplete((result, throwable) -> post(() -> {
            run.flying.remove(future);
            onAttemptComplete(run, attempt, result);
        }));
    }

    private void onBodySent(Run run, Attempt attempt) {
        if (run.finished || !attempt.transmitting) {
            return;
        }
        attempt.transmitting = false;
        run.transmitting--;
        pump(run);
    }

    private void onAttemptComplete(Run run, Attempt attempt, ArgusUploadClient.UploadResult result) {
        if (run.finished) {
            return;
        }
        run.outstanding--;
        if (attempt.transmitting) {
            attempt.transmitting = false;
            run.transmitting--;
        }
        run.log.requestFinished(attempt.region, result);
        if (run.cancelled || result == null) {
            pump(run);
            return;
        }

        RegionFile region = attempt.region;
        if (result.success()) {
            try {
                manifest.markUploaded(region);
            } catch (IOException e) {
                listener.onRegionFailed(region, "uploaded but failed to record in manifest: " + e.getMessage(), doneCount.get(), totalCount);
            }
            doneCount.incrementAndGet();
            listener.onRegionUploaded(region, doneCount.get(), totalCount);
            pump(run);
            return;
        }

        if (result.isAuthError()) {
            run.finished = true;
            run.cancelled = true;
            for (CompletableFuture<?> future : run.flying) {
                future.cancel(true);
            }
            running.set(false);
            run.log.finish(succeededCount(), failCount.get(), true);
            listener.onFatalError("Upload rejected (HTTP " + result.statusCode() + ") - check the token. Stopping run.");
            return;
        }

        boolean retryable = result.isServerError() || result.isRateLimited() || result.ioError() != null;
        if (retryable && attempt.retryCount < config.maxRetries) {
            long backoff = RETRY_BACKOFF_MILLIS;
            if (result.isRateLimited()) {
                backoff = result.retryAfterMillis() > 0
                        ? Math.min(result.retryAfterMillis(), MAX_RETRY_AFTER_MILLIS)
                        : RATE_LIMIT_BACKOFF_MILLIS;
                run.gateUntil = Math.max(run.gateUntil, System.currentTimeMillis() + backoff);
                run.log.paused(result.statusCode(), backoff);
            }
            run.log.retrying(region, attempt.retryCount + 1, backoff);
            run.pendingRetries++;
            Attempt retry = new Attempt(region, attempt.batchId, attempt.retryCount + 1);
            schedule(() -> {
                if (run.finished) {
                    return;
                }
                run.pendingRetries--;
                run.retries.add(retry);
                pump(run);
            }, backoff);
            pump(run);
            return;
        }

        failCount.incrementAndGet();
        String reason = result.ioError() != null
                ? result.ioError().getMessage()
                : "HTTP " + result.statusCode() + (result.body() != null ? ": " + truncate(result.body()) : "");
        doneCount.incrementAndGet();
        run.log.failed(region, reason);
        listener.onRegionFailed(region, reason, doneCount.get(), totalCount);
        pump(run);
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

    private void post(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    private void schedule(Runnable task, long delayMillis) {
        try {
            executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // shut down
        }
    }

    public void shutdown() {
        cancel();
        executor.shutdown();
    }

    private static final class Attempt {
        final RegionFile region;
        final String batchId;
        final int retryCount;
        boolean transmitting;

        Attempt(RegionFile region, String batchId, int retryCount) {
            this.region = region;
            this.batchId = batchId;
            this.retryCount = retryCount;
        }
    }

    /** One run's queue and counters; everything but {@code cancelled} and {@code flying} is touched only on the executor thread. */
    private static final class Run {
        final Deque<RegionFile> queue;
        final String prefix;
        final Deque<Attempt> retries = new ArrayDeque<>();
        final Set<CompletableFuture<?>> flying = ConcurrentHashMap.newKeySet();
        final UploadLog log;
        volatile boolean cancelled;
        boolean finished;
        boolean pumpScheduled;
        int dispatched;
        int transmitting;
        int outstanding;
        int pendingRetries;
        long gateUntil;

        Run(Deque<RegionFile> queue, String prefix, UploadLog log) {
            this.queue = queue;
            this.prefix = prefix;
            this.log = log;
        }
    }
}

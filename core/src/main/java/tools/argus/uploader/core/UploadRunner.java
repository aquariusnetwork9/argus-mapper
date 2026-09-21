package tools.argus.uploader.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
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
 *
 * <p>The server pushes back in two ways. "Busy" (its global upload rate) is not the region's
 * fault: the number of open requests is halved, new ones wait a short jittered moment, and the
 * region is tried again without using up its retries; the number creeps back up on successes.
 * Any other 429 is the per-token quota: new requests stop, everything unsent goes back in the queue,
 * and after a wait one probe request tests whether the quota has reset - if so the run carries on by
 * itself, if not it waits again. If it hasn't cleared after half an hour the run ends and what wasn't
 * sent is left for a later run. Cancelling ends the wait at once.
 */
public final class UploadRunner {

    private static final int OUTSTANDING_PER_SLOT = 4;
    private static final long RETRY_BACKOFF_MILLIS = 6_000L;
    private static final long BUSY_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_BUSY_BACKOFF_MILLIS = 8_000L;
    private static final long MAX_RETRY_AFTER_MILLIS = 120_000L;
    private static final long DECREASE_COOLDOWN_MILLIS = 1_000L;
    private static final int MAX_BUSY_RETRIES = 60;
    private static final long QUOTA_FIRST_WAIT_MILLIS = 30_000L;
    private static final long QUOTA_PROBE_INTERVAL_MILLIS = 60_000L;
    private static final long QUOTA_GIVE_UP_MILLIS = 30 * 60_000L;

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
    private volatile long quotaFirstWaitMillis = QUOTA_FIRST_WAIT_MILLIS;
    private volatile long quotaProbeIntervalMillis = QUOTA_PROBE_INTERVAL_MILLIS;
    private volatile long quotaGiveUpMillis = QUOTA_GIVE_UP_MILLIS;

    public UploadRunner(ArgusUploadClient client, ArgusConfig config, UploadManifest manifest, UploadProgressListener listener) {
        this.client = client;
        this.config = config;
        this.manifest = manifest;
        this.listener = listener;
    }

    void setQuotaTiming(long firstWaitMillis, long probeIntervalMillis, long giveUpMillis) {
        this.quotaFirstWaitMillis = firstWaitMillis;
        this.quotaProbeIntervalMillis = probeIntervalMillis;
        this.quotaGiveUpMillis = giveUpMillis;
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
        Run run = new Run(new ArrayDeque<>(toUpload), runIdPrefix, log, slots() * OUTSTANDING_PER_SLOT);
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
        int slots = slots();
        while (!run.cancelled && !run.stopping && run.transmitting < slots && run.outstanding < openLimit(run)) {
            long now = System.currentTimeMillis();
            if (run.quotaMode) {
                if (run.probing) {
                    break;
                }
                if (now - run.quotaSince > quotaGiveUpMillis) {
                    run.stopping = true;
                    break;
                }
                if (now < run.quotaResumeAt) {
                    schedulePump(run, run.quotaResumeAt - now);
                    break;
                }
                Attempt probe = nextAttempt(run);
                if (probe == null) {
                    break;
                }
                probe.probe = true;
                run.probing = true;
                run.log.note("QUOTA", "probing with " + probe.region.filename());
                launch(run, probe);
                break;
            }
            if (now < run.gateUntil) {
                schedulePump(run, run.gateUntil - now);
                break;
            }
            Attempt next = nextAttempt(run);
            if (next == null) {
                break;
            }
            launch(run, next);
        }
        boolean drained = run.queue.isEmpty() && run.retries.isEmpty() && run.waiting.isEmpty();
        if (run.outstanding == 0 && (run.cancelled || run.stopping || drained)) {
            run.finished = true;
            if (run.stopping && !run.cancelled) {
                releaseUnsent(run);
            }
            run.log.finish(succeededCount(), failCount.get(), run.cancelled);
            running.set(false);
            listener.onComplete(succeededCount(), failCount.get());
        }
    }

    private Attempt nextAttempt(Run run) {
        Attempt retry = run.retries.poll();
        if (retry != null) {
            return retry;
        }
        dropUnready(run);
        RegionFile region = run.queue.poll();
        if (region == null) {
            return null;
        }
        int batch = run.dispatched++ / Math.max(1, config.maxPerBatch);
        return new Attempt(region, run.prefix + "-" + batch, 0);
    }

    private int slots() {
        return Math.max(1, Math.min(config.uploadConcurrency, ArgusConfig.MAX_UPLOAD_CONCURRENCY));
    }

    private int openLimit(Run run) {
        return Math.max(1, (int) Math.min(run.limit, slots() * OUTSTANDING_PER_SLOT));
    }

    /** Hands everything the quota kept from being sent back as "left for later", unrecorded so a later run sends it. */
    private void releaseUnsent(Run run) {
        List<RegionFile> unsent = new ArrayList<>();
        for (Attempt attempt : run.retries) {
            unsent.add(attempt.region);
        }
        for (Attempt attempt : run.waiting) {
            unsent.add(attempt.region);
        }
        unsent.addAll(run.queue);
        for (RegionFile region : unsent) {
            listener.onRegionDeferred(region);
            totalCount--;
        }
        run.log.note("QUOTA", "gave up waiting; " + unsent.size() + " region(s) left for a later run");
        listener.onNotice("The server's upload limit didn't clear for " + quotaGiveUpMillis / 60_000 + " minutes, so "
                + unsent.size() + " region(s) are left for a later run.");
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
        attempt.launchedAt = System.currentTimeMillis();
        run.log.requestStarted(attempt.region, attempt.batchId, attempt.retryCount, run.outstanding, run.transmitting);
        listener.onRegionStarted(attempt.region);
        CompletableFuture<ArgusUploadClient.UploadResult> future = client.uploadAsync(attempt.region, attempt.batchId,
                () -> post(() -> onBodySent(run, attempt)));
        run.flying.add(future);
        if (run.cancelled) {
            future.cancel(true);
        }
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
        boolean quotaReply = result.isRateLimited() && !result.isServerBusy();
        if (attempt.probe) {
            run.probing = false;
            if (!quotaReply) {
                if (result.ioError() == null) {
                    run.quotaMode = false;
                    run.quotaClearedAt = System.currentTimeMillis();
                    run.limit = Math.min(2.0, slots() * OUTSTANDING_PER_SLOT);
                    run.log.note("QUOTA", "cleared after " + (System.currentTimeMillis() - run.quotaSince) / 1000 + "s");
                } else {
                    run.quotaResumeAt = System.currentTimeMillis() + quotaProbeIntervalMillis;
                }
            }
        }
        if (result.success()) {
            try {
                manifest.markUploaded(region);
            } catch (IOException e) {
                listener.onRegionFailed(region, "uploaded but failed to record in manifest: " + e.getMessage(), doneCount.get(), totalCount);
            }
            doneCount.incrementAndGet();
            run.consecutiveBusy = 0;
            run.limit = Math.min(slots() * OUTSTANDING_PER_SLOT, run.limit + 1.0 / Math.max(1.0, run.limit));
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

        if (quotaReply) {
            waitForQuota(run, attempt, result);
            pump(run);
            return;
        }

        if (result.isServerBusy() && attempt.busyCount < MAX_BUSY_RETRIES) {
            easeOff(run, result);
            run.retries.add(new Attempt(region, attempt.batchId, attempt.retryCount, attempt.busyCount + 1));
            pump(run);
            return;
        }

        boolean retryable = result.isServerError() || result.isRateLimited() || result.ioError() != null;
        if (retryable && attempt.retryCount < config.maxRetries) {
            run.log.retrying(region, attempt.retryCount + 1, RETRY_BACKOFF_MILLIS);
            Attempt retry = new Attempt(region, attempt.batchId, attempt.retryCount + 1, attempt.busyCount);
            run.waiting.add(retry);
            schedule(() -> {
                if (run.finished) {
                    return;
                }
                run.waiting.remove(retry);
                run.retries.add(retry);
                pump(run);
            }, RETRY_BACKOFF_MILLIS);
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

    /** Puts the region back and holds new requests until a probe shows the per-user limit has reset. */
    private void waitForQuota(Run run, Attempt attempt, ArgusUploadClient.UploadResult result) {
        long now = System.currentTimeMillis();
        boolean episodeStart = !run.quotaMode;
        if (episodeStart && attempt.launchedAt < run.quotaClearedAt) {
            // sent before a probe showed the limit had reset, so this refusal is old news
            run.retries.add(new Attempt(attempt.region, attempt.batchId, attempt.retryCount, attempt.busyCount));
            listener.onRegionRequeued(attempt.region);
            return;
        }
        if (episodeStart) {
            run.quotaMode = true;
            run.quotaSince = now;
            run.quotaResumeAt = now + quotaFirstWaitMillis;
        } else if (attempt.probe) {
            run.quotaResumeAt = now + quotaProbeIntervalMillis;
        }
        run.retries.add(new Attempt(attempt.region, attempt.batchId, attempt.retryCount, attempt.busyCount));
        listener.onRegionRequeued(attempt.region);
        run.log.note("QUOTA", attempt.region.filename() + " refused: " + truncate(String.valueOf(result.body())));
        if (episodeStart) {
            int remaining = run.queue.size() + run.retries.size() + run.waiting.size() + run.outstanding;
            listener.onNotice("The server's per-user upload limit was reached with about " + remaining
                    + " region(s) left to send. Waiting for it to reset; the run resumes by itself (/argus cancel stops it).");
        }
    }

    /** One "busy" event halves the open-request limit and holds new requests briefly; the several replies of one burst count once. */
    private void easeOff(Run run, ArgusUploadClient.UploadResult result) {
        long now = System.currentTimeMillis();
        long delay;
        if (result.retryAfterMillis() > 0) {
            delay = Math.min(result.retryAfterMillis(), MAX_RETRY_AFTER_MILLIS);
        } else {
            int exponent = Math.min(Math.max(run.consecutiveBusy - 1, 0), 3);
            delay = Math.min(MAX_BUSY_BACKOFF_MILLIS, BUSY_BACKOFF_MILLIS << exponent)
                    + ThreadLocalRandom.current().nextLong(500);
        }
        if (now - run.lastDecrease >= DECREASE_COOLDOWN_MILLIS) {
            run.limit = Math.max(1.0, run.limit / 2);
            run.lastDecrease = now;
            run.consecutiveBusy++;
            run.log.paused(result.statusCode(), delay, openLimit(run));
        }
        run.gateUntil = Math.max(run.gateUntil, now + delay);
        if (!run.busyNoticed) {
            run.busyNoticed = true;
            listener.onNotice("The server is at its upload rate, so uploads are slowing down. Nothing is lost.");
        }
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
        final int busyCount;
        boolean transmitting;
        boolean probe;
        long launchedAt;

        Attempt(RegionFile region, String batchId, int retryCount) {
            this(region, batchId, retryCount, 0);
        }

        Attempt(RegionFile region, String batchId, int retryCount, int busyCount) {
            this.region = region;
            this.batchId = batchId;
            this.retryCount = retryCount;
            this.busyCount = busyCount;
        }
    }

    /** One run's queue and counters; everything but {@code cancelled} and {@code flying} is touched only on the executor thread. */
    private static final class Run {
        final Deque<RegionFile> queue;
        final String prefix;
        final Deque<Attempt> retries = new ArrayDeque<>();
        final Set<Attempt> waiting = new LinkedHashSet<>();
        final Set<CompletableFuture<?>> flying = ConcurrentHashMap.newKeySet();
        final UploadLog log;
        volatile boolean cancelled;
        boolean finished;
        boolean stopping;
        boolean pumpScheduled;
        boolean busyNoticed;
        boolean quotaMode;
        boolean probing;
        int dispatched;
        int transmitting;
        int outstanding;
        int consecutiveBusy;
        double limit;
        long gateUntil;
        long lastDecrease;
        long quotaSince;
        long quotaResumeAt;
        long quotaClearedAt;

        Run(Deque<RegionFile> queue, String prefix, UploadLog log, double limit) {
            this.queue = queue;
            this.prefix = prefix;
            this.log = log;
            this.limit = limit;
        }
    }
}

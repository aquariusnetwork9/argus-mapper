package tools.argus.uploader.core;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    }

    /** Filters, reports a summary, and if there's anything to upload, starts the background run. */
    public void start(List<RegionFile> found, String runIdPrefix) {
        if (running.get()) {
            listener.onFatalError("A run is already in progress.");
            return;
        }
        List<RegionFile> toUpload = new ArrayList<>();
        int alreadyUploaded = 0;
        int tooLarge = 0;
        for (RegionFile region : found) {
            if (manifest.isUploaded(region)) {
                alreadyUploaded++;
                continue;
            }
            if (region.sizeBytes() > config.maxFileSizeBytes) {
                tooLarge++;
                continue;
            }
            toUpload.add(region);
        }
        listener.onSummary(found.size(), alreadyUploaded, tooLarge, toUpload.size());
        if (toUpload.isEmpty()) {
            return;
        }

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
            listener.onComplete(doneCount.get(), failCount.get());
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
            running.set(false);
            listener.onComplete(doneCount.get(), failCount.get());
            return;
        }
        ArgusUploadClient.UploadResult result = client.upload(region, batchId);

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

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    public void shutdown() {
        cancel();
        executor.shutdown();
    }
}

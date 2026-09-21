package tools.argus.uploader.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadRunnerConcurrencyTest {

    private HttpServer server;
    private ExecutorService serverThreads;
    private final List<Path> scratch = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverThreads != null) {
            serverThreads.shutdownNow();
        }
        for (Path dir : scratch) {
            try (var files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange, int requestNumber) throws Exception;
    }

    private ArgusConfig serve(Handler handler) throws IOException {
        AtomicInteger requestNumber = new AtomicInteger();
        serverThreads = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/upload", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                handler.handle(exchange, requestNumber.incrementAndGet());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                exchange.close();
            }
        });
        server.start();

        ArgusConfig config = new ArgusConfig();
        config.apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
        config.token = "x";
        config.layer = "y";
        config.maxRetries = 3;
        return config;
    }

    private static void reply(HttpExchange exchange, int status) throws IOException {
        byte[] body = "{}".getBytes();
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static List<RegionFile> regions(Path dir, int count) throws Exception {
        List<RegionFile> regions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String filename = i + "_0.zip";
            Path file = dir.resolve(filename);
            Files.writeString(file, "fake-region-bytes-" + i);
            regions.add(new RegionFile(file, filename, i, 0, "overworld", Files.size(file)));
        }
        return regions;
    }

    /** For tests that abort requests mid-send: those can hold their file open a moment on Windows, which would fail the temp dir cleanup. */
    private Path scratchDir() throws IOException {
        Path dir = Files.createTempDirectory("argus-runner-test");
        scratch.add(dir);
        return dir;
    }

    private static final class Recorder implements UploadProgressListener {
        final AtomicInteger uploaded = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger fatal = new AtomicInteger();
        final AtomicInteger completions = new AtomicInteger();
        final List<String> notices = Collections.synchronizedList(new ArrayList<>());
        final List<RegionFile> deferred = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch fatalSeen = new CountDownLatch(1);

        @Override
        public void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int excludedByBlackzone, int toUpload) {
        }

        @Override
        public void onRegionUploaded(RegionFile region, int done, int total) {
            uploaded.incrementAndGet();
        }

        @Override
        public void onRegionFailed(RegionFile region, String reason, int done, int total) {
            failed.incrementAndGet();
        }

        @Override
        public void onNotice(String message) {
            notices.add(message);
        }

        @Override
        public void onRegionDeferred(RegionFile region) {
            deferred.add(region);
        }

        @Override
        public void onFatalError(String message) {
            fatal.incrementAndGet();
            fatalSeen.countDown();
        }

        @Override
        public void onComplete(int succeeded, int failedCount) {
            completions.incrementAndGet();
            finished.countDown();
        }
    }

    @Test
    @Timeout(20)
    void nextRegionStartsWithoutWaitingForTheServerToFinishTheLast(@TempDir Path dir) throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ArgusConfig config = serve((exchange, n) -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            Thread.sleep(400);
            active.decrementAndGet();
            reply(exchange, 200);
        });
        config.uploadConcurrency = 1;

        Recorder recorder = new Recorder();
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config, manifest, recorder);
        List<RegionFile> regions = regions(dir, 8);

        long started = System.nanoTime();
        runner.start(regions, "overlap-test");
        assertTrue(recorder.finished.await(15, TimeUnit.SECONDS), "run should have finished");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(8, recorder.uploaded.get());
        assertTrue(peak.get() >= 2, "replies were awaited one at a time: peak " + peak.get());
        assertTrue(peak.get() <= 4, "more replies outstanding than the cap allows: peak " + peak.get());
        assertTrue(elapsedMillis < 8 * 400, "eight 400 ms replies should overlap, took " + elapsedMillis + " ms");
        for (RegionFile region : regions) {
            assertFalse(UploadManifest.load(dir.resolve("manifest.txt")).needsUpload(region, false));
        }
    }

    @Test
    @Timeout(20)
    void aRateLimitedReplyPausesTheRunForRetryAfterThenRetries(@TempDir Path dir) throws Exception {
        ArgusConfig config = serve((exchange, n) -> {
            if (n == 1) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                reply(exchange, 429);
            } else {
                reply(exchange, 200);
            }
        });
        config.uploadConcurrency = 2;

        Recorder recorder = new Recorder();
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config,
                UploadManifest.load(dir.resolve("manifest.txt")), recorder);

        long started = System.nanoTime();
        runner.start(regions(dir, 3), "rate-limit-test");
        assertTrue(recorder.finished.await(15, TimeUnit.SECONDS), "run should have finished");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(3, recorder.uploaded.get());
        assertEquals(0, recorder.failed.get());
        assertTrue(elapsedMillis >= 900, "the retry should have waited out Retry-After, took " + elapsedMillis + " ms");
    }

    @Test
    @Timeout(20)
    void aBusyServerSlowsTheRunDownWithoutFailingRegions(@TempDir Path dir) throws Exception {
        ArgusConfig config = serve((exchange, n) -> {
            if (n <= 5) {
                byte[] body = "{\"error\":\"server_busy\",\"message\":\"Server is at its global upload rate.\"}".getBytes();
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            } else {
                reply(exchange, 200);
            }
        });
        config.uploadConcurrency = 2;
        config.maxRetries = 0;

        Recorder recorder = new Recorder();
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config,
                UploadManifest.load(dir.resolve("manifest.txt")), recorder);

        runner.start(regions(dir, 6), "busy-test");
        assertTrue(recorder.finished.await(15, TimeUnit.SECONDS), "run should have finished");

        assertEquals(6, recorder.uploaded.get());
        assertEquals(0, recorder.failed.get(), "a busy server is not the region's fault, even with no retries allowed");
        assertEquals(1, recorder.notices.size(), "the player is told once");
        assertTrue(recorder.deferred.isEmpty());
    }

    private static void quotaReply(HttpExchange exchange) throws IOException {
        byte[] body = "Too many requests - try again later.".getBytes();
        exchange.sendResponseHeaders(429, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static int unrecorded(Path dir, List<RegionFile> regions) throws IOException {
        int count = 0;
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        for (RegionFile region : regions) {
            if (manifest.needsUpload(region, false)) {
                count++;
            }
        }
        return count;
    }

    @Test
    @Timeout(20)
    void hittingThePerUserLimitPausesThenResumesByItself(@TempDir Path dir) throws Exception {
        ArgusConfig config = serve((exchange, n) -> {
            if (n >= 4 && n <= 7) {
                quotaReply(exchange);
            } else {
                reply(exchange, 200);
            }
        });
        config.uploadConcurrency = 1;

        Recorder recorder = new Recorder();
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config,
                UploadManifest.load(dir.resolve("manifest.txt")), recorder);
        runner.setQuotaTiming(200, 200, 60_000);
        List<RegionFile> regions = regions(dir, 12);

        runner.start(regions, "quota-resume-test");
        assertTrue(recorder.finished.await(15, TimeUnit.SECONDS), "run should have finished");

        assertEquals(12, recorder.uploaded.get());
        assertEquals(0, recorder.failed.get(), "the limit is not the regions' fault");
        assertTrue(recorder.deferred.isEmpty(), "nothing is given up on when the limit clears");
        assertFalse(recorder.notices.isEmpty());
        assertTrue(recorder.notices.get(0).contains("resumes by itself"), recorder.notices.get(0));
        assertEquals(0, unrecorded(dir, regions));
        assertEquals(1, recorder.completions.get());
    }

    @Test
    @Timeout(20)
    void aLimitThatNeverClearsEndsTheRunAndLeavesTheRestForLater(@TempDir Path dir) throws Exception {
        ArgusConfig config = serve((exchange, n) -> {
            if (n <= 3) {
                reply(exchange, 200);
            } else {
                quotaReply(exchange);
            }
        });
        config.uploadConcurrency = 1;

        Recorder recorder = new Recorder();
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config,
                UploadManifest.load(dir.resolve("manifest.txt")), recorder);
        runner.setQuotaTiming(50, 50, 400);
        List<RegionFile> regions = regions(dir, 12);

        runner.start(regions, "quota-giveup-test");
        assertTrue(recorder.finished.await(15, TimeUnit.SECONDS), "run should have finished");

        assertEquals(3, recorder.uploaded.get());
        assertEquals(0, recorder.failed.get());
        assertEquals(9, recorder.deferred.size(), "everything not sent is handed back");
        assertTrue(recorder.notices.get(recorder.notices.size() - 1).contains("didn't clear"), recorder.notices.toString());
        assertEquals(9, unrecorded(dir, regions), "the unsent regions stay eligible for the next run");
        assertEquals(1, recorder.completions.get());
    }

    @Test
    @Timeout(20)
    void nothingIsSentWhileWaitingOnTheLimitAndCancelEndsTheWait(@TempDir Path dir) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        ArgusConfig config = serve((exchange, n) -> {
            requests.incrementAndGet();
            quotaReply(exchange);
        });
        config.uploadConcurrency = 1;

        Recorder recorder = new Recorder();
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config,
                UploadManifest.load(dir.resolve("manifest.txt")), recorder);

        runner.start(regions(dir, 5), "quota-cancel-test");
        long deadline = System.currentTimeMillis() + 5_000;
        while (recorder.notices.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(recorder.notices.isEmpty(), "the wait should have been announced");
        Thread.sleep(300);
        int before = requests.get();
        Thread.sleep(700);
        assertEquals(before, requests.get(), "no requests while waiting for the limit to reset");
        assertTrue(runner.isRunning());

        runner.cancel();
        assertTrue(recorder.finished.await(3, TimeUnit.SECONDS), "cancel should end the wait at once");
        assertEquals(1, recorder.completions.get());
        assertFalse(runner.isRunning());
    }

    @Test
    @Timeout(20)
    void aRejectedTokenStopsTheWholeRunOnceWithoutCompleting() throws Exception {
        Path dir = scratchDir();
        ArgusConfig config = serve((exchange, n) -> reply(exchange, 401));
        config.uploadConcurrency = 3;

        Recorder recorder = new Recorder();
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config,
                UploadManifest.load(dir.resolve("manifest.txt")), recorder);

        List<RegionFile> regions = regions(dir, 10);
        runner.start(regions, "auth-test");
        assertTrue(recorder.fatalSeen.await(10, TimeUnit.SECONDS), "the rejection should have been reported");
        Thread.sleep(500);

        assertEquals(1, recorder.fatal.get());
        assertEquals(0, recorder.completions.get());
        assertEquals(0, recorder.uploaded.get());
        assertFalse(runner.isRunning());
    }

    @Test
    @Timeout(20)
    void cancelWithSeveralRequestsInFlightCompletesExactlyOnce() throws Exception {
        Path dir = scratchDir();
        CountDownLatch severalSeen = new CountDownLatch(3);
        ArgusConfig config = serve((exchange, n) -> {
            severalSeen.countDown();
            Thread.sleep(30_000);
        });
        config.uploadConcurrency = 3;
        config.maxRetries = 0;

        Recorder recorder = new Recorder();
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config,
                UploadManifest.load(dir.resolve("manifest.txt")), recorder);

        List<RegionFile> regions = regions(dir, 10);
        runner.start(regions, "cancel-many-test");
        assertTrue(severalSeen.await(5, TimeUnit.SECONDS), "requests should have overlapped");
        runner.cancel();

        assertTrue(recorder.finished.await(5, TimeUnit.SECONDS), "cancel should end the run promptly");
        Thread.sleep(300);
        assertEquals(1, recorder.completions.get());
        assertFalse(runner.isRunning());
    }
}

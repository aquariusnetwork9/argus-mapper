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

    @Test
    @Timeout(20)
    void hittingTheTokenQuotaStopsTheRunAndLeavesTheRestForLater(@TempDir Path dir) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        ArgusConfig config = serve((exchange, n) -> {
            requests.incrementAndGet();
            if (n <= 3) {
                reply(exchange, 200);
            } else {
                byte[] body = "Too many requests - try again later.".getBytes();
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        config.uploadConcurrency = 1;

        Recorder recorder = new Recorder();
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config, manifest, recorder);
        List<RegionFile> regions = regions(dir, 12);

        runner.start(regions, "quota-test");
        assertTrue(recorder.finished.await(15, TimeUnit.SECONDS), "run should have finished");

        assertEquals(3, recorder.uploaded.get());
        assertEquals(0, recorder.failed.get(), "regions the quota stopped are not failures");
        assertEquals(9, recorder.deferred.size(), "everything not sent is handed back");
        assertTrue(requests.get() <= 3 + 4, "no more requests after the quota than were already open: " + requests.get());
        assertEquals(1, recorder.notices.size());
        assertTrue(recorder.notices.get(0).contains("limit"), recorder.notices.get(0));
        assertEquals(1, recorder.completions.get());
        int unrecorded = 0;
        for (RegionFile region : regions) {
            if (UploadManifest.load(dir.resolve("manifest.txt")).needsUpload(region, false)) {
                unrecorded++;
            }
        }
        assertEquals(9, unrecorded, "the unsent regions stay eligible for the next run");
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

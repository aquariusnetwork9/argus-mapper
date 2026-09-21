package tools.argus.uploader.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for a real bug found live on 6b6t: {@code /argus cancel} went unanswered for
 * minutes because the runner's single background thread was blocked inside a slow HTTP call with
 * no way to abort it - cancellation was only ever checked *between* attempts, never during one.
 * Uses a real (loopback) HTTP server that deliberately never responds, so this only passes if
 * {@link UploadRunner#cancel} actually aborts the in-flight request (via
 * {@link ArgusUploadClient#uploadAsync} being a cancellable {@code CompletableFuture}) rather
 * than just waiting it out - a regression here would hang until the {@code @Timeout} below kills
 * the test, not silently pass.
 */
class UploadRunnerCancelTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void cancelAbortsAnInFlightRequestPromptlyRatherThanWaitingItOut(@TempDir Path dir) throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", exchange -> {
            requestReceived.countDown();
            // Deliberately never responds within this test's lifetime - stands in for the slow/
            // hanging request that caused the real bug.
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        ArgusConfig config = new ArgusConfig();
        config.apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
        config.token = "x";
        config.layer = "y";
        config.maxRetries = 0;

        Path regionFile = dir.resolve("0_0.zip");
        Files.writeString(regionFile, "fake-region-bytes");
        RegionFile region = new RegionFile(regionFile, "0_0.zip", 0, 0, "overworld", Files.size(regionFile));

        ArgusUploadClient client = new ArgusUploadClient(config);
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        AtomicInteger completions = new AtomicInteger();
        UploadRunner runner = new UploadRunner(client, config, manifest, new UploadProgressListener() {
            @Override
            public void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int excludedByBlackzone, int toUpload) {
            }

            @Override
            public void onRegionUploaded(RegionFile region, int done, int total) {
            }

            @Override
            public void onRegionFailed(RegionFile region, String reason, int done, int total) {
            }

            @Override
            public void onFatalError(String message) {
            }

            @Override
            public void onComplete(int succeeded, int failed) {
                completions.incrementAndGet();
            }
        });

        runner.start(List.of(region), "cancel-test");
        assertTrue(requestReceived.await(5, TimeUnit.SECONDS), "request never reached the test server");

        runner.cancel();

        long deadline = System.currentTimeMillis() + 5_000;
        while (runner.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(runner.isRunning(), "cancel() should abort the in-flight request promptly, not wait out its full timeout/retries");
        assertEquals(1, completions.get());
    }
}

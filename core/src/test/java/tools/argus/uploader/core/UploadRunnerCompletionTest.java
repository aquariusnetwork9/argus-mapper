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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for a real bug found live: when every region in a run failed, the chat summary
 * read e.g. "11 ok, 11 failed" - a run that failed end to end looked like a partial success.
 * Cause: {@link UploadRunner#processNext}/{@code finishCancelled} passed {@code doneCount}
 * (every *resolved* attempt, success or failure) as {@link UploadProgressListener#onComplete}'s
 * succeeded-count parameter, rather than actual successes - the two only coincide (both equal to
 * the failure count) when nothing succeeded, which is exactly what made the bug read as plausible
 * rather than obviously wrong.
 */
class UploadRunnerCompletionTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void onCompleteReportsZeroSucceededWhenEveryRegionFails(@TempDir Path dir) throws Exception {
        CountDownLatch bothRequestsSeen = new CountDownLatch(2);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", exchange -> {
            bothRequestsSeen.countDown();
            byte[] body = "{\"error\":\"nope\"}".getBytes();
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ArgusConfig config = new ArgusConfig();
        config.apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
        config.token = "x";
        config.layer = "y";
        config.paceMillis = 50;
        config.maxRetries = 0;

        RegionFile a = writeRegion(dir, 0, 0);
        RegionFile b = writeRegion(dir, 1, 0);

        ArgusUploadClient client = new ArgusUploadClient(config);
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        AtomicInteger reportedSucceeded = new AtomicInteger(-1);
        AtomicInteger reportedFailed = new AtomicInteger(-1);
        CountDownLatch completed = new CountDownLatch(1);
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
                reportedSucceeded.set(succeeded);
                reportedFailed.set(failed);
                completed.countDown();
            }
        });

        runner.start(List.of(a, b), "completion-test");
        assertTrue(bothRequestsSeen.await(5, TimeUnit.SECONDS), "both regions should have been attempted");
        assertTrue(completed.await(5, TimeUnit.SECONDS), "run should have finished");

        assertEquals(0, reportedSucceeded.get(), "nothing actually succeeded - must not read as a partial success");
        assertEquals(2, reportedFailed.get());
    }

    private static RegionFile writeRegion(Path dir, int x, int z) throws Exception {
        String filename = x + "_" + z + ".zip";
        Path file = dir.resolve(filename);
        Files.writeString(file, "fake-region-bytes");
        return new RegionFile(file, filename, x, z, "overworld", Files.size(file));
    }
}

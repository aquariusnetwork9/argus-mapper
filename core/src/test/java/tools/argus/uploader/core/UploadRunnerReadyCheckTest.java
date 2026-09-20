package tools.argus.uploader.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadRunnerReadyCheckTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void aRegionTheReadyCheckRejectsIsNotSentNorRecorded(@TempDir Path dir) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ArgusConfig config = new ArgusConfig();
        config.apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
        config.token = "x";
        config.layer = "y";
        config.paceMillis = 20;
        config.maxRetries = 0;

        RegionFile sent = writeRegion(dir, 0, 0);
        RegionFile churning = writeRegion(dir, 1, 0);
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));

        List<RegionFile> deferred = Collections.synchronizedList(new ArrayList<>());
        List<Integer> totals = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger(-1);
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config, manifest, new UploadProgressListener() {
            @Override
            public void onSummary(int totalFound, int alreadyUploaded, int tooLarge, int excludedByBlackzone, int toUpload) {
            }

            @Override
            public void onRegionDeferred(RegionFile region) {
                deferred.add(region);
            }

            @Override
            public void onRegionUploaded(RegionFile region, int done, int total) {
                totals.add(total);
            }

            @Override
            public void onRegionFailed(RegionFile region, String reason, int done, int total) {
            }

            @Override
            public void onFatalError(String message) {
            }

            @Override
            public void onComplete(int ok, int failed) {
                succeeded.set(ok);
                completed.countDown();
            }
        });
        runner.setReadyCheck(region -> !region.equals(churning));

        runner.start(List.of(churning, sent), "ready-check-test");
        assertTrue(completed.await(5, TimeUnit.SECONDS), "run should have finished");

        assertEquals(1, requests.get());
        assertEquals(List.of(churning), deferred);
        assertEquals(List.of(1), totals, "the progress total shrinks by the region that was held back");
        assertEquals(1, succeeded.get());
        assertTrue(UploadManifest.load(dir.resolve("manifest.txt")).needsUpload(churning, false));
        assertFalse(UploadManifest.load(dir.resolve("manifest.txt")).needsUpload(sent, false));
    }

    private static RegionFile writeRegion(Path dir, int x, int z) throws Exception {
        String filename = x + "_" + z + ".zip";
        Path file = dir.resolve(filename);
        Files.writeString(file, "fake-region-bytes");
        return new RegionFile(file, filename, x, z, "overworld", Files.size(file));
    }
}

package tools.argus.uploader.core;

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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadLogTest {

    private HttpServer server;

    @AfterEach
    void cleanUp() {
        UploadLog.setDirectory(null);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(20)
    void aRunLeavesALogWithTimingsRateLimitHeadersAndASummaryButNeverTheToken(@TempDir Path dir) throws Exception {
        Path logDir = dir.resolve("logs");
        UploadLog.setDirectory(logDir);
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/upload", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int n = requests.incrementAndGet();
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", Integer.toString(200 - n));
            exchange.getResponseHeaders().add("X-Unrelated", "boring");
            byte[] body = ("{\"queued\":" + n + "}").getBytes();
            exchange.sendResponseHeaders(n == 5 ? 500 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ArgusConfig config = new ArgusConfig();
        config.apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
        config.token = "SECRET-TOKEN-VALUE";
        config.layer = "y";
        config.maxRetries = 0;

        List<RegionFile> regions = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Path file = dir.resolve(i + "_0.zip");
            Files.writeString(file, "fake-region-bytes");
            regions.add(new RegionFile(file, i + "_0.zip", i, 0, "overworld", Files.size(file)));
        }
        CountDownLatch completed = new CountDownLatch(1);
        UploadRunner runner = new UploadRunner(new ArgusUploadClient(config), config,
                UploadManifest.load(dir.resolve("manifest.txt")), new UploadProgressListener() {
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
                completed.countDown();
            }
        });

        runner.start(regions, "log-test");
        assertTrue(completed.await(10, TimeUnit.SECONDS), "run should have finished");

        String log = readOnlyLog(logDir);
        assertTrue(log.contains("START"), log);
        assertTrue(log.contains("status=200"), log);
        assertTrue(log.contains("status=500"), log);
        assertTrue(log.contains("sent="), log);
        assertTrue(log.toLowerCase().contains("x-ratelimit-remaining="), "rate limit headers are logged: " + log);
        assertTrue(log.contains("{\"queued\":"), "the reply body is logged: " + log);
        assertTrue(log.contains("5 ok, 1 failed"), log);
        assertTrue(log.contains("regions/min"), log);
        assertTrue(log.contains("reply time (ms)"), log);
        assertFalse(log.contains("SECRET-TOKEN-VALUE"), "the token must never be logged");
        assertFalse(log.toLowerCase().contains("authorization"), "request headers must never be logged");
    }

    @Test
    void nothingIsWrittenUntilADirectoryIsSet(@TempDir Path dir) throws IOException {
        UploadLog log = UploadLog.open("quiet", "description");
        log.note("X", "y");
        log.finish(0, 0, false);

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void onlyTheNewestFewLogsAreKept(@TempDir Path dir) throws IOException {
        UploadLog.setDirectory(dir);
        for (int i = 0; i < 35; i++) {
            Files.writeString(dir.resolve(String.format("20200101-0000%02d-old.log", i)), "x");
        }

        UploadLog.open("new", "d").finish(0, 0, false);

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(30, files.count());
        }
        assertFalse(Files.exists(dir.resolve("20200101-000000-old.log")));
        assertTrue(Files.exists(dir.resolve("20200101-000034-old.log")));
    }

    private static String readOnlyLog(Path logDir) throws IOException {
        try (Stream<Path> files = Files.list(logDir)) {
            List<Path> logs = files.toList();
            assertEquals(1, logs.size());
            return Files.readString(logs.get(0));
        }
    }
}

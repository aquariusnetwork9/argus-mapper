package tools.argus.uploader.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the coordinate cap is enforced inside the actual network call
 * itself (see CoordLimits javadoc: the scanner filtering these out is not
 * the only thing standing between a bad RegionFile and the network).
 */
class ArgusUploadClientTest {

    @AfterEach
    void restoreDefault() {
        CoordLimits.setLifted(false);
    }

    private static ArgusUploadClient unreachableClient() {
        ArgusConfig config = new ArgusConfig();
        config.apiBaseUrl = "http://127.0.0.1:1"; // nothing is listening here - if this got hit, the test would hang/timeout
        config.token = "x";
        config.layer = "y";
        return new ArgusUploadClient(config);
    }

    /** Sends {@code region} to a loopback server and returns the X-Region-Modified header it saw. */
    private static String headerSeenFor(RegionFile region) throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("X-Region-Modified"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            ArgusConfig config = new ArgusConfig();
            config.apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
            config.token = "x";
            config.layer = "y";
            assertTrue(new ArgusUploadClient(config).upload(region, "batch-1").success());
        } finally {
            server.stop(0);
        }
        return seen.get();
    }

    @Test
    void sendsTheFilesModifiedTimeInUtcEpochMillisAsAHeader(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("0_0.zip");
        Files.writeString(file, "x");
        RegionFile region = new RegionFile(file, "0_0.zip", 0, 0, "overworld", 1, 1_700_000_000_123L);

        assertEquals("1700000000123", headerSeenFor(region));
    }

    @Test
    void omitsTheHeaderWhenTheModifiedTimeIsUnknown(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("0_0.zip");
        Files.writeString(file, "x");
        RegionFile region = new RegionFile(file, "0_0.zip", 0, 0, "overworld", 1);

        assertNull(headerSeenFor(region));
    }

    @Test
    void refusesToUploadOutOfRangeCoordinatesWithoutMakingARequest() {
        ArgusUploadClient client = unreachableClient();

        RegionFile tooFarX = new RegionFile(Path.of("100000_0.zip"), "100000_0.zip", 100_000, 0, "overworld", 10);
        RegionFile tooFarZ = new RegionFile(Path.of("0_-100000.zip"), "0_-100000.zip", 0, -100_000, "overworld", 10);

        ArgusUploadClient.UploadResult resultX = client.upload(tooFarX, "batch-1");
        ArgusUploadClient.UploadResult resultZ = client.upload(tooFarZ, "batch-1");

        assertFalse(resultX.success());
        assertFalse(resultZ.success());
        assertTrue(resultX.ioError() != null && resultX.ioError().getMessage().contains("Refusing to upload"));
        assertEquals(-1, resultX.statusCode());
    }

    @Test
    void refusesJustPastTheDefaultRegionLimit() {
        RegionFile past = new RegionFile(Path.of("201_0.zip"), "201_0.zip", 201, 0, "overworld", 10);

        ArgusUploadClient.UploadResult result = unreachableClient().upload(past, "batch-1");

        assertFalse(result.success());
        assertTrue(result.ioError().getMessage().contains("Refusing to upload"));
    }

    @Test
    void whenTheLimitIsLiftedTheClientNoLongerRefusesOnDistance() {
        CoordLimits.setLifted(true);
        RegionFile far = new RegionFile(Path.of("5000_0.zip"), "5000_0.zip", 5000, 0, "overworld", 10);

        ArgusUploadClient.UploadResult result = unreachableClient().upload(far, "batch-1");

        assertFalse(result.success());
        assertFalse(result.ioError() != null && result.ioError().getMessage().contains("Refusing to upload"),
                "it should get as far as trying the (unreachable) network");
    }
}

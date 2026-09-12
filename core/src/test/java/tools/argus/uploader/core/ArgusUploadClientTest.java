package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the coordinate cap is enforced inside the actual network call
 * itself (see CoordLimits javadoc: the scanner filtering these out is not
 * the only thing standing between a bad RegionFile and the network).
 */
class ArgusUploadClientTest {

    @Test
    void refusesToUploadOutOfRangeCoordinatesWithoutMakingARequest() {
        ArgusConfig config = new ArgusConfig();
        config.apiBaseUrl = "http://127.0.0.1:1"; // nothing is listening here - if this got hit, the test would hang/timeout
        config.token = "x";
        config.layer = "y";
        ArgusUploadClient client = new ArgusUploadClient(config);

        RegionFile tooFarX = new RegionFile(Path.of("100000_0.zip"), "100000_0.zip", 100_000, 0, "overworld", 10);
        RegionFile tooFarZ = new RegionFile(Path.of("0_-100000.zip"), "0_-100000.zip", 0, -100_000, "overworld", 10);

        ArgusUploadClient.UploadResult resultX = client.upload(tooFarX, "batch-1");
        ArgusUploadClient.UploadResult resultZ = client.upload(tooFarZ, "batch-1");

        assertFalse(resultX.success());
        assertFalse(resultZ.success());
        assertTrue(resultX.ioError() != null && resultX.ioError().getMessage().contains("Refusing to upload"));
        assertEquals(-1, resultX.statusCode());
    }
}

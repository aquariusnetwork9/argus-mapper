package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XaeroScannerTest {

    @Test
    void findsAndClassifiesRegionFiles(@TempDir Path root) throws IOException {
        write(root.resolve("0_0.zip"));
        write(root.resolve("-3_5.zip"));
        write(root.resolve("DIM-1").resolve("1_1.zip"));
        write(root.resolve("DIM1").resolve("2_2.zip"));
        write(root.resolve("not-a-region.txt"));
        write(root.resolve("caves").resolve("-2147483648").resolve("9_9.zip"));

        XaeroScanner.ScanResult result = XaeroScanner.scan(root, false);

        assertEquals(4, result.regions().size(), "should find overworld x2, nether, end but not the non-region file or caves");
        assertEquals(1, result.rejectedCaves());
        assertEquals(0, result.rejectedOutOfRange());

        List<RegionFile> nether = result.regions().stream().filter(r -> r.dimension().equals("the_nether")).toList();
        assertEquals(1, nether.size());
        assertEquals(1, nether.get(0).regionX());
        assertEquals(1, nether.get(0).regionZ());
    }

    @Test
    void includesCavesWhenRequested(@TempDir Path root) throws IOException {
        write(root.resolve("caves").resolve("-2147483648").resolve("9_9.zip"));

        XaeroScanner.ScanResult withoutCaves = XaeroScanner.scan(root, false);
        XaeroScanner.ScanResult withCaves = XaeroScanner.scan(root, true);

        assertEquals(0, withoutCaves.regions().size());
        assertEquals(1, withCaves.regions().size());
    }

    @Test
    void rejectsOutOfRangeCoordinatesEntirely(@TempDir Path root) throws IOException {
        write(root.resolve("100000_0.zip"));
        write(root.resolve("0_-100000.zip"));
        write(root.resolve("99999_-99999.zip"));

        XaeroScanner.ScanResult result = XaeroScanner.scan(root, false);

        assertEquals(1, result.regions().size(), "only the in-range file should survive");
        assertEquals(99_999, result.regions().get(0).regionX());
        assertEquals(2, result.rejectedOutOfRange());
    }

    @Test
    void missingRootReturnsEmptyNotError() throws IOException {
        XaeroScanner.ScanResult result = XaeroScanner.scan(Path.of("does-not-exist-argus-test"), false);
        assertTrue(result.regions().isEmpty());
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "fake-zip-bytes");
    }
}

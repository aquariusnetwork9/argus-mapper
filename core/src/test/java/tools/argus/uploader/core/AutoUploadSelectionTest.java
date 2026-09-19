package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoUploadSelectionTest {

    private static RegionFile region(int x, long mtime) {
        String filename = x + "_0.zip";
        return new RegionFile(Path.of(filename), filename, x, 0, "overworld", 10, mtime);
    }

    @Test
    void onlySendsRegionsSavedSinceLiveWasTurnedOn(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile before = region(1, 500);
        RegionFile after = region(2, 1_500);

        assertEquals(List.of(after), AutoUploadSelection.select(List.of(before, after), manifest, 1_000));
    }

    @Test
    void skipsARegionThatIsAlreadyCurrent(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile current = region(1, 1_200);
        manifest.markUploaded(current);

        assertEquals(List.of(), AutoUploadSelection.select(List.of(current), manifest, 1_000));
    }

    @Test
    void resendsAnUploadedRegionOnceItChangesAgain(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        manifest.markUploaded(region(1, 1_200));
        RegionFile changed = region(1, 1_300);

        assertEquals(List.of(changed), AutoUploadSelection.select(List.of(changed), manifest, 1_000));
    }
}

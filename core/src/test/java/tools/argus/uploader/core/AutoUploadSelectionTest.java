package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoUploadSelectionTest {

    private static final long QUIET = AutoUploadSelection.QUIET_MILLIS;
    private static final long LIVE_SINCE = 1_000;
    private static final long NOW = LIVE_SINCE + 10 * QUIET;

    private static RegionFile region(int x, long mtime) {
        String filename = x + "_0.zip";
        return new RegionFile(Path.of(filename), filename, x, 0, "overworld", 10, mtime);
    }

    private static AutoUploadSelection.Selection select(List<RegionFile> regions, UploadManifest manifest) {
        return AutoUploadSelection.select(regions, manifest, LIVE_SINCE, NOW);
    }

    @Test
    void onlySendsRegionsSavedSinceLiveWasTurnedOn(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile before = region(1, 500);
        RegionFile after = region(2, 1_500);

        assertEquals(List.of(after), select(List.of(before, after), manifest).ready());
    }

    @Test
    void skipsARegionThatIsAlreadyCurrent(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile current = region(1, 1_200);
        manifest.markUploaded(current);

        assertEquals(new AutoUploadSelection.Selection(List.of(), 0), select(List.of(current), manifest));
    }

    @Test
    void resendsAnUploadedRegionOnceItChangesAgain(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        manifest.markUploaded(region(1, 1_200));
        RegionFile changed = region(1, 1_300);

        assertEquals(List.of(changed), select(List.of(changed), manifest).ready());
    }

    @Test
    void holdsARegionThatWasWrittenToRecently(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile settled = region(1, NOW - QUIET);
        RegionFile stillBeingMapped = region(2, NOW - QUIET + 1);

        AutoUploadSelection.Selection selection = select(List.of(settled, stillBeingMapped), manifest);

        assertEquals(List.of(settled), selection.ready());
        assertEquals(1, selection.held());
    }

    @Test
    void aHeldRegionIsNotCountedWhenItWouldNotHaveBeenSentAnyway(@TempDir Path dir) throws IOException {
        UploadManifest manifest = UploadManifest.load(dir.resolve("manifest.txt"));
        RegionFile uploaded = region(1, NOW - 1);
        manifest.markUploaded(uploaded);
        RegionFile beforeLive = region(2, LIVE_SINCE - 1);

        assertEquals(0, select(List.of(uploaded, beforeLive), manifest).held());
    }

    @Test
    void stillQuietRequiresAnUnchangedFileThatHasSettled(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("0_0.zip");
        Files.writeString(file, "x");
        Files.setLastModifiedTime(file, FileTime.fromMillis(NOW - QUIET));
        RegionFile scanned = new RegionFile(file, "0_0.zip", 0, 0, "overworld", 1, NOW - QUIET);

        assertTrue(AutoUploadSelection.isStillQuiet(scanned, NOW));

        Files.setLastModifiedTime(file, FileTime.fromMillis(NOW - 1_000));
        assertFalse(AutoUploadSelection.isStillQuiet(scanned, NOW), "rewritten since the scan");
    }

    @Test
    void stillQuietIsFalseForAFileThatVanished(@TempDir Path dir) {
        RegionFile gone = new RegionFile(dir.resolve("gone.zip"), "gone.zip", 0, 0, "overworld", 1, NOW - QUIET);

        assertFalse(AutoUploadSelection.isStillQuiet(gone, NOW));
    }
}

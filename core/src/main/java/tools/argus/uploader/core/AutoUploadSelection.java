package tools.argus.uploader.core;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Picks which eligible regions a live upload cycle sends. */
public final class AutoUploadSelection {

    /** How long a region file must go unwritten before live upload treats it as finished. */
    public static final long QUIET_MILLIS = 10 * 60_000L;

    private AutoUploadSelection() {
    }

    /**
     * @param ready regions to send now
     * @param held  regions that qualify but were written to too recently; a later cycle picks them up
     */
    public record Selection(List<RegionFile> ready, int held) {
    }

    /**
     * @param eligible regions already past the coordinate cap, nether gate and blackzones
     * @return those Xaero saved at or after {@code liveSinceMillis} that are new, or changed since
     *         their last upload, split by whether they have been quiet for {@link #QUIET_MILLIS}
     */
    public static Selection select(List<RegionFile> eligible, UploadManifest manifest, long liveSinceMillis,
                                   long nowMillis) {
        List<RegionFile> ready = new ArrayList<>();
        int held = 0;
        for (RegionFile region : eligible) {
            if (region.lastModifiedMillis() < liveSinceMillis || !manifest.needsUpload(region, true)) {
                continue;
            }
            if (isQuiet(region.lastModifiedMillis(), nowMillis)) {
                ready.add(region);
            } else {
                held++;
            }
        }
        return new Selection(ready, held);
    }

    /** Re-reads the file's modified time: it must be unchanged since the scan and still quiet. */
    public static boolean isStillQuiet(RegionFile region, long nowMillis) {
        try {
            long onDisk = Files.getLastModifiedTime(region.path()).toMillis();
            return onDisk == region.lastModifiedMillis() && isQuiet(onDisk, nowMillis);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isQuiet(long modifiedMillis, long nowMillis) {
        return nowMillis - modifiedMillis >= QUIET_MILLIS;
    }
}

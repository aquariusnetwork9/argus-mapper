package tools.argus.uploader.core;

import java.util.ArrayList;
import java.util.List;

/** Picks which eligible regions a live upload cycle sends. */
public final class AutoUploadSelection {

    private AutoUploadSelection() {
    }

    /**
     * @param eligible regions already past the coordinate cap, nether gate and blackzones
     * @return those Xaero saved at or after {@code liveSinceMillis} that are new, or changed since
     *         their last upload
     */
    public static List<RegionFile> select(List<RegionFile> eligible, UploadManifest manifest, long liveSinceMillis) {
        List<RegionFile> out = new ArrayList<>();
        for (RegionFile region : eligible) {
            if (region.lastModifiedMillis() >= liveSinceMillis && manifest.needsUpload(region, true)) {
                out.add(region);
            }
        }
        return out;
    }
}

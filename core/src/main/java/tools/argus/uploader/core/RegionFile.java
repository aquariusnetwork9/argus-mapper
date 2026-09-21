package tools.argus.uploader.core;

import java.nio.file.Path;

/**
 * @param dimension          one of "overworld", "the_nether", "theend"
 * @param lastModifiedMillis the file's modified time when it was scanned, 0 if unknown - captured at
 *                           scan time (not upload time) so a file Xaero rewrites mid-upload is
 *                           re-detected as changed on the next run instead of being recorded as
 *                           already current
 */
public record RegionFile(Path path, String filename, int regionX, int regionZ, String dimension, long sizeBytes,
                         long lastModifiedMillis) {

    public RegionFile(Path path, String filename, int regionX, int regionZ, String dimension, long sizeBytes) {
        this(path, filename, regionX, regionZ, dimension, sizeBytes, 0L);
    }

    public String manifestKey() {
        return dimension + "|" + filename;
    }
}

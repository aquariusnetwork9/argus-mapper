package tools.argus.uploader.core;

import java.nio.file.Path;

/**
 * @param dimension one of "overworld", "the_nether", "theend"
 */
public record RegionFile(Path path, String filename, int regionX, int regionZ, String dimension, long sizeBytes) {

    public String manifestKey() {
        return dimension + "|" + filename;
    }
}

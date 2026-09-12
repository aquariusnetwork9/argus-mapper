package tools.argus.uploader.core;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Classifies a region file's dimension from its path, using Xaero's World
 * Map folder naming convention. Dimension folders are named {@code DIM-1}
 * (nether) and {@code DIM1} (end); anything else is treated as overworld.
 * A {@code caves} branch is treated separately (see {@link #isCaves}).
 */
public final class DimensionMapper {

    private static final Pattern NETHER = Pattern.compile("(?i)(^|[^0-9])DIM-1([^0-9]|$)");
    private static final Pattern END = Pattern.compile("(?i)(^|[^0-9])DIM1([^0-9]|$)");
    private static final Pattern CAVES = Pattern.compile("(?i)^caves$");

    private DimensionMapper() {
    }

    public static boolean isCaves(Path relativePath) {
        for (int i = 0; i < relativePath.getNameCount(); i++) {
            if (CAVES.matcher(relativePath.getName(i).toString()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** @return "overworld", "the_nether", or "theend" */
    public static String classify(Path relativePath) {
        for (int i = 0; i < relativePath.getNameCount(); i++) {
            String segment = relativePath.getName(i).toString();
            if (segment.toLowerCase().contains("nether")) {
                return "the_nether";
            }
            if (segment.toLowerCase().contains("the_end") || segment.toLowerCase().contains("theend")) {
                return "theend";
            }
            if (NETHER.matcher(segment).find()) {
                return "the_nether";
            }
            if (END.matcher(segment).find()) {
                return "theend";
            }
        }
        return "overworld";
    }
}

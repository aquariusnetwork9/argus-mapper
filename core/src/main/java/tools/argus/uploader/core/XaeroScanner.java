package tools.argus.uploader.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class XaeroScanner {

    private static final Pattern REGION_FILE = Pattern.compile("^(-?\\d+)_(-?\\d+)\\.zip$");

    private XaeroScanner() {
    }

    /**
     * @param regions           in-range, non-caves-unless-requested region files
     * @param rejectedOutOfRange region files found but excluded because |x| or |z|
     *                           exceeded {@link CoordLimits#MAX_ABS_COORD} — see that
     *                           class for why this exists
     * @param rejectedCaves      cave-branch region files excluded because includeCaves was false
     */
    public record ScanResult(List<RegionFile> regions, int rejectedOutOfRange, int rejectedCaves) {
    }

    /**
     * Walks {@code root} recursively and returns every Xaero region zip
     * found, classified by dimension. Files under a {@code caves} branch are
     * skipped unless {@code includeCaves} is true. Files outside
     * {@link CoordLimits} are never included in the result — see that class.
     */
    public static ScanResult scan(Path root, boolean includeCaves) throws IOException {
        List<RegionFile> found = new ArrayList<>();
        int rejectedOutOfRange = 0;
        int rejectedCaves = 0;
        if (!Files.isDirectory(root)) {
            return new ScanResult(found, 0, 0);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String filename = path.getFileName().toString();
                Matcher m = REGION_FILE.matcher(filename);
                if (!m.matches()) {
                    continue;
                }
                Path relative = root.relativize(path);
                boolean caves = DimensionMapper.isCaves(relative);
                if (caves && !includeCaves) {
                    rejectedCaves++;
                    continue;
                }
                int x = Integer.parseInt(m.group(1));
                int z = Integer.parseInt(m.group(2));
                if (!CoordLimits.inRange(x, z)) {
                    rejectedOutOfRange++;
                    continue;
                }
                String dimension = DimensionMapper.classify(relative);
                long size = Files.size(path);
                found.add(new RegionFile(path, filename, x, z, dimension, size));
            }
        }
        return new ScanResult(found, rejectedOutOfRange, rejectedCaves);
    }
}

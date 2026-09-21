package tools.argus.uploader.core.automap;

import java.util.Collection;

/** How much of a straight flight line runs over regions that already have a map file. */
public final class CorridorOverlap {

    private static final int REGION_BLOCKS = 512;

    private CorridorOverlap() {
    }

    /**
     * @param cardinal see {@link CalibrationController}: 0 east, 1 south, 2 west, 3 north
     * @param regions  (regionX, regionZ) pairs that already exist
     * @return how many of them lie within a region of the line, out to {@code lengthBlocks}
     */
    public static int count(Collection<int[]> regions, double startX, double startZ, int cardinal, double lengthBlocks) {
        boolean alongX = cardinal % 2 == 0;
        int sign = cardinal < 2 ? 1 : -1;
        int lineRegion = Math.floorDiv((int) Math.floor(alongX ? startZ : startX), REGION_BLOCKS);
        double startAlong = alongX ? startX : startZ;
        double endAlong = startAlong + sign * lengthBlocks;
        int fromRegion = Math.floorDiv((int) Math.floor(Math.min(startAlong, endAlong)), REGION_BLOCKS);
        int toRegion = Math.floorDiv((int) Math.floor(Math.max(startAlong, endAlong)), REGION_BLOCKS);
        int count = 0;
        for (int[] region : regions) {
            int along = alongX ? region[0] : region[1];
            int across = alongX ? region[1] : region[0];
            if (Math.abs(across - lineRegion) <= 1 && along >= fromRegion && along <= toRegion) {
                count++;
            }
        }
        return count;
    }

    /** The cardinal whose line crosses the fewest existing regions; ties go to {@code preferred}. */
    public static int leastMapped(Collection<int[]> regions, double startX, double startZ, int preferred,
                                  double lengthBlocks) {
        int best = preferred;
        int bestCount = count(regions, startX, startZ, preferred, lengthBlocks);
        for (int cardinal = 0; cardinal < 4; cardinal++) {
            int c = count(regions, startX, startZ, cardinal, lengthBlocks);
            if (c < bestCount) {
                best = cardinal;
                bestCount = c;
            }
        }
        return best;
    }
}

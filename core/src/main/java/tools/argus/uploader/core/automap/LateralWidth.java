package tools.argus.uploader.core.automap;

/** How many chunks either side of the flight line are already covered, a little way behind the player. */
public final class LateralWidth {

    private LateralWidth() {
    }

    /** @return the smaller of the two sides, in chunks beyond the line's own chunk (at most {@code maxChunks}) */
    public static int measure(double x, double z, double headingYawDegrees, int lagChunks, int maxChunks,
                              ChunkProbe probe) {
        double radians = Math.toRadians(headingYawDegrees);
        double headingX = -Math.sin(radians);
        double headingZ = Math.cos(radians);
        double rowX = x - headingX * lagChunks * 16;
        double rowZ = z - headingZ * lagChunks * 16;
        return Math.min(side(rowX, rowZ, headingZ, -headingX, maxChunks, probe),
                side(rowX, rowZ, -headingZ, headingX, maxChunks, probe));
    }

    private static int side(double rowX, double rowZ, double stepX, double stepZ, int maxChunks, ChunkProbe probe) {
        int count = 0;
        for (int k = 1; k <= maxChunks; k++) {
            int chunkX = Math.floorDiv((int) Math.floor(rowX + stepX * 16 * k), 16);
            int chunkZ = Math.floorDiv((int) Math.floor(rowZ + stepZ * 16 * k), 16);
            if (!probe.test(chunkX, chunkZ)) {
                break;
            }
            count++;
        }
        return count;
    }
}

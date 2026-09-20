package tools.argus.uploader.core.automap;

/**
 * How wide a strip of chunks Xaero ends up mapping along a flight line, by speed. Measured on 6b6t:
 * about 14 chunks across at roughly 25 blocks/s, dropping to 8-10 at the faster speeds. Lanes are
 * planned for the top speed so they still meet at the speed the flight will mostly run at.
 */
public final class LaneWidthModel {

    private static final double SLOW_BLOCKS_PER_TICK = 25.0 / 20.0;
    private static final double SLOW_WIDTH_CHUNKS = 14;
    private static final double FAST_BLOCKS_PER_TICK = 5.99;
    private static final double FAST_WIDTH_CHUNKS = 8;

    private LaneWidthModel() {
    }

    public static double widthChunks(double blocksPerTick) {
        if (blocksPerTick <= SLOW_BLOCKS_PER_TICK) {
            return SLOW_WIDTH_CHUNKS;
        }
        double t = Math.min(1, (blocksPerTick - SLOW_BLOCKS_PER_TICK) / (FAST_BLOCKS_PER_TICK - SLOW_BLOCKS_PER_TICK));
        return SLOW_WIDTH_CHUNKS + t * (FAST_WIDTH_CHUNKS - SLOW_WIDTH_CHUNKS);
    }

    /** Chunks either side of the lane's own chunk that can be counted on at that speed. */
    public static int halfWidthChunks(double blocksPerTick) {
        return Math.max(2, (int) Math.floor((widthChunks(blocksPerTick) - 1) / 2));
    }
}

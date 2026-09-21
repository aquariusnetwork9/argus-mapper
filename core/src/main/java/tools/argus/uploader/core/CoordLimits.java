package tools.argus.uploader.core;

/**
 * Hard ceiling on how far from the world origin an upload can reach: |regionX| and |regionZ| may be
 * at most {@link #MAX_ABS_REGION}. These are Xaero region coordinates (the numbers in a filename
 * like {@code 3_-1.zip}), 512 blocks each, so the default is about +/-102,400 blocks on each axis,
 * in every dimension. An earlier version capped the region number at 99,999 as though it were a
 * block coordinate, which is over 51 million blocks and so limited nothing.
 *
 * <p>Enforced in two independent places on purpose ({@link XaeroScanner} never returns an
 * out-of-range file, and {@link ArgusUploadClient} refuses to send one even if somehow handed one)
 * so a bug in one layer can't alone defeat the limit. Both read the same {@link #isLifted} switch,
 * which is in-memory only - it is never saved, so it is back to enforcing after any restart.
 */
public final class CoordLimits {

    public static final int MAX_ABS_REGION = 200;

    private static volatile boolean lifted;

    private CoordLimits() {
    }

    public static boolean isLifted() {
        return lifted;
    }

    /** Only ever called from an explicit, confirmed, per-session opt-in; never from config. */
    public static void setLifted(boolean value) {
        lifted = value;
    }

    public static boolean inRange(int coord) {
        return lifted || (coord >= -MAX_ABS_REGION && coord <= MAX_ABS_REGION);
    }

    public static boolean inRange(int x, int z) {
        return inRange(x) && inRange(z);
    }

    /** The fixed default area, whether or not whole-map upload has lifted the limit. */
    public static boolean withinDefaultLimit(int x, int z) {
        return x >= -MAX_ABS_REGION && x <= MAX_ABS_REGION && z >= -MAX_ABS_REGION && z <= MAX_ABS_REGION;
    }
}

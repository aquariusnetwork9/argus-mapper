package tools.argus.uploader.core;

/**
 * Hard ceiling on region coordinates: magnitude must fit in 5 digits, i.e.
 * the map this mod can ever touch is bounded to +/-99,999 (~100k) on each
 * axis, in every dimension. Enforced in two independent places on purpose
 * ({@link XaeroScanner} never returns an out-of-range file, and
 * {@link ArgusUploadClient} refuses to send one even if somehow handed one)
 * so a bug in one layer can't alone defeat the limit.
 */
public final class CoordLimits {

    public static final int MAX_ABS_COORD = 99_999;

    private CoordLimits() {
    }

    public static boolean inRange(int coord) {
        return coord >= -MAX_ABS_COORD && coord <= MAX_ABS_COORD;
    }

    public static boolean inRange(int x, int z) {
        return inRange(x) && inRange(z);
    }
}

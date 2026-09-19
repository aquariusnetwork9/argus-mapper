package tools.argus.uploader.core;

import java.util.Optional;

/**
 * Flags a teleport from a stream of once-per-tick player positions: a move of more than
 * {@link #THRESHOLD_BLOCKS} in a single step, or any change of dimension. Purely local - positions
 * are only compared with the previous one and never stored beyond it or sent anywhere.
 */
public final class TeleportDetector {

    public static final double THRESHOLD_BLOCKS = 128.0;

    /** @param blocksMoved straight-line distance, 0 when the dimension changed (the two
     *                     coordinate spaces can't be compared) */
    public record Jump(String dimension, double x, double z, double blocksMoved, boolean dimensionChanged) {
    }

    private boolean hasLast;
    private String lastDimension;
    private double lastX;
    private double lastY;
    private double lastZ;

    public Optional<Jump> observe(String dimension, double x, double y, double z) {
        Optional<Jump> jump = Optional.empty();
        if (hasLast) {
            if (!dimension.equals(lastDimension)) {
                jump = Optional.of(new Jump(dimension, x, z, 0, true));
            } else {
                double dx = x - lastX;
                double dy = y - lastY;
                double dz = z - lastZ;
                double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (moved > THRESHOLD_BLOCKS) {
                    jump = Optional.of(new Jump(dimension, x, z, moved, false));
                }
            }
        }
        hasLast = true;
        lastDimension = dimension;
        lastX = x;
        lastY = y;
        lastZ = z;
        return jump;
    }

    public void reset() {
        hasLast = false;
        lastDimension = null;
    }
}

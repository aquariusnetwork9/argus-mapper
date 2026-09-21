package tools.argus.uploader.core.automap;

import java.util.Arrays;

/**
 * How wide a strip of chunks Xaero ends up mapping along a flight line, by speed, from a table of
 * measured points (blocks per tick : chunks across) joined by straight lines. The default is what
 * was measured on 6b6t: about 14 across at 25 blocks/s, 8-10 a little faster, 6 at 40, 4 at 60 and
 * 2 at 92 (the last point is assumed to hold up to 120). Flying faster means narrower lanes and so more of them, so the quickest way
 * over an area is not the fastest speed - with these numbers it is about 25 blocks/s.
 */
public final class LaneWidthModel {

    public static final String DEFAULT_TABLE = "1.25:14,1.6:9,2.0:6,3.0:4,4.6:2,5.99:2";

    private static final double SEARCH_STEP = 0.05;

    private final double[] speeds;
    private final double[] widths;

    private LaneWidthModel(double[] speeds, double[] widths) {
        this.speeds = speeds;
        this.widths = widths;
    }

    public static LaneWidthModel defaults() {
        return parse(DEFAULT_TABLE);
    }

    /** {@code "speed:width,speed:width,..."}; anything unusable falls back to {@link #DEFAULT_TABLE}. */
    public static LaneWidthModel parse(String table) {
        LaneWidthModel parsed = tryParse(table);
        return parsed != null ? parsed : tryParse(DEFAULT_TABLE);
    }

    private static LaneWidthModel tryParse(String table) {
        if (table == null) {
            return null;
        }
        String[] entries = table.split(",");
        if (entries.length < 2) {
            return null;
        }
        double[][] points = new double[entries.length][];
        try {
            for (int i = 0; i < entries.length; i++) {
                String[] pair = entries[i].trim().split(":");
                if (pair.length != 2) {
                    return null;
                }
                double speed = Double.parseDouble(pair[0].trim());
                double width = Double.parseDouble(pair[1].trim());
                if (!(speed > 0) || !(width >= 1)) {
                    return null;
                }
                points[i] = new double[]{speed, width};
            }
        } catch (NumberFormatException e) {
            return null;
        }
        Arrays.sort(points, (a, b) -> Double.compare(a[0], b[0]));
        double[] speeds = new double[points.length];
        double[] widths = new double[points.length];
        for (int i = 0; i < points.length; i++) {
            if (i > 0 && points[i][0] == points[i - 1][0]) {
                return null;
            }
            speeds[i] = points[i][0];
            widths[i] = points[i][1];
        }
        return new LaneWidthModel(speeds, widths);
    }

    public double widthChunks(double blocksPerTick) {
        if (blocksPerTick <= speeds[0]) {
            return widths[0];
        }
        for (int i = 1; i < speeds.length; i++) {
            if (blocksPerTick <= speeds[i]) {
                double t = (blocksPerTick - speeds[i - 1]) / (speeds[i] - speeds[i - 1]);
                return widths[i - 1] + t * (widths[i] - widths[i - 1]);
            }
        }
        return widths[widths.length - 1];
    }

    /** Chunks either side of the lane's own chunk that can be counted on at that speed. */
    public int halfWidthChunks(double blocksPerTick) {
        return Math.max(1, (int) Math.floor((widthChunks(blocksPerTick) - 1) / 2));
    }

    /** The speed in [min, max] that covers the most new ground: speed times lane spacing. */
    public double bestSpeed(double min, double max) {
        double best = min;
        double bestScore = -1;
        for (double s = min; s <= max + 1e-9; s += SEARCH_STEP) {
            double score = s * (widthChunks(s) - 1);
            if (score > bestScore + 1e-9) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }
}

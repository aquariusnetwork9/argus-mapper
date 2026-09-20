package tools.argus.uploader.core.automap;

import java.util.HashMap;
import java.util.Map;

/**
 * Records, along a straight flight line, which chunks either side of it were ever loaded and which
 * Xaero had recorded a few and a good many chunks after the player passed.
 */
public final class CorridorRecorder {

    public static final int ROWS = 16;
    public static final int LAG_NEAR = 6;
    public static final int LAG_FAR = 20;
    private static final int WIDTH = ROWS * 2 + 1;
    private static final double THRESHOLD = 0.9;

    /** Chunks across the line that were covered in at least 90% of the recorded cells, contiguously around it. */
    public record Widths(int loaded, int written6, int written20) {
    }

    public record StageResult(int cells, Widths widths, double[] loadedByRow, double[] written6ByRow,
                              double[] written20ByRow) {
    }

    private static final class Cell {
        int stage = -1;
        boolean steady;
        boolean done6;
        final boolean[] loaded = new boolean[WIDTH];
        final boolean[] written6 = new boolean[WIDTH];
        final boolean[] written20 = new boolean[WIDTH];
    }

    private static final class Totals {
        int cells;
        final long[] denominator = new long[WIDTH];
        final long[] loaded = new long[WIDTH];
        final long[] written6 = new long[WIDTH];
        final long[] written20 = new long[WIDTH];
    }

    private final boolean alongX;
    private final int direction;
    private final int lineRow;
    private final int viewRadius;
    private final Map<Integer, Cell> cells = new HashMap<>();
    private final Totals[] totals;
    private boolean anyFinished;
    private int finishedAlong;

    public CorridorRecorder(boolean alongX, int direction, int lineRow, int viewRadius, int stageCount) {
        this.alongX = alongX;
        this.direction = direction;
        this.lineRow = lineRow;
        this.viewRadius = viewRadius;
        this.totals = new Totals[stageCount];
        for (int i = 0; i < stageCount; i++) {
            totals[i] = new Totals();
        }
    }

    public void observe(int playerAlong, int stage, boolean settling, ChunkProbe loaded, ChunkProbe written) {
        for (int behind = -viewRadius; behind <= LAG_FAR; behind++) {
            int along = playerAlong - direction * behind;
            if (anyFinished && direction * (along - finishedAlong) <= 0) {
                continue;
            }
            Cell cell = cells.computeIfAbsent(along, a -> new Cell());
            if (behind >= 0 && cell.stage < 0) {
                cell.stage = stage;
                cell.steady = !settling;
            }
            for (int row = -ROWS; row <= ROWS; row++) {
                int index = row + ROWS;
                int chunkX = alongX ? along : lineRow + row;
                int chunkZ = alongX ? lineRow + row : along;
                if (!cell.loaded[index] && loaded.test(chunkX, chunkZ)) {
                    cell.loaded[index] = true;
                }
                if (behind >= LAG_NEAR && cell.stage >= 0 && !cell.done6) {
                    cell.written6[index] = written.test(chunkX, chunkZ);
                }
                if (behind >= LAG_FAR && cell.stage >= 0) {
                    cell.written20[index] = written.test(chunkX, chunkZ);
                }
            }
            if (behind >= LAG_NEAR && cell.stage >= 0) {
                cell.done6 = true;
            }
            if (behind >= LAG_FAR && cell.stage >= 0) {
                cells.remove(along);
                anyFinished = true;
                finishedAlong = along;
                if (cell.steady) {
                    accumulate(totals[cell.stage], cell);
                }
            }
        }
    }

    private static void accumulate(Totals total, Cell cell) {
        total.cells++;
        for (int i = 0; i < WIDTH; i++) {
            total.denominator[i]++;
            total.loaded[i] += cell.loaded[i] ? 1 : 0;
            total.written6[i] += cell.written6[i] ? 1 : 0;
            total.written20[i] += cell.written20[i] ? 1 : 0;
        }
    }

    public StageResult result(int stage) {
        Totals total = totals[stage];
        double[] loaded = fractions(total.loaded, total.denominator);
        double[] written6 = fractions(total.written6, total.denominator);
        double[] written20 = fractions(total.written20, total.denominator);
        return new StageResult(total.cells, new Widths(width(loaded), width(written6), width(written20)),
                loaded, written6, written20);
    }

    private static double[] fractions(long[] counts, long[] denominators) {
        double[] out = new double[WIDTH];
        for (int i = 0; i < WIDTH; i++) {
            out[i] = denominators[i] == 0 ? Double.NaN : (double) counts[i] / denominators[i];
        }
        return out;
    }

    private static int width(double[] byRow) {
        if (!(byRow[ROWS] >= THRESHOLD)) {
            return 0;
        }
        int left = 0;
        while (ROWS - left - 1 >= 0 && byRow[ROWS - left - 1] >= THRESHOLD) {
            left++;
        }
        int right = 0;
        while (ROWS + right + 1 < WIDTH && byRow[ROWS + right + 1] >= THRESHOLD) {
            right++;
        }
        return left + right + 1;
    }
}

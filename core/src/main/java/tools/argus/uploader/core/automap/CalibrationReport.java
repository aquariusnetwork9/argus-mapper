package tools.argus.uploader.core.automap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns a finished (or stopped) calibration into text: a readable summary and a per-row table. */
public final class CalibrationReport {

    private static final double BLOCKS_PER_SECOND = 20.0;

    private CalibrationReport() {
    }

    public static String summary(CalibrationController run, List<String> environment) {
        StringBuilder out = new StringBuilder("ARGUS Mapper auto-map calibration\n");
        for (String line : environment) {
            out.append(line).append('\n');
        }
        out.append("heading: ").append(run.heading()).append(", along the ")
                .append(run.alongX() ? "z" : "x").append(" chunk row ").append(run.lineRow()).append('\n');
        out.append("outcome: ").append(run.state()).append(run.abortReason().isEmpty() ? "" : " (" + run.abortReason() + ")")
                .append(", ").append(run.elapsedMillis() / 1000).append(" s flown\n\n");
        out.append("Chunks across the flight line, in at least 90% of the measured cells:\n");
        out.append(String.format(Locale.ROOT, "%5s %8s %8s %9s %9s %9s %10s %6s %5s%n",
                "stage", "set b/t", "meas b/t", "b/s", "loaded", "mapped@6", "mapped@20", "cells", "rb"));

        List<String> table = new ArrayList<>();
        for (int stage = 0; stage < run.schedule().stages(); stage++) {
            CorridorRecorder.StageResult result = run.result(stage);
            double measured = run.measuredSpeed(stage);
            out.append(String.format(Locale.ROOT, "%5d %8.2f %8s %9s %9d %9d %10d %6d %5d%n", stage + 1,
                    run.schedule().speed(stage), num(measured), Double.isNaN(measured) ? "-" : num(measured * BLOCKS_PER_SECOND),
                    result.widths().loaded(), result.widths().written6(), result.widths().written20(),
                    result.cells(), run.rubberbands(stage)));
            if (result.cells() >= 10 && !Double.isNaN(measured)) {
                table.add(String.format(Locale.ROOT, "%.2f:%d", measured, Math.max(1, result.widths().written20())));
            }
        }
        out.append('\n');
        if (table.size() >= 2) {
            String joined = String.join(",", table);
            LaneWidthModel model = LaneWidthModel.parse(joined);
            out.append("Suggested (from mapped@20 at the measured speeds):\nautoMapWidthTable=").append(joined).append('\n');
            out.append(String.format(Locale.ROOT, "Best speed by that table: %.2f blocks/tick (%.0f blocks/s)%n",
                    model.bestSpeed(1.0, 5.99), model.bestSpeed(1.0, 5.99) * BLOCKS_PER_SECOND));
        } else {
            out.append("Not enough completed stages to suggest a width table.\n");
        }
        return out.toString();
    }

    /** One line per stage and row: how often that row was loaded / mapped, so a strip's shape can be read. */
    public static String rowsCsv(CalibrationController run) {
        StringBuilder out = new StringBuilder("stage,set_speed,measured_speed,row,loaded,mapped_lag6,mapped_lag20\n");
        for (int stage = 0; stage < run.schedule().stages(); stage++) {
            CorridorRecorder.StageResult result = run.result(stage);
            if (result.cells() == 0) {
                continue;
            }
            for (int row = -CorridorRecorder.ROWS; row <= CorridorRecorder.ROWS; row++) {
                int index = row + CorridorRecorder.ROWS;
                out.append(stage + 1).append(',').append(num(run.schedule().speed(stage))).append(',')
                        .append(num(run.measuredSpeed(stage))).append(',').append(row).append(',')
                        .append(num(result.loadedByRow()[index])).append(',')
                        .append(num(result.written6ByRow()[index])).append(',')
                        .append(num(result.written20ByRow()[index])).append('\n');
            }
        }
        return out.toString();
    }

    private static String num(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.2f", value);
    }
}

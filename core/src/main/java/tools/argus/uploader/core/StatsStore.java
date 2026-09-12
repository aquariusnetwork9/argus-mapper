package tools.argus.uploader.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Small persisted counters for Discord leaderboard reporting. Distance is
 * accumulated in memory by the caller and flushed here periodically, not on
 * every update - see fabric-common's PlayerDistanceTracker.
 */
public final class StatsStore {

    private final Path file;
    public double totalDistanceBlocks;

    private StatsStore(Path file) {
        this.file = file;
    }

    /** An in-memory-only store - used as a safe fallback if loading the real one fails. */
    public static StatsStore empty() {
        return new StatsStore(null);
    }

    public static StatsStore load(Path file) throws IOException {
        StatsStore s = new StatsStore(file);
        if (Files.exists(file)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
            try {
                s.totalDistanceBlocks = Double.parseDouble(p.getProperty("totalDistanceBlocks", "0"));
            } catch (NumberFormatException ignored) {
                s.totalDistanceBlocks = 0;
            }
        }
        return s;
    }

    public synchronized void addDistance(double blocks) throws IOException {
        totalDistanceBlocks += blocks;
        save();
    }

    public void save() throws IOException {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("totalDistanceBlocks", String.valueOf(totalDistanceBlocks));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "ARGUS Mapper contributor stats (for Discord leaderboard reporting).");
        }
    }
}

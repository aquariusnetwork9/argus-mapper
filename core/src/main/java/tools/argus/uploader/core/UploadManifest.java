package tools.argus.uploader.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tracks which (dimension, filename) region files have already been uploaded, across runs. */
public final class UploadManifest {

    private final Path file;
    private final Set<String> uploaded = new HashSet<>();

    private UploadManifest(Path file) {
        this.file = file;
    }

    public static UploadManifest load(Path file) throws IOException {
        UploadManifest m = new UploadManifest(file);
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    m.uploaded.add(line.trim());
                }
            }
        } else if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        return m;
    }

    public boolean isUploaded(RegionFile region) {
        return uploaded.contains(region.manifestKey());
    }

    /** Marks uploaded and appends to disk immediately, so a crash mid-run doesn't lose progress. */
    public synchronized void markUploaded(RegionFile region) throws IOException {
        String key = region.manifestKey();
        if (!uploaded.add(key)) {
            return;
        }
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(key);
            w.newLine();
        }
    }

    public int size() {
        return uploaded.size();
    }

    public List<String> snapshot() {
        return List.copyOf(uploaded);
    }
}

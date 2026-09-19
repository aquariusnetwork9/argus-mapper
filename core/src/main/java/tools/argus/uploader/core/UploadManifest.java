package tools.argus.uploader.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks which (dimension, filename) region files have already been uploaded, across runs, and the
 * file's modified time when each was (see {@link #isChangedSinceUpload}). Append-only on disk, one
 * line per record - {@code dimension|filename} (an entry written before modified times were
 * tracked) or {@code dimension|filename<TAB>mtimeMillis}; on load the last line for a key wins.
 */
public final class UploadManifest {

    private final Path file;
    // Value is the modified time recorded at upload, 0 for an entry with none (legacy).
    private final Map<String, Long> uploaded = new HashMap<>();

    private UploadManifest(Path file) {
        this.file = file;
    }

    public static UploadManifest load(Path file) throws IOException {
        UploadManifest m = new UploadManifest(file);
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    m.parseLine(line);
                }
            }
        } else if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        return m;
    }

    private void parseLine(String line) {
        int tab = line.indexOf('\t');
        if (tab < 0) {
            uploaded.merge(line.trim(), 0L, Math::max);
            return;
        }
        long mtime;
        try {
            mtime = Long.parseLong(line.substring(tab + 1).trim());
        } catch (NumberFormatException e) {
            mtime = 0L;
        }
        uploaded.put(line.substring(0, tab).trim(), Math.max(0L, mtime));
    }

    public boolean isUploaded(RegionFile region) {
        return uploaded.containsKey(region.manifestKey());
    }

    /**
     * True if this region was uploaded before and its file is now newer than it was then. Always
     * false for a region never uploaded, for one whose upload predates modified-time tracking
     * (nothing to compare against - see {@link #adoptBaselines}), and for a file whose modified
     * time is unknown. Strictly newer, not merely different: a restored older backup shouldn't
     * count as an update.
     */
    public boolean isChangedSinceUpload(RegionFile region) {
        Long recorded = uploaded.get(region.manifestKey());
        return recorded != null && recorded > 0 && region.lastModifiedMillis() > recorded;
    }

    /** Whether an upload run should send this region: never uploaded, or - when
     *  {@code reuploadChanged} - uploaded but changed since (see {@link #isChangedSinceUpload}). */
    public boolean needsUpload(RegionFile region, boolean reuploadChanged) {
        return !isUploaded(region) || (reuploadChanged && isChangedSinceUpload(region));
    }

    /** Marks uploaded and appends to disk immediately, so a crash mid-run doesn't lose progress. */
    public synchronized void markUploaded(RegionFile region) throws IOException {
        String key = region.manifestKey();
        long mtime = Math.max(0L, region.lastModifiedMillis());
        Long previous = uploaded.get(key);
        if (previous != null && (previous == mtime || mtime == 0)) {
            return;
        }
        uploaded.put(key, mtime);
        append(List.of(line(key, mtime)));
    }

    /**
     * Gives every already-uploaded region that has no recorded modified time (uploaded by a version
     * before this was tracked) its current file's modified time as a baseline, so a later change is
     * detectable. The true upload-time state is unrecoverable, so an edit made between the original
     * upload and this call is not detected - only edits after it.
     */
    public synchronized void adoptBaselines(List<RegionFile> regions) throws IOException {
        List<String> lines = new ArrayList<>();
        for (RegionFile region : regions) {
            String key = region.manifestKey();
            Long recorded = uploaded.get(key);
            if (recorded != null && recorded == 0 && region.lastModifiedMillis() > 0) {
                uploaded.put(key, region.lastModifiedMillis());
                lines.add(line(key, region.lastModifiedMillis()));
            }
        }
        if (!lines.isEmpty()) {
            append(lines);
        }
    }

    private static String line(String key, long mtime) {
        return mtime > 0 ? key + '\t' + mtime : key;
    }

    private void append(List<String> lines) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (String line : lines) {
                w.write(line);
                w.newLine();
            }
        }
    }

    public int size() {
        return uploaded.size();
    }

    public List<String> snapshot() {
        return List.copyOf(uploaded.keySet());
    }
}

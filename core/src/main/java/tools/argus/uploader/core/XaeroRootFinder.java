package tools.argus.uploader.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds candidate Xaero World Map root folders for the current world.
 *
 * <p>Xaero sanitizes server addresses / world names when building these
 * folder names, and the exact rule isn't public and has changed across
 * versions, so this deliberately does a fuzzy (case-insensitive
 * "contains") match against a caller-supplied token (server address or
 * world folder name) rather than trying to reproduce the exact naming
 * scheme. Callers should surface the result list to the user (see
 * {@code /argus scan}) so a wrong or ambiguous match is caught before
 * spending upload requests on it.
 */
public final class XaeroRootFinder {

    private static final String[] NEW_LAYOUT_DIRS = {"xaero/world-map"};
    private static final String[] OLD_LAYOUT_DIRS = {"XaeroWorldMap"};

    private XaeroRootFinder() {
    }

    public static List<Path> findCandidates(Path gameDir, String worldToken) throws IOException {
        List<Path> candidates = new ArrayList<>();
        String needle = worldToken == null ? "" : worldToken.toLowerCase();
        for (String dir : NEW_LAYOUT_DIRS) {
            collectMatches(gameDir.resolve(dir), needle, candidates);
        }
        for (String dir : OLD_LAYOUT_DIRS) {
            collectMatches(gameDir.resolve(dir), needle, candidates);
        }
        return candidates;
    }

    private static void collectMatches(Path parent, String needle, List<Path> out) throws IOException {
        if (!Files.isDirectory(parent)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                if (needle.isEmpty() || child.getFileName().toString().toLowerCase().contains(needle)) {
                    out.add(child);
                }
            }
        }
    }
}

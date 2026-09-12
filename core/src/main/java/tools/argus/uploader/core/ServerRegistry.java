package tools.argus.uploader.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * A small, hand-editable (or /argus server add-able) registry mapping known
 * servers to the ARGUS layer their region data belongs to, so one mod build
 * can grow to cover more than one anarchy server over time. Stored flat as
 * {@code server.<id>.layer=<layer>} + {@code server.<id>.match=<comma,separated,substrings>}.
 */
public final class ServerRegistry {

    private final Path file;
    private final Map<String, ServerProfile> profiles = new LinkedHashMap<>();

    private ServerRegistry(Path file) {
        this.file = file;
    }

    /** An empty, non-persisting registry - used as a safe fallback if loading the real one fails. */
    public static ServerRegistry empty() {
        return new ServerRegistry(null);
    }

    public static ServerRegistry load(Path file) throws IOException {
        ServerRegistry registry = new ServerRegistry(file);
        if (Files.exists(file)) {
            registry.readFrom(file);
        }
        if (registry.profiles.isEmpty()) {
            registry.seedDefaults();
            registry.save();
        }
        return registry;
    }

    private void readFrom(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        Map<String, String> layers = new TreeMap<>();
        Map<String, String> matches = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("server.") && key.endsWith(".layer")) {
                layers.put(idFromKey(key, ".layer"), p.getProperty(key));
            } else if (key.startsWith("server.") && key.endsWith(".match")) {
                matches.put(idFromKey(key, ".match"), p.getProperty(key));
            }
        }
        for (Map.Entry<String, String> entry : layers.entrySet()) {
            String id = entry.getKey();
            String layer = entry.getValue();
            if (layer == null || layer.isBlank()) {
                continue;
            }
            profiles.put(id, new ServerProfile(id, layer, splitCsv(matches.getOrDefault(id, ""))));
        }
    }

    private static String idFromKey(String key, String suffix) {
        return key.substring("server.".length(), key.length() - suffix.length());
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Seeded from the ARGUS API example this mod was built against; add more with /argus server add. */
    private void seedDefaults() {
        profiles.put("6b6t", new ServerProfile("6b6t", "shallowplague", List.of("6b6t")));
    }

    public void save() throws IOException {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        for (ServerProfile profile : profiles.values()) {
            p.setProperty("server." + profile.name() + ".layer", profile.layer());
            p.setProperty("server." + profile.name() + ".match", String.join(",", profile.addressMatches()));
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "ARGUS known-server registry. server.<id>.layer + server.<id>.match "
                    + "(comma-separated, case-insensitive address substrings). Edit by hand or via /argus server add.");
        }
    }

    public List<ServerProfile> all() {
        return List.copyOf(profiles.values());
    }

    public Optional<ServerProfile> byName(String name) {
        return Optional.ofNullable(profiles.get(name));
    }

    /** All profiles whose match list contains a substring of {@code address}; empty if none, >1 if ambiguous. */
    public List<ServerProfile> matching(String address) {
        List<ServerProfile> out = new ArrayList<>();
        for (ServerProfile p : profiles.values()) {
            if (p.matches(address)) {
                out.add(p);
            }
        }
        return out;
    }

    public void addOrReplace(ServerProfile profile) throws IOException {
        profiles.put(profile.name(), profile);
        save();
    }
}

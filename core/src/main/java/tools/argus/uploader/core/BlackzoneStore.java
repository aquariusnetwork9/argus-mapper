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
 * Local-only store of {@link BlackZone}s. Never touches the network, never appears in any
 * upload payload, manifest, or Discord report - only the resulting exclusion counts do (see
 * {@link UploadRunner}). Stored flat, same shape as {@link ServerRegistry}:
 * {@code blackzone.<id>.dimension/.layer/.minX/.minZ/.maxX/.maxZ/.label}.
 */
public final class BlackzoneStore {

    private final Path file;
    private final Map<String, BlackZone> zones = new LinkedHashMap<>();

    private BlackzoneStore(Path file) {
        this.file = file;
    }

    /** An empty, non-persisting store - used as a safe fallback if loading the real one fails. */
    public static BlackzoneStore empty() {
        return new BlackzoneStore(null);
    }

    public static BlackzoneStore load(Path file) throws IOException {
        BlackzoneStore store = new BlackzoneStore(file);
        if (Files.exists(file)) {
            store.readFrom(file);
        }
        return store;
    }

    private void readFrom(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        Map<String, Map<String, String>> byId = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("blackzone.")) {
                continue;
            }
            int lastDot = key.lastIndexOf('.');
            if (lastDot <= "blackzone.".length()) {
                continue;
            }
            String id = key.substring("blackzone.".length(), lastDot);
            String field = key.substring(lastDot + 1);
            byId.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(field, p.getProperty(key));
        }
        for (Map.Entry<String, Map<String, String>> entry : byId.entrySet()) {
            BlackZone zone = parse(entry.getKey(), entry.getValue());
            if (zone != null) {
                zones.put(zone.id(), zone);
            }
        }
    }

    private static BlackZone parse(String id, Map<String, String> fields) {
        String dimension = fields.get("dimension");
        String layer = fields.get("layer");
        if (dimension == null || dimension.isBlank() || layer == null || layer.isBlank()) {
            return null;
        }
        try {
            RegionBounds bounds = new RegionBounds(
                    Integer.parseInt(fields.get("minX")), Integer.parseInt(fields.get("minZ")),
                    Integer.parseInt(fields.get("maxX")), Integer.parseInt(fields.get("maxZ")));
            String label = fields.getOrDefault("label", "");
            return new BlackZone(id, dimension, layer, bounds, label);
        } catch (NullPointerException | IllegalArgumentException e) {
            return null;
        }
    }

    public void save() throws IOException {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        for (BlackZone zone : zones.values()) {
            String prefix = "blackzone." + zone.id() + ".";
            p.setProperty(prefix + "dimension", zone.dimension());
            p.setProperty(prefix + "layer", zone.layer());
            p.setProperty(prefix + "minX", Integer.toString(zone.bounds().minRegionX()));
            p.setProperty(prefix + "minZ", Integer.toString(zone.bounds().minRegionZ()));
            p.setProperty(prefix + "maxX", Integer.toString(zone.bounds().maxRegionX()));
            p.setProperty(prefix + "maxZ", Integer.toString(zone.bounds().maxRegionZ()));
            p.setProperty(prefix + "label", zone.label());
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "ARGUS blackzones - areas this device will never upload. Local only; "
                    + "never sent anywhere. Edit by hand or via /argus blackzone add/remove.");
        }
    }

    public List<BlackZone> all() {
        return List.copyOf(zones.values());
    }

    public Optional<BlackZone> find(String id) {
        return Optional.ofNullable(zones.get(id));
    }

    public List<BlackZone> forServer(String dimension, String layer) {
        List<BlackZone> out = new ArrayList<>();
        for (BlackZone zone : zones.values()) {
            if (zone.dimension().equals(dimension) && zone.layer().equals(layer)) {
                out.add(zone);
            }
        }
        return out;
    }

    public void addOrReplace(BlackZone zone) throws IOException {
        zones.put(zone.id(), zone);
        save();
    }

    /** @return true if removed, false if no blackzone had that id */
    public boolean remove(String id) throws IOException {
        if (zones.remove(id) == null) {
            return false;
        }
        save();
        return true;
    }

    public boolean isBlackzoned(String dimension, String layer, RegionFile region) {
        for (BlackZone zone : zones.values()) {
            if (zone.covers(dimension, layer, region)) {
                return true;
            }
        }
        return false;
    }
}

package tools.argus.uploader.core;

import java.util.List;

/**
 * The wording of the confirm popup shown before any blackzone is removed - kept here, free of any
 * Minecraft classes, so every Minecraft version's popup says the same thing and the wording is
 * unit-testable. Removing a blackzone weakens a privacy protection, so the popup names exactly
 * what is about to stop being excluded.
 */
public final class BlackzoneRemovalPrompt {

    private static final int MAX_LISTED = 3;

    private BlackzoneRemovalPrompt() {
    }

    public static String title(List<BlackZone> zones) {
        if (zones.size() == 1) {
            return "Remove blackzone \"" + name(zones.get(0)) + "\"?";
        }
        return "Remove " + zones.size() + " blackzones?";
    }

    public static String body(List<BlackZone> zones) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(zones.size(), MAX_LISTED); i++) {
            BlackZone zone = zones.get(i);
            sb.append(name(zone)).append(": ").append(zone.dimension()).append(", region ")
                    .append(zone.bounds().minRegionX()).append("..").append(zone.bounds().maxRegionX()).append(", ")
                    .append(zone.bounds().minRegionZ()).append("..").append(zone.bounds().maxRegionZ()).append('\n');
        }
        if (zones.size() > MAX_LISTED) {
            sb.append("...and ").append(zones.size() - MAX_LISTED).append(" more\n");
        }
        sb.append("\nRegions inside can be uploaded again on the next run. This only changes this device.");
        return sb.toString();
    }

    private static String name(BlackZone zone) {
        return zone.label().isBlank() ? zone.id() : zone.label();
    }
}

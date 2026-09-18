package tools.argus.uploader.fabric;

import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.ServerProfile;
import tools.argus.uploader.core.ServerRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared logic behind both the automatic on-join detection and the manual
 * {@code /argus server} command, so the two paths can't drift apart.
 */
final class ServerDetection {

    record Outcome(String message, boolean isError, boolean matched) {
    }

    private ServerDetection() {
    }

    static Outcome detectAndApply(String address, ServerRegistry registry, ArgusConfig config, Path configPath) {
        if (address == null || address.isBlank()) {
            return new Outcome("Could not read the current server address to auto-detect.", true, false);
        }
        List<ServerProfile> matches = registry.matching(address);
        if (matches.isEmpty()) {
            return new Outcome("No known ARGUS server matches '" + address
                    + "'. Register it with /argus server add <id> <layer> <matchSubstrings>, or /argus server use <id> if it's already registered.", false, false);
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder("Multiple known servers match '" + address + "': ");
            matches.forEach(p -> sb.append(p.name()).append(' '));
            sb.append("- pick one with /argus server use <id>.");
            return new Outcome(sb.toString(), true, false);
        }
        ServerProfile profile = matches.get(0);
        boolean changed = !profile.layer().equals(config.layer);
        config.layer = profile.layer();
        try {
            config.save(configPath);
        } catch (IOException e) {
            return new Outcome("Detected server '" + profile.name() + "' but failed to save config: " + e.getMessage(), true, true);
        }
        return new Outcome(changed
                ? "Detected server '" + profile.name() + "', layer set to '" + profile.layer() + "'."
                : "Detected server '" + profile.name() + "' (layer already '" + profile.layer() + "').", false, true);
    }
}

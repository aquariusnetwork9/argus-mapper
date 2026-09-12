package tools.argus.uploader.core;

import java.util.List;

/**
 * A known anarchy server: which ARGUS layer its region data goes to, and
 * which substrings of a connected server address identify it. Matching is
 * deliberately loose (case-insensitive "contains") since anarchy servers
 * like 6b6t are reached through one public hostname that can route to any
 * of several backend addresses the client never sees directly - the
 * client only ever knows the address the player typed.
 */
public record ServerProfile(String name, String layer, List<String> addressMatches) {

    public boolean matches(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String lower = address.toLowerCase();
        return addressMatches.stream().anyMatch(m -> !m.isBlank() && lower.contains(m.toLowerCase()));
    }
}

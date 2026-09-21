package tools.argus.uploader.core.waystone;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The only thing the mod will ever type into chat for a waystone is {@code /tpa <name>}, and the
 * name can come from the network, so it must look exactly like a Minecraft username.
 */
public final class BotName {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private BotName() {
    }

    public static Optional<String> validate(String candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        String trimmed = candidate.trim();
        return USERNAME.matcher(trimmed).matches() ? Optional.of(trimmed) : Optional.empty();
    }

    /** The command to send, without the leading slash. */
    public static Optional<String> tpaCommand(String candidate) {
        return validate(candidate).map(name -> "tpa " + name);
    }
}

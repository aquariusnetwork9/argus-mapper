package tools.argus.uploader.core;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes the server's own "Teleporting to <place> in N seconds" countdown message (what 6b6t
 * prints when a /home or similar teleport is accepted), so uploads can pause before the player
 * arrives rather than only after.
 */
public final class TeleportChat {

    private static final Pattern COUNTDOWN = Pattern.compile("\\bteleporting\\b.*?\\bin\\s+(\\d{1,4})\\s+seconds?\\b",
            Pattern.CASE_INSENSITIVE);

    private TeleportChat() {
    }

    public static OptionalInt secondsUntilTeleport(String message) {
        if (message == null) {
            return OptionalInt.empty();
        }
        Matcher m = COUNTDOWN.matcher(message);
        return m.find() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }
}

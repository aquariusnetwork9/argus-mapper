package tools.argus.uploader.core.waystone;

import java.util.List;

/**
 * The delivery bot's availability.
 *
 * @param secondsUntilReady the bot's live cooldown; 0 means it can deliver now
 * @param bots              the delivery bot name(s) ARGUS lists, if it lists any; only valid usernames
 */
public record SystemStatus(boolean online, boolean busy, int queued, int secondsUntilReady, List<String> bots) {

    public SystemStatus {
        bots = List.copyOf(bots);
    }
}

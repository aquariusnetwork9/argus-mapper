package tools.argus.uploader.core.waystone;

import java.util.List;

/**
 * The delivery bot's availability.
 *
 * @param secondsUntilReady the bot's live cooldown; 0 means it can deliver now
 * @param bots              ARGUS's delivery bot roster ("bots" in the reply) - one bot today, but a
 *                           list since more can be added; only entries that look like a real
 *                           username are kept. For an actual teleport, prefer
 *                           {@link TeleportStatus#bot()} - it names the specific bot assigned to it.
 */
public record SystemStatus(boolean online, boolean busy, int queued, int secondsUntilReady, List<String> bots) {

    public SystemStatus {
        bots = List.copyOf(bots);
    }
}

package tools.argus.uploader.core.waystone;

/**
 * The delivery bot's availability.
 *
 * @param secondsUntilReady the bot's live cooldown; 0 means it can deliver now
 */
public record SystemStatus(boolean online, boolean busy, int queued, int secondsUntilReady) {
}

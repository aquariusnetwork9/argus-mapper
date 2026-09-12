package tools.argus.uploader.core;

import java.util.List;

/** Pure formatting for the Discord leaderboard embed, kept separate from I/O so it's easy to test. */
public final class LeaderboardReport {

    private LeaderboardReport() {
    }

    public static String title() {
        return "ARGUS Mapper contribution";
    }

    public static String description(String serverName) {
        return (serverName == null || serverName.isBlank()) ? "Contribution update" : "Contribution update - " + serverName;
    }

    public static List<DiscordWebhookClient.Field> fields(long regionsContributed, double distanceBlocks) {
        long approxChunks = regionsContributed * 1024L;
        return List.of(
                new DiscordWebhookClient.Field("Regions contributed", String.valueOf(regionsContributed), true),
                new DiscordWebhookClient.Field("~Chunks contributed", String.valueOf(approxChunks), true),
                new DiscordWebhookClient.Field("Distance traveled", formatDistance(distanceBlocks), true));
    }

    static String formatDistance(double blocks) {
        if (blocks >= 1000) {
            return String.format("%.1f km", blocks / 1000.0);
        }
        return String.format("%.0f blocks", blocks);
    }
}

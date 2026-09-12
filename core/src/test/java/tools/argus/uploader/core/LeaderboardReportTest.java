package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardReportTest {

    @Test
    void chunksAreApproximatedFromRegionCount() {
        List<DiscordWebhookClient.Field> fields = LeaderboardReport.fields(5, 0);
        assertEquals("5", fields.get(0).value());
        assertEquals(String.valueOf(5 * 1024L), fields.get(1).value());
    }

    @Test
    void distanceIsFormattedInBlocksBelow1000() {
        assertEquals("500 blocks", LeaderboardReport.formatDistance(500));
        assertEquals("999 blocks", LeaderboardReport.formatDistance(999));
    }

    @Test
    void distanceIsFormattedInKilometersAt1000AndAbove() {
        assertEquals("1.0 km", LeaderboardReport.formatDistance(1000));
        assertEquals("2.5 km", LeaderboardReport.formatDistance(2500));
    }

    @Test
    void descriptionMentionsServerNameWhenPresent() {
        assertTrue(LeaderboardReport.description("6b6t").contains("6b6t"));
        assertEquals("Contribution update", LeaderboardReport.description(null));
        assertEquals("Contribution update", LeaderboardReport.description(""));
    }
}

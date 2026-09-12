package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookClientTest {

    @Test
    void quoteEscapesControlAndSpecialCharacters() {
        assertEquals("\"hello\"", DiscordWebhookClient.quote("hello"));
        assertEquals("\"a\\\"b\"", DiscordWebhookClient.quote("a\"b"));
        assertEquals("\"a\\\\b\"", DiscordWebhookClient.quote("a\\b"));
        assertEquals("\"a\\nb\"", DiscordWebhookClient.quote("a\nb"));
        assertEquals("\"\"", DiscordWebhookClient.quote(null));
    }

    @Test
    void buildEmbedPayloadProducesValidLookingJsonShape() {
        String json = DiscordWebhookClient.buildEmbedPayload(
                "Title \"quoted\"",
                "Line1\nLine2",
                List.of(new DiscordWebhookClient.Field("Regions", "42", true)));

        assertTrue(json.startsWith("{\"embeds\":[{"));
        assertTrue(json.contains("\"title\":\"Title \\\"quoted\\\"\""));
        assertTrue(json.contains("\"description\":\"Line1\\nLine2\""));
        assertTrue(json.contains("\"fields\":[{\"name\":\"Regions\",\"value\":\"42\",\"inline\":true}]"));
        assertEquals(json.chars().filter(c -> c == '{').count(), json.chars().filter(c -> c == '}').count(), "braces should balance");
        assertEquals(json.chars().filter(c -> c == '[').count(), json.chars().filter(c -> c == ']').count(), "brackets should balance");
    }

    @Test
    void buildEmbedPayloadWithNoFieldsOmitsFieldsArray() {
        String json = DiscordWebhookClient.buildEmbedPayload("t", "d", List.of());
        assertEquals("{\"embeds\":[{\"title\":\"t\",\"description\":\"d\"}]}", json);
    }

    @Test
    void postEmbedWithoutWebhookUrlFailsWithoutMakingARequest() {
        DiscordWebhookClient.Result result = new DiscordWebhookClient().postEmbed(null, "t", "d", List.of());
        assertEquals(false, result.success());
        assertEquals(-1, result.statusCode());

        result = new DiscordWebhookClient().postEmbed("", "t", "d", List.of());
        assertEquals(false, result.success());
    }
}

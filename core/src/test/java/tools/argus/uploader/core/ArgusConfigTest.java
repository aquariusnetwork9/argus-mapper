package tools.argus.uploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgusConfigTest {

    @Test
    void firstLoadWritesATemplateWithBlankSecrets(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("argus-mapper.properties");
        ArgusConfig cfg = ArgusConfig.load(file);

        assertTrue(Files.exists(file), "should write a template on first load");
        assertEquals("", cfg.token);
        assertEquals("", cfg.layer);
        assertFalse(cfg.isUsable());
        assertFalse(cfg.reuploadChangedRegions, "re-uploading must be opt-in");
    }

    @Test
    void roundTripsAllFields(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("argus-mapper.properties");
        ArgusConfig cfg = ArgusConfig.load(file);
        cfg.token = "sp_test_token";
        cfg.layer = "shallowplague";
        cfg.xaeroRootOverride = "C:\\some\\path";
        cfg.includeCaves = true;
        cfg.restrictNetherToHighways = false;
        cfg.paceMillis = 4242;
        cfg.maxPerBatch = 50;
        cfg.maxFileSizeBytes = 123456;
        cfg.maxRetries = 7;
        cfg.discordWebhookUrl = "https://discord.com/api/webhooks/1/abc";
        cfg.autoReportToDiscord = true;
        cfg.discordApplicationId = "123";
        cfg.enableRichPresence = true;
        cfg.enableAddonApi = false;
        cfg.reuploadChangedRegions = true;
        cfg.save(file);

        ArgusConfig reloaded = ArgusConfig.load(file);
        assertEquals("sp_test_token", reloaded.token);
        assertEquals("shallowplague", reloaded.layer);
        assertEquals("C:\\some\\path", reloaded.xaeroRootOverride);
        assertTrue(reloaded.includeCaves);
        assertFalse(reloaded.restrictNetherToHighways);
        assertEquals(4242, reloaded.paceMillis);
        assertEquals(50, reloaded.maxPerBatch);
        assertEquals(123456, reloaded.maxFileSizeBytes);
        assertEquals(7, reloaded.maxRetries);
        assertEquals("https://discord.com/api/webhooks/1/abc", reloaded.discordWebhookUrl);
        assertTrue(reloaded.autoReportToDiscord);
        assertEquals("123", reloaded.discordApplicationId);
        assertTrue(reloaded.enableRichPresence);
        assertFalse(reloaded.enableAddonApi);
        assertTrue(reloaded.reuploadChangedRegions);
        assertTrue(reloaded.isUsable());
    }

    @Test
    void blankLayerOrTokenMeansNotUsable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("argus-mapper.properties");
        ArgusConfig cfg = ArgusConfig.load(file);
        cfg.token = "abc";
        cfg.layer = "";
        assertFalse(cfg.isUsable());
        cfg.token = "";
        cfg.layer = "abc";
        assertFalse(cfg.isUsable());
    }
}

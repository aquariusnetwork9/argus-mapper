package tools.argus.uploader.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Flat, human-editable config. Loaded from / saved to a .properties file. */
public final class ArgusConfig {

    public String apiBaseUrl = "https://map.argus.tools/api/partner/upload";
    public String token = "";
    public String layer = "";
    public String xaeroRootOverride = "";
    public boolean includeCaves = false;

    // Nether privacy gate (see HighwayProximity): when true, a nether region can only be
    // uploaded if it comes near a road in the Aquarius Road Department (ARD) highway network for
    // the current server. Defaults to true - "exclude when unsure" is the correct default, not
    // an opt-in. Does not affect overworld or end uploads.
    public boolean restrictNetherToHighways = true;
    public long paceMillis = 3000L;
    public int maxPerBatch = 200;
    public long maxFileSizeBytes = 10_000_000L;
    public int maxRetries = 3;

    // Discord webhook reporting (leaderboard / distance / chunks-contributed stats).
    // See DiscordWebhookClient. Create a webhook URL from a Discord channel's
    // Integrations settings - this mod never talks to a Discord bot or gateway.
    public String discordWebhookUrl = "";
    public boolean autoReportToDiscord = false;

    // Rich Presence is NOT wired up yet - see README "Discord Rich Presence
    // (not yet implemented)". These fields are reserved so a future build can
    // add it without another config migration.
    public String discordApplicationId = "";
    public boolean enableRichPresence = false;

    public boolean isUsable() {
        return !token.isBlank() && !layer.isBlank();
    }

    public static ArgusConfig load(Path file) throws IOException {
        ArgusConfig cfg = new ArgusConfig();
        if (!Files.exists(file)) {
            cfg.save(file);
            return cfg;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        cfg.apiBaseUrl = p.getProperty("apiBaseUrl", cfg.apiBaseUrl);
        cfg.token = p.getProperty("token", cfg.token);
        cfg.layer = p.getProperty("layer", cfg.layer);
        cfg.xaeroRootOverride = p.getProperty("xaeroRootOverride", cfg.xaeroRootOverride);
        cfg.includeCaves = Boolean.parseBoolean(p.getProperty("includeCaves", String.valueOf(cfg.includeCaves)));
        cfg.restrictNetherToHighways = Boolean.parseBoolean(p.getProperty("restrictNetherToHighways", String.valueOf(cfg.restrictNetherToHighways)));
        cfg.paceMillis = parseLong(p.getProperty("paceMillis"), cfg.paceMillis);
        cfg.maxPerBatch = (int) parseLong(p.getProperty("maxPerBatch"), cfg.maxPerBatch);
        cfg.maxFileSizeBytes = parseLong(p.getProperty("maxFileSizeBytes"), cfg.maxFileSizeBytes);
        cfg.maxRetries = (int) parseLong(p.getProperty("maxRetries"), cfg.maxRetries);
        cfg.discordWebhookUrl = p.getProperty("discordWebhookUrl", cfg.discordWebhookUrl);
        cfg.autoReportToDiscord = Boolean.parseBoolean(p.getProperty("autoReportToDiscord", String.valueOf(cfg.autoReportToDiscord)));
        cfg.discordApplicationId = p.getProperty("discordApplicationId", cfg.discordApplicationId);
        cfg.enableRichPresence = Boolean.parseBoolean(p.getProperty("enableRichPresence", String.valueOf(cfg.enableRichPresence)));
        return cfg;
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("apiBaseUrl", apiBaseUrl);
        p.setProperty("token", token);
        p.setProperty("layer", layer);
        p.setProperty("xaeroRootOverride", xaeroRootOverride);
        p.setProperty("includeCaves", String.valueOf(includeCaves));
        p.setProperty("restrictNetherToHighways", String.valueOf(restrictNetherToHighways));
        p.setProperty("paceMillis", String.valueOf(paceMillis));
        p.setProperty("maxPerBatch", String.valueOf(maxPerBatch));
        p.setProperty("maxFileSizeBytes", String.valueOf(maxFileSizeBytes));
        p.setProperty("maxRetries", String.valueOf(maxRetries));
        p.setProperty("discordWebhookUrl", discordWebhookUrl);
        p.setProperty("autoReportToDiscord", String.valueOf(autoReportToDiscord));
        p.setProperty("discordApplicationId", discordApplicationId);
        p.setProperty("enableRichPresence", String.valueOf(enableRichPresence));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "ARGUS uploader config. Fill in token + layer. Never commit this file.");
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

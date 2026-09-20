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

    // Rich Presence is NOT wired up yet - see README "Discord". These fields are
    // reserved so a future build can add it without another config migration.
    public String discordApplicationId = "";
    public boolean enableRichPresence = false;

    // Gates ArgusMapperEvents (see that class's javadoc) - when false, UPLOAD_COMPLETED/
    // STATS_CHANGED are never invoked, so no other mod's registered listener ever fires,
    // regardless of whether it called .register() itself. Defaults to true (matches existing
    // behavior; this add-on surface predates the toggle). Note what this can't do: any mod in the
    // same JVM can already read the player's live position directly from vanilla Minecraft APIs
    // with no dependency on this mod at all - this toggle only controls ARGUS Mapper's own two
    // events (upload counts, aggregate stats), neither of which ever carried live position.
    public boolean enableAddonApi = true;

    // When true, a region that was uploaded before but whose Xaero file is newer than it was at
    // that upload is included in the next explicit upload run, instead of being skipped as "already
    // uploaded" forever. Still subject to every blackzone/size/coordinate check a first upload is.
    // The ARGUS API replaces the stored region with the re-sent file, so a re-upload supersedes the
    // older copy rather than duplicating it. Defaults to false so updating the mod doesn't start
    // re-sending regions until the player opts in.
    public boolean reuploadChangedRegions = false;

    public double autoMapMaxSpeed = 5.99;
    public double autoMapMinSpeed = 1.5;
    public int autoMapCruiseY = 300;
    public int autoMapHalfWidthChunks = 0;

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
        cfg.enableAddonApi = Boolean.parseBoolean(p.getProperty("enableAddonApi", String.valueOf(cfg.enableAddonApi)));
        cfg.reuploadChangedRegions = Boolean.parseBoolean(p.getProperty("reuploadChangedRegions", String.valueOf(cfg.reuploadChangedRegions)));
        cfg.autoMapMaxSpeed = parseDouble(p.getProperty("autoMapMaxSpeed"), cfg.autoMapMaxSpeed);
        cfg.autoMapMinSpeed = parseDouble(p.getProperty("autoMapMinSpeed"), cfg.autoMapMinSpeed);
        cfg.autoMapCruiseY = (int) parseLong(p.getProperty("autoMapCruiseY"), cfg.autoMapCruiseY);
        cfg.autoMapHalfWidthChunks = (int) parseLong(p.getProperty("autoMapHalfWidthChunks"), cfg.autoMapHalfWidthChunks);
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
        p.setProperty("enableAddonApi", String.valueOf(enableAddonApi));
        p.setProperty("reuploadChangedRegions", String.valueOf(reuploadChangedRegions));
        p.setProperty("autoMapMaxSpeed", String.valueOf(autoMapMaxSpeed));
        p.setProperty("autoMapMinSpeed", String.valueOf(autoMapMinSpeed));
        p.setProperty("autoMapCruiseY", String.valueOf(autoMapCruiseY));
        p.setProperty("autoMapHalfWidthChunks", String.valueOf(autoMapHalfWidthChunks));
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "ARGUS uploader config. Fill in token + layer. Never commit this file.");
        }
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
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

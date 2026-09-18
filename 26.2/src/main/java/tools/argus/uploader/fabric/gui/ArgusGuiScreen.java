package tools.argus.uploader.fabric.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.DiscordWebhookClient;
import tools.argus.uploader.core.MapperStats;
import tools.argus.uploader.core.ServerProfile;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadTracker;
import tools.argus.uploader.fabric.ArgusUploaderClientMod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 26.2's port of fabric-common's/1.21.11's ArgusGuiScreen. Every vanilla touchpoint re-verified
 * via javap against the real 26.2 client jar and Fabric's own reference examples
 * (FabricMC/fabric-docs), not carried over from 1.21.11 by assumption: {@code MinecraftClient} -&gt;
 * {@code Minecraft}, {@code DrawContext} -&gt; {@code GuiGraphicsExtractor}, {@code Text} -&gt;
 * {@code Component}, {@code TextFieldWidget} -&gt; {@code EditBox} ({@code setText}/
 * {@code setChangedListener} -&gt; {@code setValue}/{@code setResponder}), {@code textRenderer} -&gt;
 * {@code font} ({@code TextRenderer.getWidth} -&gt; {@code Font.width}), {@code
 * drawTextWithShadow}/{@code drawCenteredTextWithShadow} -&gt; {@code text}/{@code centeredText},
 * {@code addDrawableChild}/{@code clearChildren} -&gt; {@code addRenderableWidget}/
 * {@code clearWidgets}, {@code shouldPause()} -&gt; {@code isPauseScreen()}, and the render override
 * itself -&gt; {@code extractRenderState(GuiGraphicsExtractor, int, int, float)} (part of a wider
 * "extract"-prefixed rendering rename across this version's whole GUI API, confirmed against
 * Fabric's own DrawContextExampleScreen.java reference).
 *
 * <p><b>No Road Dept. (ARD) tab here</b>, unlike the 1.21.x builds: ARD has no 26.2 port at all
 * (see 26.2/NOTES.md), so this screen has 6 tabs, not 7 - General, Token, Servers, Stats,
 * Uploads, API. If ARD ever gets a 26.2 port, that tab goes back in as its own addition, not a
 * blind copy of the 1.21.x version (which reaches into ARD's config directly).
 */
public final class ArgusGuiScreen extends Screen {

    private static final String[] TAB_NAMES = {"General", "Token", "Servers", "Stats", "Uploads", "API"};
    private static final int MIN_PANEL_W = 300;
    private static final int HEADER_H = 46;
    private static final int PAD = 12;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 4;

    private static final int GENERAL_TAB_CONTENT_H =
            (10 + ROW_H + ROW_GAP + 4)
                    + (10 + ROW_H + ROW_GAP + 6)
                    + (ROW_H + ROW_GAP)
                    + (ROW_H + ROW_GAP)
                    + 4
                    + (10 + ROW_H + ROW_GAP)
                    + (10 + ROW_H + ROW_GAP);
    private static final int PANEL_H_DEFAULT = HEADER_H + PAD + GENERAL_TAB_CONTENT_H + PAD + ROW_H + PAD;

    private static final int BG_DIM = 0x88000000;
    private static final int PANEL_BG = 0xFF12131A;
    private static final int PANEL_BORDER = 0xFF23252F;
    private static final int TITLE_COLOR = 0xFFE6E8EF;
    private static final int MUTED = 0xFF7D8296;
    private static final int ACCENT = 0xFF8A6BFF;
    private static final int FIELD_BG = 0xFF1A1C25;

    private enum Tab { GENERAL, TOKEN, SERVERS, STATS, UPLOADS, API }

    private Tab currentTab = Tab.GENERAL;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private final List<Label> labels = new ArrayList<>();

    private EditBox tokenField;
    private boolean tokenRevealed = false;
    private EditBox webhookField;
    private boolean webhookRevealed = false;

    private record Label(String text, int x, int y, int color) {
    }

    public ArgusGuiScreen() {
        super(Component.literal("ARGUS Mapper"));
    }

    @Override
    protected void init() {
        panelW = Math.max(MIN_PANEL_W, tabBarWidth() + PAD * 2);
        panelW = Math.min(panelW, Math.max(MIN_PANEL_W, width - PAD * 2));
        panelH = Math.min(PANEL_H_DEFAULT, Math.max(HEADER_H + ROW_H + PAD * 2, height - PAD * 2));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        buildTab(currentTab);
    }

    private int tabBarWidth() {
        int total = 0;
        for (String name : TAB_NAMES) {
            total += 8 + this.font.width(name) + 10;
        }
        return total - 10;
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        this.clearWidgets();
        buildTab(tab);
    }

    private void buildTab(Tab tab) {
        labels.clear();
        tileRects.clear();
        addTabBar();
        int contentTop = panelY + HEADER_H + PAD;
        switch (tab) {
            case GENERAL -> buildGeneralTab(contentTop);
            case TOKEN -> buildTokenTab(contentTop);
            case SERVERS -> buildServersTab(contentTop);
            case STATS -> buildStatsTab(contentTop);
            case UPLOADS -> buildUploadsTab(contentTop);
            case API -> buildApiTab(contentTop);
        }
    }

    private void addTabBar() {
        Tab[] values = Tab.values();
        int x = panelX + PAD;
        int y = panelY + 24;
        for (int i = 0; i < values.length; i++) {
            Tab t = values[i];
            int w = 8 + this.font.width(TAB_NAMES[i]);
            PanelButton tabBtn = new PanelButton(x, y, w, 16, Component.literal(TAB_NAMES[i]), () -> switchTab(t))
                    .border(0)
                    .colors(t == currentTab ? 0x00000000 : 0x00000000, t == currentTab ? ACCENT : MUTED);
            addRenderableWidget(tabBtn);
            x += w + 10;
        }
    }

    // ---------------------------------------------------------------- General

    private void buildGeneralTab(int top) {
        ArgusConfig cfg = ArgusUploaderClientMod.config();
        int y = top;

        label("API base URL (read-only)", panelX + PAD, y, MUTED);
        y += 10;
        label(cfg.apiBaseUrl, panelX + PAD, y, TITLE_COLOR);
        y += ROW_H + ROW_GAP + 4;

        label("Xaero folder override (blank = auto-detect)", panelX + PAD, y, MUTED);
        y += 10;
        EditBox xaeroField = new EditBox(this.font, panelX + PAD, y, panelW - PAD * 2, ROW_H, Component.empty());
        xaeroField.setMaxLength(256);
        xaeroField.setValue(cfg.xaeroRootOverride);
        xaeroField.setResponder(s -> cfg.xaeroRootOverride = s);
        addRenderableWidget(xaeroField);
        y += ROW_H + ROW_GAP + 6;

        y = toggleRow(y, "Include cave regions", cfg.includeCaves, v -> cfg.includeCaves = v);
        y = toggleRow(y, "Restrict nether uploads to ARD highways", cfg.restrictNetherToHighways, v -> cfg.restrictNetherToHighways = v);
        y += 4;

        y = sliderRow(y, "Pace between uploads", 1000, 10000, 500, cfg.paceMillis > Integer.MAX_VALUE ? 10000 : (int) cfg.paceMillis,
                v -> v + " ms", v -> cfg.paceMillis = v);
        y = sliderRow(y, "Regions per batch", 10, 200, 10, cfg.maxPerBatch, v -> Integer.toString(v), v -> cfg.maxPerBatch = v);

        int saveY = panelY + panelH - PAD - ROW_H;
        addRenderableWidget(saveButton(panelX + PAD, saveY, cfg));
        addRenderableWidget(new PanelButton(panelX + PAD + 100, saveY, 90, ROW_H, Component.literal("Reload"), () -> {
            ArgusUploaderClientMod.reloadConfig();
            switchTab(Tab.GENERAL);
        }));
    }

    // ---------------------------------------------------------------- Token

    private void buildTokenTab(int top) {
        ArgusConfig cfg = ArgusUploaderClientMod.config();
        int y = top;

        label("ARGUS partner token", panelX + PAD, y, MUTED);
        y += 10;
        tokenField = new EditBox(this.font, panelX + PAD, y, panelW - PAD * 2 - 24, ROW_H, Component.empty());
        tokenField.setMaxLength(256);
        tokenField.setValue(cfg.token);
        tokenField.setEditable(tokenRevealed);
        tokenField.setResponder(s -> cfg.token = s);
        addRenderableWidget(tokenField);
        addRenderableWidget(new PanelButton(panelX + panelW - PAD - 20, y, 20, ROW_H, Component.literal(tokenRevealed ? "-" : "o"),
                () -> {
                    tokenRevealed = !tokenRevealed;
                    switchTab(Tab.TOKEN);
                }));
        y += ROW_H + 2;
        label("Also settable with /argus settoken <token>", panelX + PAD, y, MUTED);
        y += ROW_H;

        label("Discord webhook URL", panelX + PAD, y, MUTED);
        y += 10;
        webhookField = new EditBox(this.font, panelX + PAD, y, panelW - PAD * 2 - 24, ROW_H, Component.empty());
        webhookField.setMaxLength(256);
        webhookField.setValue(cfg.discordWebhookUrl);
        webhookField.setEditable(webhookRevealed);
        webhookField.setResponder(s -> cfg.discordWebhookUrl = s);
        addRenderableWidget(webhookField);
        addRenderableWidget(new PanelButton(panelX + panelW - PAD - 20, y, 20, ROW_H, Component.literal(webhookRevealed ? "-" : "o"),
                () -> {
                    webhookRevealed = !webhookRevealed;
                    switchTab(Tab.TOKEN);
                }));
        y += ROW_H + 6;
        label("Stored only in this device's argus-mapper.properties.", panelX + PAD, y, MUTED);

        int saveY = panelY + panelH - PAD - ROW_H;
        addRenderableWidget(saveButton(panelX + PAD, saveY, cfg));
    }

    // ---------------------------------------------------------------- Servers

    private void buildServersTab(int top) {
        ArgusConfig cfg = ArgusUploaderClientMod.config();
        List<ServerProfile> all = ArgusUploaderClientMod.registry().all();
        int y = top;
        label("Known servers", panelX + PAD, y, MUTED);
        y += 12;

        int shown = 0;
        for (ServerProfile p : all) {
            if (shown >= 3) {
                break;
            }
            boolean active = p.layer().equals(cfg.layer);
            label(p.name() + "  (" + p.layer() + ")", panelX + PAD, y + 4, TITLE_COLOR);
            if (active) {
                label("ACTIVE", panelX + PAD + 180, y + 4, ACCENT);
            } else {
                addRenderableWidget(new PanelButton(panelX + panelW - PAD - 50, y, 50, ROW_H, Component.literal("Use"), () -> {
                    cfg.layer = p.layer();
                    persist(cfg);
                    switchTab(Tab.SERVERS);
                }));
            }
            y += ROW_H + ROW_GAP;
            shown++;
        }
        y += 8;
        label("Add servers with /argus server add <id> <layer> <matches>", panelX + PAD, y, MUTED);
    }

    // ---------------------------------------------------------------- Stats

    private void buildStatsTab(int top) {
        long regions;
        try {
            regions = UploadManifest.load(ArgusUploaderClientMod.manifestPath()).size();
        } catch (IOException e) {
            regions = 0;
        }
        MapperStats stats = MapperStats.of(regions, ArgusUploaderClientMod.stats().totalDistanceBlocks);

        int y = top;
        int tileW = (panelW - PAD * 2 - 8 * 2) / 3;
        statTile(panelX + PAD, y, tileW, "REGIONS", Long.toString(stats.regionsContributed()));
        statTile(panelX + PAD + tileW + 8, y, tileW, "~CHUNKS", Long.toString(stats.chunksContributedApprox()));
        statTile(panelX + PAD + (tileW + 8) * 2, y, tileW, "DISTANCE", formatDistance(stats.distanceTraveledBlocks()));
        y += 46;

        ArgusConfig cfg = ArgusUploaderClientMod.config();
        y = toggleRow(y, "Auto-report to Discord after uploads", cfg.autoReportToDiscord, v -> cfg.autoReportToDiscord = v);
        y += 6;

        addRenderableWidget(new PanelButton(panelX + PAD, y, 130, ROW_H, Component.literal("Send test report"), () -> {
            new Thread(() -> {
                var result = new DiscordWebhookClient().postEmbed(
                        cfg.discordWebhookUrl, "ARGUS Mapper", "Test message from the GUI.", List.of());
                var mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.execute(() -> mc.player.sendSystemMessage(Component.literal("[ARGUS] "
                            + (result.success() ? "Test message sent." : "Test failed."))));
                }
            }, "argus-gui-discord-test").start();
        }));

        int saveY = panelY + panelH - PAD - ROW_H;
        addRenderableWidget(saveButton(panelX + PAD, saveY, cfg));
    }

    private static String formatDistance(double blocks) {
        return blocks >= 1000 ? String.format("%.1f km", blocks / 1000.0) : String.format("%.0f blk", blocks);
    }

    private void statTile(int x, int y, int w, String key, String value) {
        this.labels.add(new Label(key, x + 6, y + 6, MUTED));
        this.labels.add(new Label(value, x + 6, y + 18, TITLE_COLOR));
        this.tileRects.add(new int[]{x, y, w, 32});
    }

    private final List<int[]> tileRects = new ArrayList<>();

    // ---------------------------------------------------------------- Uploads

    private int uploadsContentTop;

    private void buildUploadsTab(int top) {
        uploadsContentTop = top;
    }

    private void renderUploads(GuiGraphicsExtractor context) {
        UploadTracker tracker = ArgusUploaderClientMod.activeUpload();
        int y = uploadsContentTop;
        if (tracker == null) {
            context.text(this.font, "No upload has run yet this session.", panelX + PAD, y, MUTED, true);
            context.text(this.font, "Run /argus scan, then /argus upload to start one.", panelX + PAD, y + 12, MUTED, true);
            return;
        }

        List<UploadTracker.RowState> rows = tracker.rows();
        int total = rows.size();
        int done = 0;
        int failed = 0;
        int queued = 0;
        String uploadingName = null;
        for (UploadTracker.RowState row : rows) {
            switch (row.status()) {
                case DONE -> done++;
                case FAILED -> failed++;
                case QUEUED -> queued++;
                case UPLOADING -> uploadingName = row.region().filename();
            }
        }
        int resolved = done + failed;

        context.text(this.font, "Upload progress", panelX + PAD, y, TITLE_COLOR, true);
        y += 14;

        int barX = panelX + PAD;
        int barW = panelW - PAD * 2;
        int barH = 14;
        context.fill(barX, y, barX + barW, y + barH, FIELD_BG);
        if (total > 0) {
            int fillW = (int) Math.round(barW * (resolved / (double) total));
            context.fill(barX, y, barX + fillW, y + barH, ACCENT);
        }
        PanelButton.drawBorder(context, barX, y, barW, barH, PANEL_BORDER);
        context.centeredText(this.font, resolved + " / " + total,
                barX + barW / 2, y + (barH - 8) / 2, TITLE_COLOR);
        y += barH + 8;

        context.text(this.font, "Queued: " + queued + "   Done: " + done + "   Failed: " + failed, panelX + PAD, y, MUTED, true);
        y += 12;
        if (tracker.excludedByBlackzone() > 0) {
            context.text(this.font, "Excluded by blackzone: " + tracker.excludedByBlackzone(),
                    panelX + PAD, y, MUTED, true);
            y += 12;
        }
        if (uploadingName != null) {
            context.text(this.font, "Uploading: " + uploadingName, panelX + PAD, y, ACCENT, true);
        } else if (total > 0 && resolved == total) {
            context.text(this.font, "Run finished.", panelX + PAD, y, ACCENT, true);
        }
    }

    // ---------------------------------------------------------------- API

    private void buildApiTab(int top) {
        ArgusConfig cfg = ArgusUploaderClientMod.config();
        int y = top;
        y = toggleRow(y, "Let other mods react to this mod's events", cfg.enableAddonApi, v -> cfg.enableAddonApi = v);
        y += 6;
        label("Other Fabric mods can react to ARGUS Mapper", panelX + PAD, y, TITLE_COLOR);
        y += 12;
        label("without touching its internals:", panelX + PAD, y, TITLE_COLOR);
        y += 16;
        label("ArgusMapperEvents.UPLOAD_COMPLETED.register(...)", panelX + PAD, y, ACCENT);
        y += 11;
        label("ArgusMapperEvents.STATS_CHANGED.register(...)", panelX + PAD, y, ACCENT);
        y += 18;
        label("Same pattern as ARD's own LocalHazardEvents.", panelX + PAD, y, MUTED);
        y += 20;
        statTile(panelX + PAD, y, 140, "ADD-ONS CONNECTED", "-");

        int saveY = panelY + panelH - PAD - ROW_H;
        addRenderableWidget(saveButton(panelX + PAD, saveY, cfg));
    }

    // ---------------------------------------------------------------- shared helpers

    private int toggleRow(int y, String text, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        label(text, panelX + PAD, y + (ROW_H - 8) / 2, TITLE_COLOR);
        addRenderableWidget(new ToggleButton(panelX + panelW - PAD - 40, y, 40, ROW_H, initial, onChange));
        return y + ROW_H + ROW_GAP;
    }

    private int sliderRow(int y, String text, int min, int max, int step, int initial,
                           java.util.function.IntFunction<String> labelFor, java.util.function.IntConsumer onChange) {
        label(text, panelX + PAD, y, MUTED);
        y += 10;
        addRenderableWidget(new LabeledSlider(panelX + PAD, y, panelW - PAD * 2, ROW_H, min, max, step, initial, labelFor, onChange));
        return y + ROW_H + ROW_GAP;
    }

    private PanelButton saveButton(int x, int y, ArgusConfig cfg) {
        return new PanelButton(x, y, 90, ROW_H, Component.literal("Save"), () -> persist(cfg))
                .colors(0xFF362A5E, 0xFFB79CFF);
    }

    private void persist(ArgusConfig cfg) {
        try {
            cfg.save(ArgusUploaderClientMod.configPath());
        } catch (IOException ignored) {
            // the chat commands surface this same failure identically; the GUI stays silent
            // rather than duplicate that error-reporting path for a first pass
        }
    }

    private void label(String text, int x, int y, int color) {
        labels.add(new Label(text, x, y, color));
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, BG_DIM);
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        PanelButton.drawBorder(context, panelX, panelY, panelW, panelH, PANEL_BORDER);
        context.text(this.font, "ARGUS Mapper", panelX + PAD, panelY + 8, TITLE_COLOR, true);

        for (int[] r : tileRects) {
            context.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], FIELD_BG);
            PanelButton.drawBorder(context, r[0], r[1], r[2], r[3], PANEL_BORDER);
        }

        for (Label l : labels) {
            context.text(this.font, l.text(), l.x(), l.y(), l.color(), true);
        }

        if (currentTab == Tab.UPLOADS) {
            renderUploads(context);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

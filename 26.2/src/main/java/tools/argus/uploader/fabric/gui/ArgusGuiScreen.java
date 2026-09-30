package tools.argus.uploader.fabric.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.BlackZone;
import tools.argus.uploader.core.DiscordWebhookClient;
import tools.argus.uploader.core.MapperStats;
import tools.argus.uploader.core.ServerProfile;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadTracker;
import tools.argus.uploader.core.automap.ChunkBox;
import tools.argus.uploader.fabric.ArgusUploaderClientMod;
import tools.argus.uploader.fabric.AutoMapper;
import tools.argus.uploader.fabric.AutoUploads;
import tools.argus.uploader.fabric.BlackzoneRemoval;
import tools.argus.uploader.fabric.Bounty;
import tools.argus.uploader.fabric.Waystones;
import tools.argus.uploader.core.waystone.Waystone;

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
 * (see 26.2/NOTES.md). So this screen has 10 tabs, not 11 - General, Token, Servers, Blackzones,
 * Stats, Uploads, Auto-map, Bounty, Waystones, API. Auto-map is now fully ported ({@code
 * AutoMapper}), so Bounty's "go" action actually queues a flight (see {@link Bounty#requestFlight})
 * instead of reporting a stub message. If ARD ever gets a 26.2 port, its own tab goes back in as
 * its own addition, not a blind copy of the 1.21.x version (which reaches into ARD's config
 * directly).
 */
public final class ArgusGuiScreen extends Screen {

    private static final String[] TAB_NAMES = {"General", "Token", "Servers", "Blackzones", "Stats", "Uploads", "Auto-map", "Bounty", "Waystones", "API"};
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
                    + (ROW_H + ROW_GAP)
                    + 4
                    + (10 + ROW_H + ROW_GAP)
                    + (10 + ROW_H + ROW_GAP);
    private static final int PANEL_H_DEFAULT = HEADER_H + PAD + GENERAL_TAB_CONTENT_H + PAD + ROW_H + PAD;

    // status (2 lines), area label + fields, buttons, calibration label + row, 3 rows of settings -
    // mirrors fabric-common's own row math for this tab (it's taller than PANEL_H_DEFAULT).
    private static final int AUTO_MAP_TAB_CONTENT_H = 28 + (10 + ROW_H + ROW_GAP) + (ROW_H + ROW_GAP + 4)
            + (10 + ROW_H + ROW_GAP + 6) + 3 * (10 + ROW_H + ROW_GAP);
    private static final int AUTO_MAP_PANEL_H = HEADER_H + PAD + AUTO_MAP_TAB_CONTENT_H + PAD + ROW_H + PAD;

    private static final int BG_DIM = 0x88000000;
    private static final int PANEL_BG = 0xFF12131A;
    private static final int PANEL_BORDER = 0xFF23252F;
    private static final int TITLE_COLOR = 0xFFE6E8EF;
    private static final int MUTED = 0xFF7D8296;
    private static final int ACCENT = 0xFF8A6BFF;
    private static final int FIELD_BG = 0xFF1A1C25;

    private enum Tab { GENERAL, TOKEN, SERVERS, BLACKZONES, STATS, UPLOADS, AUTO_MAP, BOUNTY, WAYSTONES, API }

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
        int wanted = tab == Tab.AUTO_MAP ? AUTO_MAP_PANEL_H : PANEL_H_DEFAULT;
        panelH = Math.min(wanted, Math.max(HEADER_H + ROW_H + PAD * 2, height - PAD * 2));
        panelY = (height - panelH) / 2;
        labels.clear();
        tileRects.clear();
        addTabBar();
        int contentTop = panelY + HEADER_H + PAD;
        switch (tab) {
            case GENERAL -> buildGeneralTab(contentTop);
            case TOKEN -> buildTokenTab(contentTop);
            case SERVERS -> buildServersTab(contentTop);
            case BLACKZONES -> buildBlackzonesTab(contentTop);
            case STATS -> buildStatsTab(contentTop);
            case UPLOADS -> buildUploadsTab(contentTop);
            case AUTO_MAP -> buildAutoMapTab(contentTop);
            case BOUNTY -> buildBountyTab(contentTop);
            case WAYSTONES -> buildWaystonesTab(contentTop);
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
        y = toggleRow(y, "Re-upload regions that changed", cfg.reuploadChangedRegions, v -> cfg.reuploadChangedRegions = v);
        y += 4;

        y = sliderRow(y, "Uploads at once", 1, ArgusConfig.MAX_UPLOAD_CONCURRENCY, 1, cfg.uploadConcurrency,
                v -> Integer.toString(v), v -> cfg.uploadConcurrency = v);
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

    // ---------------------------------------------------------------- Blackzones

    private static final int BLACKZONES_PER_PAGE = 4;
    private int blackzonePage = 0;

    private void buildBlackzonesTab(int top) {
        List<BlackZone> all = ArgusUploaderClientMod.blackzoneStore().all();
        int y = top;
        if (all.isEmpty()) {
            label("No blackzones set.", panelX + PAD, y, TITLE_COLOR);
            y += 14;
            label("Select an area on the Xaero world map, then right-click", panelX + PAD, y, MUTED);
            y += 11;
            label("and choose Mark ARGUS Blackzone.", panelX + PAD, y, MUTED);
            return;
        }
        int pages = (all.size() + BLACKZONES_PER_PAGE - 1) / BLACKZONES_PER_PAGE;
        blackzonePage = Math.min(blackzonePage, pages - 1);
        label("Never uploaded. Stored only on this device.", panelX + PAD, y, MUTED);
        y += 14;

        String activeLayer = ArgusUploaderClientMod.config().layer;
        int from = blackzonePage * BLACKZONES_PER_PAGE;
        for (BlackZone zone : all.subList(from, Math.min(all.size(), from + BLACKZONES_PER_PAGE))) {
            label(zone.label().isBlank() ? zone.id() : zone.label(), panelX + PAD, y, TITLE_COLOR);
            String where = zone.dimension() + ", region " + zone.bounds().minRegionX() + ".." + zone.bounds().maxRegionX()
                    + ", " + zone.bounds().minRegionZ() + ".." + zone.bounds().maxRegionZ()
                    + (zone.layer().equals(activeLayer) ? "" : "  (" + zone.layer() + ")");
            label(where, panelX + PAD, y + 10, MUTED);
            addRenderableWidget(new PanelButton(panelX + panelW - PAD - 60, y, 60, ROW_H, Component.literal("Remove"),
                    () -> BlackzoneRemoval.confirmAndRemove(this, List.of(zone), () -> switchTab(Tab.BLACKZONES))));
            y += ROW_H + ROW_GAP + 2;
        }

        if (pages > 1) {
            int pagerY = panelY + panelH - PAD - ROW_H;
            addRenderableWidget(new PanelButton(panelX + PAD, pagerY, 24, ROW_H, Component.literal("<"), () -> {
                blackzonePage = Math.max(0, blackzonePage - 1);
                switchTab(Tab.BLACKZONES);
            }));
            label((blackzonePage + 1) + " / " + pages, panelX + PAD + 32, pagerY + (ROW_H - 8) / 2, MUTED);
            addRenderableWidget(new PanelButton(panelX + PAD + 70, pagerY, 24, ROW_H, Component.literal(">"), () -> {
                blackzonePage = Math.min(pages - 1, blackzonePage + 1);
                switchTab(Tab.BLACKZONES);
            }));
        }
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
        int y = top;
        y = toggleRow(y, "Live upload (this session only)", AutoUploads.isLive(), on -> autoToggle(AutoUploads.Kind.LIVE, on));
        y = toggleRow(y, "Whole-map upload (this session only)", AutoUploads.isWholeMap(), on -> autoToggle(AutoUploads.Kind.WHOLE_MAP, on));
        if (AutoUploads.isLive() && AutoUploads.isPaused()) {
            addRenderableWidget(new PanelButton(panelX + PAD, y, 140, ROW_H, Component.literal("Resume live upload"), () -> {
                AutoUploads.resumeLive();
                switchTab(Tab.UPLOADS);
            }));
            y += ROW_H + ROW_GAP;
        }
        uploadsContentTop = y + 4;
    }

    private void autoToggle(AutoUploads.Kind kind, boolean on) {
        if (on) {
            AutoUploads.requestEnable(kind, this, () -> switchTab(Tab.UPLOADS));
        } else {
            AutoUploads.disable(kind);
            switchTab(Tab.UPLOADS);
        }
    }

    private void renderUploads(GuiGraphicsExtractor context) {
        UploadTracker tracker = ArgusUploaderClientMod.activeUpload();
        int y = uploadsContentTop;
        context.text(this.font, "Live upload: " + AutoUploads.liveStatus(), panelX + PAD, y, MUTED, true);
        y += 14;
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
        int uploadingCount = 0;
        for (UploadTracker.RowState row : rows) {
            switch (row.status()) {
                case DONE -> done++;
                case FAILED -> failed++;
                case QUEUED -> queued++;
                case UPLOADING -> {
                    uploadingCount++;
                    uploadingName = row.region().filename();
                }
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
            context.text(this.font, "Uploading: " + uploadingName
                    + (uploadingCount > 1 ? " (+" + (uploadingCount - 1) + " more in flight)" : ""), panelX + PAD, y, ACCENT, true);
        } else if (total > 0 && resolved == total) {
            context.text(this.font, "Run finished.", panelX + PAD, y, ACCENT, true);
        }
    }

    // ---------------------------------------------------------------- Auto-map

    private static final String[] AREA_FIELDS = {"", "", "", ""};
    private static String calibrationSpeeds = "";

    private int autoMapStatusY;
    private String autoMapError = "";

    private void buildAutoMapTab(int top) {
        ArgusConfig cfg = ArgusUploaderClientMod.config();
        int gap = 6;
        int inner = panelW - PAD * 2;
        int y = top;
        autoMapStatusY = y;
        y += 28;

        label("Area to map, in regions (min X, min Z, max X, max Z)", panelX + PAD, y, MUTED);
        y += 10;
        int fieldW = (inner - gap * 3) / 4;
        for (int i = 0; i < 4; i++) {
            int index = i;
            EditBox field = new EditBox(this.font, panelX + PAD + i * (fieldW + gap), y, fieldW, ROW_H, Component.empty());
            field.setMaxLength(8);
            field.setValue(AREA_FIELDS[i]);
            field.setResponder(s -> AREA_FIELDS[index] = s);
            addRenderableWidget(field);
        }
        y += ROW_H + ROW_GAP;

        int buttonW = (inner - gap * 2) / 3;
        addRenderableWidget(new PanelButton(panelX + PAD, y, buttonW, ROW_H, Component.literal("Use my region"), this::fillAreaFromPlayer));
        addRenderableWidget(new PanelButton(panelX + PAD + buttonW + gap, y, buttonW, ROW_H, Component.literal("Start auto-map"), this::startAutoMap)
                .colors(0xFF362A5E, 0xFFB79CFF));
        addRenderableWidget(new PanelButton(panelX + PAD + (buttonW + gap) * 2, y, buttonW, ROW_H, Component.literal("Stop"),
                () -> AutoMapper.stop("stopped from the GUI")));
        y += ROW_H + ROW_GAP + 4;

        label("Calibration flight speeds, blocks/tick (blank = 1.0 to 5.99)", panelX + PAD, y, MUTED);
        y += 10;
        int runW = 110;
        EditBox speeds = new EditBox(this.font, panelX + PAD, y, inner - runW - gap, ROW_H, Component.empty());
        speeds.setMaxLength(80);
        speeds.setValue(calibrationSpeeds);
        speeds.setResponder(s -> calibrationSpeeds = s);
        addRenderableWidget(speeds);
        addRenderableWidget(new PanelButton(panelX + panelW - PAD - runW, y, runW, ROW_H, Component.literal("Run calibration"), this::startCalibration));
        y += ROW_H + ROW_GAP + 6;

        int cellW = (inner - gap) / 2;
        int rightX = panelX + PAD + cellW + gap;
        doubleField(panelX + PAD, y, cellW, "Pinned speed (0 = pick the best)", cfg.autoMapSpeed, v -> cfg.autoMapSpeed = v);
        doubleField(rightX, y, cellW, "Slowest speed", cfg.autoMapMinSpeed, v -> cfg.autoMapMinSpeed = v);
        y += 10 + ROW_H + ROW_GAP;
        doubleField(panelX + PAD, y, cellW, "Fastest speed", cfg.autoMapMaxSpeed, v -> cfg.autoMapMaxSpeed = v);
        label("Cruise altitude", rightX, y, MUTED);
        addRenderableWidget(new LabeledSlider(rightX, y + 10, cellW, ROW_H, 100, 600, 5, cfg.autoMapCruiseY,
                v -> "Y " + v, v -> cfg.autoMapCruiseY = v));
        y += 10 + ROW_H + ROW_GAP;
        label("Lane half-width (0 = from the table)", panelX + PAD, y, MUTED);
        addRenderableWidget(new LabeledSlider(panelX + PAD, y + 10, cellW, ROW_H, 0, 12, 1, cfg.autoMapHalfWidthChunks,
                v -> v == 0 ? "automatic" : v + " chunks", v -> cfg.autoMapHalfWidthChunks = v));
        label("Width table (speed:chunks, blank = built in)", rightX, y, MUTED);
        EditBox table = new EditBox(this.font, rightX, y + 10, cellW, ROW_H, Component.empty());
        table.setMaxLength(160);
        table.setValue(cfg.autoMapWidthTable);
        table.setResponder(s -> cfg.autoMapWidthTable = s);
        addRenderableWidget(table);

        int saveY = panelY + panelH - PAD - ROW_H;
        addRenderableWidget(saveButton(panelX + PAD, saveY, cfg));
        label("Or right-click a box on Xaero's map.", panelX + PAD + 100, saveY + (ROW_H - 8) / 2, MUTED);
    }

    private void doubleField(int x, int y, int w, String text, double initial, java.util.function.DoubleConsumer onChange) {
        label(text, x, y, MUTED);
        EditBox field = new EditBox(this.font, x, y + 10, w, ROW_H, Component.empty());
        field.setMaxLength(8);
        field.setValue(initial == Math.rint(initial) ? Integer.toString((int) initial) : Double.toString(initial));
        field.setResponder(s -> {
            try {
                double value = Double.parseDouble(s.trim());
                if (value >= 0) {
                    onChange.accept(value);
                }
            } catch (NumberFormatException ignored) {
                // keeps the last valid value until the text parses
            }
        });
        addRenderableWidget(field);
    }

    private void fillAreaFromPlayer() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        String regionX = Integer.toString(client.player.getBlockX() >> 9);
        String regionZ = Integer.toString(client.player.getBlockZ() >> 9);
        AREA_FIELDS[0] = regionX;
        AREA_FIELDS[1] = regionZ;
        AREA_FIELDS[2] = regionX;
        AREA_FIELDS[3] = regionZ;
        autoMapError = "";
        switchTab(Tab.AUTO_MAP);
    }

    private void startAutoMap() {
        try {
            int[] r = new int[4];
            for (int i = 0; i < 4; i++) {
                r[i] = Integer.parseInt(AREA_FIELDS[i].trim());
            }
            var problem = AutoMapper.readinessProblem();
            if (problem.isPresent()) {
                autoMapError = problem.get();
                return;
            }
            autoMapError = "";
            AutoMapper.requestStartFromCommand(ChunkBox.ofRegions(r[0], r[1], r[2], r[3]));
        } catch (NumberFormatException e) {
            autoMapError = "Enter four whole numbers for the area.";
        }
    }

    private void startCalibration() {
        var problem = AutoMapper.readinessProblem();
        if (problem.isPresent()) {
            autoMapError = problem.get();
            return;
        }
        autoMapError = "";
        AutoMapper.requestCalibrationFromCommand(calibrationSpeeds);
    }

    /** Drawn every frame, like the Uploads progress, so the status follows a run in the background. */
    private void renderAutoMap(GuiGraphicsExtractor context) {
        int y = autoMapStatusY;
        context.text(this.font, "Auto-map: " + AutoMapper.status(), panelX + PAD, y, TITLE_COLOR, true);
        var problem = AutoMapper.readinessProblem();
        if (!autoMapError.isEmpty()) {
            context.text(this.font, autoMapError, panelX + PAD, y + 12, 0xFFFF8080, true);
        } else if (problem.isPresent()) {
            context.text(this.font, "Not ready: " + problem.get(), panelX + PAD, y + 12, 0xFFFFB86B, true);
        } else {
            context.text(this.font, "Ready: Elytra Fly is on and you're gliding.", panelX + PAD, y + 12, ACCENT, true);
        }
    }

    // ---------------------------------------------------------------- Bounty

    private int bountyStatusY;
    private String bountyMessage = "";

    private void buildBountyTab(int top) {
        ArgusConfig cfg = ArgusUploaderClientMod.config();
        int gap = 6;
        int inner = panelW - PAD * 2;
        int y = top;
        bountyStatusY = y;
        y += 52;

        y = toggleRow(y, "Mark bounty regions on the Xaero map", cfg.bountyEnabled, Bounty::setEnabled);
        y = sliderRow(y, "Regions to mark", 10, 100, 5, cfg.bountyLimit, v -> Integer.toString(v), v -> cfg.bountyLimit = v);

        int buttonW = (inner - gap) / 2;
        addRenderableWidget(new PanelButton(panelX + PAD, y, buttonW, ROW_H, Component.literal("Refresh now"), () -> Bounty.refreshNow()));
        addRenderableWidget(new PanelButton(panelX + PAD + buttonW + gap, y, buttonW, ROW_H, Component.literal("Clear markers"), Bounty::clearMarkers));
        y += ROW_H + ROW_GAP;
        addRenderableWidget(new PanelButton(panelX + PAD, y, buttonW, ROW_H, Component.literal("Map the 2x cell"), () -> bountyFlight("2x"))
                .colors(0xFF362A5E, 0xFFB79CFF));
        addRenderableWidget(new PanelButton(panelX + PAD + buttonW + gap, y, buttonW, ROW_H, Component.literal("Map the nearest"), () -> bountyFlight("nearest"))
                .colors(0xFF362A5E, 0xFFB79CFF));

        addRenderableWidget(saveButton(panelX + PAD, panelY + panelH - PAD - ROW_H, cfg));
    }

    private void bountyFlight(String which) {
        var problem = AutoMapper.readinessProblem();
        String noBounty = Bounty.requestFlight(which);
        bountyMessage = noBounty != null ? noBounty : problem.map(p -> "Not ready: " + p).orElse("");
    }

    private void renderBounty(GuiGraphicsExtractor context) {
        int y = bountyStatusY;
        int width = panelW - PAD * 2;
        context.text(this.font, this.font.plainSubstrByWidth("Bounty: " + Bounty.statusLine(), width),
                panelX + PAD, y, TITLE_COLOR, true);
        context.text(this.font, this.font.plainSubstrByWidth(
                "Temporary waypoints on Xaero's World Map.", width), panelX + PAD, y + 14, MUTED, true);
        context.text(this.font, this.font.plainSubstrByWidth(
                "Fetches a public list about every 2 min; sends no token.", width), panelX + PAD, y + 26, MUTED, true);
        if (!bountyMessage.isEmpty()) {
            context.text(this.font, this.font.plainSubstrByWidth(bountyMessage, width),
                    panelX + PAD, y + 38, 0xFFFFB86B, true);
        }
    }

    // ---------------------------------------------------------------- Waystones

    private int waystoneStatusY;
    private int waystonePage;
    private int waystoneBuiltVersion = -1;
    private int waystoneBotLabelY;
    private EditBox waystoneBotField;

    private void buildWaystonesTab(int top) {
        Waystones.touchTab();
        Minecraft mc = Minecraft.getInstance();
        int gap = 6;
        int y = top;
        waystoneStatusY = y;
        y += 40;

        y = toggleRow(y, "Show waystones on the Xaero map", Waystones.markersEnabled(), Waystones::setMarkersEnabled);

        waystoneBotLabelY = y;
        y += 10;
        int resetW = 50;
        waystoneBotField = new EditBox(this.font, panelX + PAD, y, panelW - PAD * 2 - gap - resetW, ROW_H, Component.empty());
        waystoneBotField.setMaxLength(16);
        waystoneBotField.setValue(Waystones.manualBotName());
        waystoneBotField.setResponder(Waystones::setManualBotName);
        addRenderableWidget(waystoneBotField);
        addRenderableWidget(new PanelButton(panelX + panelW - PAD - resetW, y, resetW, ROW_H, Component.literal("Reset"), () -> {
            waystoneBotField.setValue("");
            Waystones.resetManualBotName();
        }));
        y += ROW_H + ROW_GAP;

        List<Waystone> all = Waystones.sortedFor(mc);
        int rowStep = ROW_H + ROW_GAP + 2;
        int pagerY = panelY + panelH - PAD - ROW_H;
        int perPage = Math.max(1, (pagerY - 4 - y) / rowStep);
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        waystonePage = Math.max(0, Math.min(waystonePage, pages - 1));
        if (all.isEmpty()) {
            label("No waystones yet - they load when this tab opens.", panelX + PAD, y + 4, MUTED);
        }
        for (Waystone waystone : all.subList(waystonePage * perPage, Math.min(all.size(), (waystonePage + 1) * perPage))) {
            double distance = Waystones.distanceTo(mc, waystone);
            label(this.font.plainSubstrByWidth(waystone.displayName(), panelW - PAD * 2 - 76), panelX + PAD, y, TITLE_COLOR);
            label(waystone.dimensionLabel() + "  " + waystone.x() + ", " + waystone.z()
                    + (distance >= 0 ? "  -  " + Math.round(distance) + " blocks" : ""), panelX + PAD, y + 10, MUTED);
            addRenderableWidget(new PanelButton(panelX + panelW - PAD - 68, y, 68, ROW_H, Component.literal("Teleport"),
                    () -> Waystones.requestTeleport(waystone, this)).colors(0xFF362A5E, 0xFFB79CFF));
            y += rowStep;
        }

        addRenderableWidget(new PanelButton(panelX + PAD, pagerY, 24, ROW_H, Component.literal("<"), () -> {
            waystonePage = Math.max(0, waystonePage - 1);
            switchTab(Tab.WAYSTONES);
        }));
        label((waystonePage + 1) + " / " + pages, panelX + PAD + 32, pagerY + (ROW_H - 8) / 2, MUTED);
        addRenderableWidget(new PanelButton(panelX + PAD + 70, pagerY, 24, ROW_H, Component.literal(">"), () -> {
            waystonePage = Math.min(pages - 1, waystonePage + 1);
            switchTab(Tab.WAYSTONES);
        }));
        addRenderableWidget(new PanelButton(panelX + panelW - PAD - 90, pagerY, 90, ROW_H, Component.literal("Refresh"), Waystones::refreshNow));
        waystoneBuiltVersion = Waystones.version();
    }

    private void renderWaystones(GuiGraphicsExtractor context) {
        Waystones.touchTab();
        if (Waystones.version() != waystoneBuiltVersion) {
            switchTab(Tab.WAYSTONES);
        }
        int y = waystoneStatusY;
        int width = panelW - PAD * 2;
        if (waystoneBotField != null) {
            waystoneBotField.setSuggestion(waystoneBotField.getValue().isEmpty() ? Waystones.botSuggestion() : "");
        }
        context.text(this.font, this.font.plainSubstrByWidth(Waystones.botNameLine(), width),
                panelX + PAD, waystoneBotLabelY, MUTED, true);
        context.text(this.font, this.font.plainSubstrByWidth(Waystones.accountLine(), width),
                panelX + PAD, y, TITLE_COLOR, true);
        context.text(this.font, this.font.plainSubstrByWidth(Waystones.botLine(), width),
                panelX + PAD, y + 12, MUTED, true);
        String third = !Waystones.flowLine().isEmpty() ? Waystones.flowLine()
                : !Waystones.markersLine().isEmpty() && Waystones.markersEnabled() ? "Map: " + Waystones.markersLine()
                : Waystones.listLine();
        context.text(this.font, this.font.plainSubstrByWidth(third, width),
                panelX + PAD, y + 24, Waystones.flowLine().isEmpty() ? MUTED : ACCENT, true);
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
        if (currentTab == Tab.AUTO_MAP) {
            renderAutoMap(context);
        }
        if (currentTab == Tab.BOUNTY) {
            renderBounty(context);
        }
        if (currentTab == Tab.WAYSTONES) {
            renderWaystones(context);
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

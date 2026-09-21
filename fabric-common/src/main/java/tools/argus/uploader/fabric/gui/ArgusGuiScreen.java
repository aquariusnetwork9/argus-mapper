package tools.argus.uploader.fabric.gui;

import com.aquariusnetwork.highwayconditions.HighwayConditionsConfig;
import com.aquariusnetwork.highwayconditions.HighwayConditionsFabricClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.BlackZone;
import tools.argus.uploader.core.DiscordWebhookClient;
import tools.argus.uploader.core.MapperStats;
import tools.argus.uploader.core.ServerProfile;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.core.UploadTracker;
import tools.argus.uploader.fabric.ArgusUploaderClientMod;
import tools.argus.uploader.core.automap.ChunkBox;
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
 * The GUI replacement for the {@code /argus} and {@code /ard} chat commands - "Nightwire" skin
 * (dark panel, violet accent), chosen from three design concepts. Reads and writes the real
 * {@link ArgusConfig} / server registry / ARD config directly; there is no separate GUI-only
 * state to fall out of sync with the chat commands.
 *
 * <p>Known v1 limitations: no scrolling (each tab's content is sized to fit without it - see
 * individual build*Tab() methods if a tab grows past that), and sliders keep vanilla's own
 * groove/handle texture rather than a fully custom one (see {@link LabeledSlider}).
 */
public final class ArgusGuiScreen extends Screen {

    private static final String[] TAB_NAMES = {"General", "Token", "Servers", "Blackzones", "Stats", "Uploads", "Auto-map", "Bounty", "Waystones", "Road Dept.", "API"};
    private static final int MIN_PANEL_W = 300;
    private static final int HEADER_H = 46;
    private static final int PAD = 12;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 4;

    // Mirrors buildGeneralTab()'s own sequence of y-increments exactly (it's the tallest tab: 2
    // fields, 3 toggles, 2 sliders) so the default panel height is derived from the real row math
    // instead of a guessed constant - update this alongside that method if its rows ever change.
    // Reported live: the old fixed 268 put the Save/Reload row on top of the last slider.
    private static final int GENERAL_TAB_CONTENT_H =
            (10 + ROW_H + ROW_GAP + 4)     // API base URL field
                    + (10 + ROW_H + ROW_GAP + 6)   // Xaero folder override field
                    + (ROW_H + ROW_GAP)            // toggle: include caves
                    + (ROW_H + ROW_GAP)            // toggle: restrict nether
                    + (ROW_H + ROW_GAP)            // toggle: re-upload changed regions
                    + 4
                    + (10 + ROW_H + ROW_GAP)       // slider: pace between uploads
                    + (10 + ROW_H + ROW_GAP);      // slider: regions per batch
    private static final int PANEL_H_DEFAULT = HEADER_H + PAD + GENERAL_TAB_CONTENT_H + PAD + ROW_H + PAD;

    // status (2 lines), area label + fields, buttons, calibration label + row, 3 rows of settings
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

    private enum Tab { GENERAL, TOKEN, SERVERS, BLACKZONES, STATS, UPLOADS, AUTO_MAP, BOUNTY, WAYSTONES, ROAD_DEPT, API }

    private Tab currentTab = Tab.GENERAL;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private final List<Label> labels = new ArrayList<>();

    private TextFieldWidget tokenField;
    private boolean tokenRevealed = false;
    private TextFieldWidget webhookField;
    private boolean webhookRevealed = false;

    private record Label(String text, int x, int y, int color) {
    }

    public ArgusGuiScreen() {
        super(Text.literal("ARGUS Mapper"));
    }

    @Override
    protected void init() {
        // Recomputed on every init(), which vanilla's Screen.resize() re-invokes on a window
        // resize/GUI-scale change - so the panel reflows to the new window size and to however
        // wide the tab bar actually measures, rather than the old hardcoded panelW guess that
        // clipped the last tab or two whenever the real font metrics didn't match the guess.
        panelW = Math.max(MIN_PANEL_W, tabBarWidth() + PAD * 2);
        panelW = Math.min(panelW, Math.max(MIN_PANEL_W, width - PAD * 2));
        panelH = Math.min(PANEL_H_DEFAULT, Math.max(HEADER_H + ROW_H + PAD * 2, height - PAD * 2));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        buildTab(currentTab);
    }

    /** Sum of every tab button's width plus the 10px gap between them - the same math
     *  {@link #addTabBar} lays them out with, kept in one place so the panel is always sized to
     *  fit the tab bar it actually renders instead of a hand-guessed constant. */
    private int tabBarWidth() {
        int total = 0;
        for (String name : TAB_NAMES) {
            total += 8 + this.textRenderer.getWidth(name) + 10;
        }
        return total - 10;
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        this.clearChildren();
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
            case ROAD_DEPT -> buildRoadDeptTab(contentTop);
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
            int w = 8 + this.textRenderer.getWidth(TAB_NAMES[i]);
            PanelButton tabBtn = new PanelButton(x, y, w, 16, Text.literal(TAB_NAMES[i]), () -> switchTab(t))
                    .border(0)
                    .colors(t == currentTab ? 0x00000000 : 0x00000000, t == currentTab ? ACCENT : MUTED);
            addDrawableChild(tabBtn);
            x += w + 10;
        }
    }

    // ---------------------------------------------------------------- General

    private void buildGeneralTab(int top) {
        ArgusConfig cfg = ArgusUploaderClientMod.config();
        int y = top;

        label("API base URL (read-only)", panelX + PAD, y, MUTED);
        y += 10;
        // A plain label, not a TextFieldWidget - a fixed-width text field showing a
        // longer-than-it-looks URL from position 0 with no visual cue that it's cut off (no
        // ellipsis, no scrollbar) silently hid the back half of this value - reported live
        // ("reads .../api/part" instead of the real .../api/partner/upload) even though the
        // actual config value on disk was always correct. A label never clips (it just draws
        // past the panel edge on an unusually narrow window, which is still fully readable,
        // unlike silent truncation) and matches how every other read-only value in this GUI is
        // already shown.
        label(cfg.apiBaseUrl, panelX + PAD, y, TITLE_COLOR);
        y += ROW_H + ROW_GAP + 4;

        label("Xaero folder override (blank = auto-detect)", panelX + PAD, y, MUTED);
        y += 10;
        TextFieldWidget xaeroField = new TextFieldWidget(this.textRenderer, panelX + PAD, y, panelW - PAD * 2, ROW_H, Text.empty());
        xaeroField.setMaxLength(256);
        xaeroField.setText(cfg.xaeroRootOverride);
        xaeroField.setChangedListener(s -> cfg.xaeroRootOverride = s);
        addDrawableChild(xaeroField);
        y += ROW_H + ROW_GAP + 6;

        y = toggleRow(y, "Include cave regions", cfg.includeCaves, v -> cfg.includeCaves = v);
        y = toggleRow(y, "Restrict nether uploads to ARD highways", cfg.restrictNetherToHighways, v -> cfg.restrictNetherToHighways = v);
        y = toggleRow(y, "Re-upload regions that changed", cfg.reuploadChangedRegions, v -> cfg.reuploadChangedRegions = v);
        y += 4;

        y = sliderRow(y, "Uploads at once", 1, ArgusConfig.MAX_UPLOAD_CONCURRENCY, 1, cfg.uploadConcurrency,
                v -> Integer.toString(v), v -> cfg.uploadConcurrency = v);
        y = sliderRow(y, "Regions per batch", 10, 200, 10, cfg.maxPerBatch, v -> Integer.toString(v), v -> cfg.maxPerBatch = v);

        int saveY = panelY + panelH - PAD - ROW_H;
        addDrawableChild(saveButton(panelX + PAD, saveY, cfg));
        addDrawableChild(new PanelButton(panelX + PAD + 100, saveY, 90, ROW_H, Text.literal("Reload"), () -> {
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
        tokenField = new TextFieldWidget(this.textRenderer, panelX + PAD, y, panelW - PAD * 2 - 24, ROW_H, Text.empty());
        tokenField.setMaxLength(256);
        tokenField.setText(cfg.token);
        tokenField.setEditable(tokenRevealed);
        tokenField.setChangedListener(s -> cfg.token = s);
        addDrawableChild(tokenField);
        addDrawableChild(new PanelButton(panelX + panelW - PAD - 20, y, 20, ROW_H, Text.literal(tokenRevealed ? "-" : "o"),
                () -> {
                    tokenRevealed = !tokenRevealed;
                    switchTab(Tab.TOKEN);
                }));
        y += ROW_H + 2;
        label("Also settable with /argus settoken <token>", panelX + PAD, y, MUTED);
        y += ROW_H;

        label("Discord webhook URL", panelX + PAD, y, MUTED);
        y += 10;
        webhookField = new TextFieldWidget(this.textRenderer, panelX + PAD, y, panelW - PAD * 2 - 24, ROW_H, Text.empty());
        webhookField.setMaxLength(256);
        webhookField.setText(cfg.discordWebhookUrl);
        webhookField.setEditable(webhookRevealed);
        webhookField.setChangedListener(s -> cfg.discordWebhookUrl = s);
        addDrawableChild(webhookField);
        addDrawableChild(new PanelButton(panelX + panelW - PAD - 20, y, 20, ROW_H, Text.literal(webhookRevealed ? "-" : "o"),
                () -> {
                    webhookRevealed = !webhookRevealed;
                    switchTab(Tab.TOKEN);
                }));
        y += ROW_H + 6;
        label("Stored only in this device's argus-mapper.properties.", panelX + PAD, y, MUTED);

        int saveY = panelY + panelH - PAD - ROW_H;
        addDrawableChild(saveButton(panelX + PAD, saveY, cfg));
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
                addDrawableChild(new PanelButton(panelX + panelW - PAD - 50, y, 50, ROW_H, Text.literal("Use"), () -> {
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
            addDrawableChild(new PanelButton(panelX + panelW - PAD - 60, y, 60, ROW_H, Text.literal("Remove"),
                    () -> BlackzoneRemoval.confirmAndRemove(this, List.of(zone), () -> switchTab(Tab.BLACKZONES))));
            y += ROW_H + ROW_GAP + 2;
        }

        if (pages > 1) {
            int pagerY = panelY + panelH - PAD - ROW_H;
            addDrawableChild(new PanelButton(panelX + PAD, pagerY, 24, ROW_H, Text.literal("<"), () -> {
                blackzonePage = Math.max(0, blackzonePage - 1);
                switchTab(Tab.BLACKZONES);
            }));
            label((blackzonePage + 1) + " / " + pages, panelX + PAD + 32, pagerY + (ROW_H - 8) / 2, MUTED);
            addDrawableChild(new PanelButton(panelX + PAD + 70, pagerY, 24, ROW_H, Text.literal(">"), () -> {
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

        addDrawableChild(new PanelButton(panelX + PAD, y, 130, ROW_H, Text.literal("Send test report"), () -> {
            // fires the same code path /argus discord test uses; result appears in chat
            new Thread(() -> {
                var result = new DiscordWebhookClient().postEmbed(
                        cfg.discordWebhookUrl, "ARGUS Mapper", "Test message from the GUI.", List.of());
                var mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    mc.execute(() -> mc.player.sendMessage(Text.literal("[ARGUS] "
                            + (result.success() ? "Test message sent." : "Test failed.")), false));
                }
            }, "argus-gui-discord-test").start();
        }));

        int saveY = panelY + panelH - PAD - ROW_H;
        addDrawableChild(saveButton(panelX + PAD, saveY, cfg));
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

    /** Adds the per-session switches, then remembers where the live-drawn part starts - unlike the
     *  other tabs, the run progress below them is drawn every frame from {@link #renderUploads}
     *  (see its javadoc for why) rather than baked into the static labels/tileRects lists. */
    private void buildUploadsTab(int top) {
        int y = top;
        y = toggleRow(y, "Live upload (this session only)", AutoUploads.isLive(), on -> autoToggle(AutoUploads.Kind.LIVE, on));
        y = toggleRow(y, "Whole-map upload (this session only)", AutoUploads.isWholeMap(), on -> autoToggle(AutoUploads.Kind.WHOLE_MAP, on));
        if (AutoUploads.isLive() && AutoUploads.isPaused()) {
            addDrawableChild(new PanelButton(panelX + PAD, y, 140, ROW_H, Text.literal("Resume live upload"), () -> {
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

    /**
     * Recomputed every frame (called from {@link #render}, not {@link #buildTab}) so the bar
     * actually moves while an upload started via {@code /argus upload} keeps running in the
     * background - see {@link tools.argus.uploader.core.UploadRunner}'s own javadoc for why that
     * background run survives this screen opening and closing. Aggregate bar plus one live detail
     * line for the in-flight (or most recently resolved) region, not a full per-file row list -
     * region files are capped at {@link ArgusConfig#maxFileSizeBytes} but aren't guaranteed to
     * finish quickly (a slow/flaky connection can make a single region take a while - confirmed
     * live on a real server, see MANUAL_TEST_PLAN.md scenario 7), so that one line shows size and
     * elapsed/took time rather than assuming near-instant completion the way this tab originally
     * did.
     */
    private void renderUploads(DrawContext context) {
        UploadTracker tracker = ArgusUploaderClientMod.activeUpload();
        int y = uploadsContentTop;
        context.drawTextWithShadow(this.textRenderer, "Live upload: " + AutoUploads.liveStatus(), panelX + PAD, y, MUTED);
        y += 14;
        if (tracker == null) {
            context.drawTextWithShadow(this.textRenderer, "No upload has run yet this session.", panelX + PAD, y, MUTED);
            context.drawTextWithShadow(this.textRenderer, "Run /argus scan, then /argus upload to start one.", panelX + PAD, y + 12, MUTED);
            return;
        }

        List<UploadTracker.RowState> rows = tracker.rows();
        int total = rows.size();
        int done = 0;
        int failed = 0;
        int queued = 0;
        UploadTracker.RowState uploading = null;
        int uploadingCount = 0;
        UploadTracker.RowState lastResolved = null;
        for (UploadTracker.RowState row : rows) {
            switch (row.status()) {
                case DONE -> done++;
                case FAILED -> failed++;
                case QUEUED -> queued++;
                case UPLOADING -> {
                    uploadingCount++;
                    if (uploading == null || row.startedAtMillis() < uploading.startedAtMillis()) {
                        uploading = row;
                    }
                }
            }
            if ((row.status() == UploadTracker.Status.DONE || row.status() == UploadTracker.Status.FAILED)
                    && (lastResolved == null || row.finishedAtMillis() > lastResolved.finishedAtMillis())) {
                lastResolved = row;
            }
        }
        int resolved = done + failed;

        context.drawTextWithShadow(this.textRenderer, "Upload progress", panelX + PAD, y, TITLE_COLOR);
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
        context.drawCenteredTextWithShadow(this.textRenderer, resolved + " / " + total,
                barX + barW / 2, y + (barH - 8) / 2, TITLE_COLOR);
        y += barH + 8;

        context.drawTextWithShadow(this.textRenderer,
                "Queued: " + queued + "   Done: " + done + "   Failed: " + failed, panelX + PAD, y, MUTED);
        y += 12;
        if (tracker.excludedByBlackzone() > 0) {
            context.drawTextWithShadow(this.textRenderer, "Excluded by blackzone: " + tracker.excludedByBlackzone(),
                    panelX + PAD, y, MUTED);
            y += 12;
        }
        if (uploading != null) {
            context.drawTextWithShadow(this.textRenderer, "Uploading: " + uploading.region().filename()
                    + " (" + formatBytes(uploading.region().sizeBytes()) + ") - "
                    + formatSeconds(uploading.elapsedMillis()) + " elapsed"
                    + (uploadingCount > 1 ? " (+" + (uploadingCount - 1) + " more in flight)" : ""), panelX + PAD, y, ACCENT);
        } else if (total > 0 && resolved == total) {
            context.drawTextWithShadow(this.textRenderer, "Run finished.", panelX + PAD, y, ACCENT);
        }
        if (lastResolved != null) {
            y += 12;
            String verb = lastResolved.status() == UploadTracker.Status.DONE ? "Done" : "Failed";
            String line = "Last: " + lastResolved.region().filename() + " (" + formatBytes(lastResolved.region().sizeBytes())
                    + ") - " + verb.toLowerCase(java.util.Locale.ROOT) + " in " + formatSeconds(lastResolved.elapsedMillis());
            context.drawTextWithShadow(this.textRenderer, line, panelX + PAD, y,
                    lastResolved.status() == UploadTracker.Status.DONE ? MUTED : 0xFFFF8080);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
        }
        if (bytes >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1_000.0);
        }
        return bytes + " B";
    }

    private static String formatSeconds(long millis) {
        return String.format(java.util.Locale.ROOT, "%.1fs", millis / 1000.0);
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
            TextFieldWidget field = new TextFieldWidget(this.textRenderer, panelX + PAD + i * (fieldW + gap), y, fieldW, ROW_H, Text.empty());
            field.setMaxLength(8);
            field.setText(AREA_FIELDS[i]);
            field.setChangedListener(s -> AREA_FIELDS[index] = s);
            addDrawableChild(field);
        }
        y += ROW_H + ROW_GAP;

        int buttonW = (inner - gap * 2) / 3;
        addDrawableChild(new PanelButton(panelX + PAD, y, buttonW, ROW_H, Text.literal("Use my region"), this::fillAreaFromPlayer));
        addDrawableChild(new PanelButton(panelX + PAD + buttonW + gap, y, buttonW, ROW_H, Text.literal("Start auto-map"), this::startAutoMap)
                .colors(0xFF362A5E, 0xFFB79CFF));
        addDrawableChild(new PanelButton(panelX + PAD + (buttonW + gap) * 2, y, buttonW, ROW_H, Text.literal("Stop"),
                () -> AutoMapper.stop("stopped from the GUI")));
        y += ROW_H + ROW_GAP + 4;

        label("Calibration flight speeds, blocks/tick (blank = 1.0 to 5.99)", panelX + PAD, y, MUTED);
        y += 10;
        int runW = 110;
        TextFieldWidget speeds = new TextFieldWidget(this.textRenderer, panelX + PAD, y, inner - runW - gap, ROW_H, Text.empty());
        speeds.setMaxLength(80);
        speeds.setText(calibrationSpeeds);
        speeds.setChangedListener(s -> calibrationSpeeds = s);
        addDrawableChild(speeds);
        addDrawableChild(new PanelButton(panelX + panelW - PAD - runW, y, runW, ROW_H, Text.literal("Run calibration"), this::startCalibration));
        y += ROW_H + ROW_GAP + 6;

        int cellW = (inner - gap) / 2;
        int rightX = panelX + PAD + cellW + gap;
        doubleField(panelX + PAD, y, cellW, "Pinned speed (0 = pick the best)", cfg.autoMapSpeed, v -> cfg.autoMapSpeed = v);
        doubleField(rightX, y, cellW, "Slowest speed", cfg.autoMapMinSpeed, v -> cfg.autoMapMinSpeed = v);
        y += 10 + ROW_H + ROW_GAP;
        doubleField(panelX + PAD, y, cellW, "Fastest speed", cfg.autoMapMaxSpeed, v -> cfg.autoMapMaxSpeed = v);
        label("Cruise altitude", rightX, y, MUTED);
        addDrawableChild(new LabeledSlider(rightX, y + 10, cellW, ROW_H, 100, 600, 5, cfg.autoMapCruiseY,
                v -> "Y " + v, v -> cfg.autoMapCruiseY = v));
        y += 10 + ROW_H + ROW_GAP;
        label("Lane half-width (0 = from the table)", panelX + PAD, y, MUTED);
        addDrawableChild(new LabeledSlider(panelX + PAD, y + 10, cellW, ROW_H, 0, 12, 1, cfg.autoMapHalfWidthChunks,
                v -> v == 0 ? "automatic" : v + " chunks", v -> cfg.autoMapHalfWidthChunks = v));
        label("Width table (speed:chunks, blank = built in)", rightX, y, MUTED);
        TextFieldWidget table = new TextFieldWidget(this.textRenderer, rightX, y + 10, cellW, ROW_H, Text.empty());
        table.setMaxLength(160);
        table.setText(cfg.autoMapWidthTable);
        table.setChangedListener(s -> cfg.autoMapWidthTable = s);
        addDrawableChild(table);

        int saveY = panelY + panelH - PAD - ROW_H;
        addDrawableChild(saveButton(panelX + PAD, saveY, cfg));
        label("Or right-click a box on Xaero's map.", panelX + PAD + 100, saveY + (ROW_H - 8) / 2, MUTED);
    }

    private void doubleField(int x, int y, int w, String text, double initial, java.util.function.DoubleConsumer onChange) {
        label(text, x, y, MUTED);
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y + 10, w, ROW_H, Text.empty());
        field.setMaxLength(8);
        field.setText(initial == Math.rint(initial) ? Integer.toString((int) initial) : Double.toString(initial));
        field.setChangedListener(s -> {
            try {
                double value = Double.parseDouble(s.trim());
                if (value >= 0) {
                    onChange.accept(value);
                }
            } catch (NumberFormatException ignored) {
                // keeps the last valid value until the text parses
            }
        });
        addDrawableChild(field);
    }

    private void fillAreaFromPlayer() {
        MinecraftClient client = MinecraftClient.getInstance();
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
    private void renderAutoMap(DrawContext context) {
        int y = autoMapStatusY;
        context.drawTextWithShadow(this.textRenderer, "Auto-map: " + AutoMapper.status(), panelX + PAD, y, TITLE_COLOR);
        var problem = AutoMapper.readinessProblem();
        if (!autoMapError.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, autoMapError, panelX + PAD, y + 12, 0xFFFF8080);
        } else if (problem.isPresent()) {
            context.drawTextWithShadow(this.textRenderer, "Not ready: " + problem.get(), panelX + PAD, y + 12, 0xFFFFB86B);
        } else {
            context.drawTextWithShadow(this.textRenderer, "Ready: Elytra Fly is on and you're gliding.", panelX + PAD, y + 12, ACCENT);
        }
    }

    // ---------------------------------------------------------------- Road Department (ARD)

    private void buildRoadDeptTab(int top) {
        HighwayConditionsConfig ard = HighwayConditionsFabricClient.config();
        int y = top;
        if (ard == null) {
            label("ARD hasn't finished loading yet - reopen this tab in a moment.", panelX + PAD, y, MUTED);
            return;
        }
        y = toggleRow(y, "Hazard-ahead HUD", ard.hud.enabled, v -> ard.hud.enabled = v);
        y = toggleRow(y, "Local hazard alert", ard.hud.localAlertEnabled, v -> ard.hud.localAlertEnabled = v);
        y = toggleRow(y, "Report submission", ard.reporter.enabled, v -> ard.reporter.enabled = v);
        y = toggleRow(y, "Presence (camper) reporting", ard.reporter.reportPresence, v -> ard.reporter.reportPresence = v);
        y = toggleRow(y, "Baritone auto-avoid", ard.baritone.autoAvoidEnabled, v -> ard.baritone.autoAvoidEnabled = v);
        y += 6;
        label("Account linking: use /ard link, then /ard token <value>.", panelX + PAD, y, MUTED);

        int saveY = panelY + panelH - PAD - ROW_H;
        addDrawableChild(new PanelButton(panelX + PAD, saveY, 100, ROW_H, Text.literal("Save"), ard::save)
                .colors(0xFF362A5E, 0xFFB79CFF));
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
        addDrawableChild(saveButton(panelX + PAD, saveY, cfg));
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
        addDrawableChild(new PanelButton(panelX + PAD, y, buttonW, ROW_H, Text.literal("Refresh now"), () -> Bounty.refreshNow()));
        addDrawableChild(new PanelButton(panelX + PAD + buttonW + gap, y, buttonW, ROW_H, Text.literal("Clear markers"), Bounty::clearMarkers));
        y += ROW_H + ROW_GAP;
        addDrawableChild(new PanelButton(panelX + PAD, y, buttonW, ROW_H, Text.literal("Map the 2x cell"), () -> bountyFlight("2x"))
                .colors(0xFF362A5E, 0xFFB79CFF));
        addDrawableChild(new PanelButton(panelX + PAD + buttonW + gap, y, buttonW, ROW_H, Text.literal("Map the nearest"), () -> bountyFlight("nearest"))
                .colors(0xFF362A5E, 0xFFB79CFF));

        addDrawableChild(saveButton(panelX + PAD, panelY + panelH - PAD - ROW_H, cfg));
    }

    private void bountyFlight(String which) {
        var problem = AutoMapper.readinessProblem();
        String noBounty = Bounty.requestFlight(which);
        bountyMessage = noBounty != null ? noBounty : problem.map(p -> "Not ready: " + p).orElse("");
    }

    private void renderBounty(DrawContext context) {
        int y = bountyStatusY;
        int width = panelW - PAD * 2;
        context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth("Bounty: " + Bounty.statusLine(), width),
                panelX + PAD, y, TITLE_COLOR);
        context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(
                "Temporary waypoints on Xaero's World Map.", width), panelX + PAD, y + 14, MUTED);
        context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(
                "Fetches a public list about every 2 min; sends no token.", width), panelX + PAD, y + 26, MUTED);
        if (!bountyMessage.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(bountyMessage, width),
                    panelX + PAD, y + 38, 0xFFFFB86B);
        }
    }

    // ---------------------------------------------------------------- Waystones

    private int waystoneStatusY;
    private int waystonePage;
    private int waystoneBuiltVersion = -1;
    private int waystoneBotLabelY;
    private TextFieldWidget waystoneBotField;

    private void buildWaystonesTab(int top) {
        Waystones.touchTab();
        MinecraftClient mc = MinecraftClient.getInstance();
        int gap = 6;
        int y = top;
        waystoneStatusY = y;
        y += 40;

        y = toggleRow(y, "Show waystones on the Xaero map", Waystones.markersEnabled(), Waystones::setMarkersEnabled);

        waystoneBotLabelY = y;
        y += 10;
        waystoneBotField = new TextFieldWidget(this.textRenderer, panelX + PAD, y, panelW - PAD * 2, ROW_H, Text.empty());
        waystoneBotField.setMaxLength(16);
        waystoneBotField.setTextPredicate(s -> s.matches("[A-Za-z0-9_]*"));
        waystoneBotField.setText(Waystones.manualBotName());
        waystoneBotField.setChangedListener(Waystones::setManualBotName);
        addDrawableChild(waystoneBotField);
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
            label(this.textRenderer.trimToWidth(waystone.displayName(), panelW - PAD * 2 - 76), panelX + PAD, y, TITLE_COLOR);
            label(waystone.dimensionLabel() + "  " + waystone.x() + ", " + waystone.z()
                    + (distance >= 0 ? "  -  " + Math.round(distance) + " blocks" : ""), panelX + PAD, y + 10, MUTED);
            addDrawableChild(new PanelButton(panelX + panelW - PAD - 68, y, 68, ROW_H, Text.literal("Teleport"),
                    () -> Waystones.requestTeleport(waystone, this)).colors(0xFF362A5E, 0xFFB79CFF));
            y += rowStep;
        }

        addDrawableChild(new PanelButton(panelX + PAD, pagerY, 24, ROW_H, Text.literal("<"), () -> {
            waystonePage = Math.max(0, waystonePage - 1);
            switchTab(Tab.WAYSTONES);
        }));
        label((waystonePage + 1) + " / " + pages, panelX + PAD + 32, pagerY + (ROW_H - 8) / 2, MUTED);
        addDrawableChild(new PanelButton(panelX + PAD + 70, pagerY, 24, ROW_H, Text.literal(">"), () -> {
            waystonePage = Math.min(pages - 1, waystonePage + 1);
            switchTab(Tab.WAYSTONES);
        }));
        addDrawableChild(new PanelButton(panelX + panelW - PAD - 90, pagerY, 90, ROW_H, Text.literal("Refresh"), Waystones::refreshNow));
        waystoneBuiltVersion = Waystones.version();
    }

    private void renderWaystones(DrawContext context) {
        Waystones.touchTab();
        if (Waystones.version() != waystoneBuiltVersion) {
            switchTab(Tab.WAYSTONES);
        }
        int y = waystoneStatusY;
        int width = panelW - PAD * 2;
        if (waystoneBotField != null) {
            waystoneBotField.setSuggestion(waystoneBotField.getText().isEmpty() ? Waystones.botSuggestion() : "");
        }
        context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(Waystones.botNameLine(), width),
                panelX + PAD, waystoneBotLabelY, MUTED);
        context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(Waystones.accountLine(), width),
                panelX + PAD, y, TITLE_COLOR);
        context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(Waystones.botLine(), width),
                panelX + PAD, y + 12, MUTED);
        String third = !Waystones.flowLine().isEmpty() ? Waystones.flowLine()
                : !Waystones.markersLine().isEmpty() && Waystones.markersEnabled() ? "Map: " + Waystones.markersLine()
                : Waystones.listLine();
        context.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(third, width),
                panelX + PAD, y + 24, Waystones.flowLine().isEmpty() ? MUTED : ACCENT);
    }

    // ---------------------------------------------------------------- shared helpers

    private int toggleRow(int y, String text, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        label(text, panelX + PAD, y + (ROW_H - 8) / 2, TITLE_COLOR);
        addDrawableChild(new ToggleButton(panelX + panelW - PAD - 40, y, 40, ROW_H, initial, onChange));
        return y + ROW_H + ROW_GAP;
    }

    private int sliderRow(int y, String text, int min, int max, int step, int initial,
                           java.util.function.IntFunction<String> labelFor, java.util.function.IntConsumer onChange) {
        label(text, panelX + PAD, y, MUTED);
        y += 10;
        addDrawableChild(new LabeledSlider(panelX + PAD, y, panelW - PAD * 2, ROW_H, min, max, step, initial, labelFor, onChange));
        return y + ROW_H + ROW_GAP;
    }

    private PanelButton saveButton(int x, int y, ArgusConfig cfg) {
        return new PanelButton(x, y, 90, ROW_H, Text.literal("Save"), () -> persist(cfg))
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, BG_DIM);
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        PanelButton.drawBorder(context, panelX, panelY, panelW, panelH, PANEL_BORDER);
        context.drawTextWithShadow(this.textRenderer, Text.literal("ARGUS Mapper"), panelX + PAD, panelY + 8, TITLE_COLOR);

        for (int[] r : tileRects) {
            context.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], FIELD_BG);
            PanelButton.drawBorder(context, r[0], r[1], r[2], r[3], PANEL_BORDER);
        }

        for (Label l : labels) {
            context.drawTextWithShadow(this.textRenderer, l.text(), l.x(), l.y(), l.color());
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

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

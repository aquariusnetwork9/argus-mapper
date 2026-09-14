package tools.argus.uploader.fabric.gui;

import com.aquariusnetwork.highwayconditions.HighwayConditionsConfig;
import com.aquariusnetwork.highwayconditions.HighwayConditionsFabricClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import tools.argus.uploader.core.ArgusConfig;
import tools.argus.uploader.core.DiscordWebhookClient;
import tools.argus.uploader.core.MapperStats;
import tools.argus.uploader.core.ServerProfile;
import tools.argus.uploader.core.UploadManifest;
import tools.argus.uploader.fabric.ArgusUploaderClientMod;

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

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 268;
    private static final int HEADER_H = 46;
    private static final int PAD = 12;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 4;

    private static final int BG_DIM = 0x88000000;
    private static final int PANEL_BG = 0xFF12131A;
    private static final int PANEL_BORDER = 0xFF23252F;
    private static final int TITLE_COLOR = 0xFFE6E8EF;
    private static final int MUTED = 0xFF7D8296;
    private static final int ACCENT = 0xFF8A6BFF;
    private static final int FIELD_BG = 0xFF1A1C25;

    private enum Tab { GENERAL, TOKEN, SERVERS, STATS, ROAD_DEPT, API }

    private Tab currentTab = Tab.GENERAL;
    private int panelX;
    private int panelY;

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
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
        buildTab(currentTab);
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        this.clearChildren();
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
            case ROAD_DEPT -> buildRoadDeptTab(contentTop);
            case API -> buildApiTab(contentTop);
        }
    }

    private void addTabBar() {
        String[] names = {"General", "Token", "Servers", "Stats", "Road Dept.", "API"};
        Tab[] values = Tab.values();
        int x = panelX + PAD;
        int y = panelY + 24;
        for (int i = 0; i < values.length; i++) {
            Tab t = values[i];
            int w = 8 + this.textRenderer.getWidth(names[i]);
            PanelButton tabBtn = new PanelButton(x, y, w, 16, Text.literal(names[i]), () -> switchTab(t))
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
        TextFieldWidget apiField = new TextFieldWidget(this.textRenderer, panelX + PAD, y, PANEL_W - PAD * 2, ROW_H, Text.empty());
        apiField.setText(cfg.apiBaseUrl);
        apiField.setEditable(false);
        addDrawableChild(apiField);
        y += ROW_H + ROW_GAP + 4;

        label("Xaero folder override (blank = auto-detect)", panelX + PAD, y, MUTED);
        y += 10;
        TextFieldWidget xaeroField = new TextFieldWidget(this.textRenderer, panelX + PAD, y, PANEL_W - PAD * 2, ROW_H, Text.empty());
        xaeroField.setMaxLength(256);
        xaeroField.setText(cfg.xaeroRootOverride);
        xaeroField.setChangedListener(s -> cfg.xaeroRootOverride = s);
        addDrawableChild(xaeroField);
        y += ROW_H + ROW_GAP + 6;

        y = toggleRow(y, "Include cave regions", cfg.includeCaves, v -> cfg.includeCaves = v);
        y = toggleRow(y, "Restrict nether uploads to ARD highways", cfg.restrictNetherToHighways, v -> cfg.restrictNetherToHighways = v);
        y += 4;

        y = sliderRow(y, "Pace between uploads", 1000, 10000, 500, cfg.paceMillis > Integer.MAX_VALUE ? 10000 : (int) cfg.paceMillis,
                v -> v + " ms", v -> cfg.paceMillis = v);
        y = sliderRow(y, "Regions per batch", 10, 200, 10, cfg.maxPerBatch, v -> Integer.toString(v), v -> cfg.maxPerBatch = v);

        int saveY = panelY + PANEL_H - PAD - ROW_H;
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
        tokenField = new TextFieldWidget(this.textRenderer, panelX + PAD, y, PANEL_W - PAD * 2 - 24, ROW_H, Text.empty());
        tokenField.setMaxLength(256);
        tokenField.setText(cfg.token);
        tokenField.setEditable(tokenRevealed);
        tokenField.setChangedListener(s -> cfg.token = s);
        addDrawableChild(tokenField);
        addDrawableChild(new PanelButton(panelX + PANEL_W - PAD - 20, y, 20, ROW_H, Text.literal(tokenRevealed ? "-" : "o"),
                () -> {
                    tokenRevealed = !tokenRevealed;
                    switchTab(Tab.TOKEN);
                }));
        y += ROW_H + 2;
        label("Also settable with /argus settoken <token>", panelX + PAD, y, MUTED);
        y += ROW_H;

        label("Discord webhook URL", panelX + PAD, y, MUTED);
        y += 10;
        webhookField = new TextFieldWidget(this.textRenderer, panelX + PAD, y, PANEL_W - PAD * 2 - 24, ROW_H, Text.empty());
        webhookField.setMaxLength(256);
        webhookField.setText(cfg.discordWebhookUrl);
        webhookField.setEditable(webhookRevealed);
        webhookField.setChangedListener(s -> cfg.discordWebhookUrl = s);
        addDrawableChild(webhookField);
        addDrawableChild(new PanelButton(panelX + PANEL_W - PAD - 20, y, 20, ROW_H, Text.literal(webhookRevealed ? "-" : "o"),
                () -> {
                    webhookRevealed = !webhookRevealed;
                    switchTab(Tab.TOKEN);
                }));
        y += ROW_H + 6;
        label("Stored only in this device's argus-mapper.properties.", panelX + PAD, y, MUTED);

        int saveY = panelY + PANEL_H - PAD - ROW_H;
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
                addDrawableChild(new PanelButton(panelX + PANEL_W - PAD - 50, y, 50, ROW_H, Text.literal("Use"), () -> {
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
        int tileW = (PANEL_W - PAD * 2 - 8 * 2) / 3;
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

        int saveY = panelY + PANEL_H - PAD - ROW_H;
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

        int saveY = panelY + PANEL_H - PAD - ROW_H;
        addDrawableChild(new PanelButton(panelX + PAD, saveY, 100, ROW_H, Text.literal("Save"), ard::save)
                .colors(0xFF362A5E, 0xFFB79CFF));
    }

    // ---------------------------------------------------------------- API

    private void buildApiTab(int top) {
        int y = top;
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
    }

    // ---------------------------------------------------------------- shared helpers

    private int toggleRow(int y, String text, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        label(text, panelX + PAD, y + (ROW_H - 8) / 2, TITLE_COLOR);
        addDrawableChild(new ToggleButton(panelX + PANEL_W - PAD - 40, y, 40, ROW_H, initial, onChange));
        return y + ROW_H + ROW_GAP;
    }

    private int sliderRow(int y, String text, int min, int max, int step, int initial,
                           java.util.function.IntFunction<String> labelFor, java.util.function.IntConsumer onChange) {
        label(text, panelX + PAD, y, MUTED);
        y += 10;
        addDrawableChild(new LabeledSlider(panelX + PAD, y, PANEL_W - PAD * 2, ROW_H, min, max, step, initial, labelFor, onChange));
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
        context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, PANEL_BG);
        PanelButton.drawBorder(context, panelX, panelY, PANEL_W, PANEL_H, PANEL_BORDER);
        context.drawTextWithShadow(this.textRenderer, Text.literal("ARGUS Mapper"), panelX + PAD, panelY + 8, TITLE_COLOR);

        for (int[] r : tileRects) {
            context.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], FIELD_BG);
            PanelButton.drawBorder(context, r[0], r[1], r[2], r[3], PANEL_BORDER);
        }

        for (Label l : labels) {
            context.drawTextWithShadow(this.textRenderer, l.text(), l.x(), l.y(), l.color());
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

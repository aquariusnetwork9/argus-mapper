package tools.argus.uploader.fabric.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tools.argus.uploader.core.TextWrap;

import java.util.List;
import java.util.function.Consumer;

/** A three-button popup (OK / Cancel / Modify) that can only be closed by picking one. */
public final class AutoBlackzoneScreen extends Screen {

    public enum Choice { OK, CANCEL, MODIFY }

    private static final int PANEL_WIDTH = 330;
    private static final int PAD = 12;
    private static final int LINE_HEIGHT = 11;
    private static final int BUTTON_WIDTH = 90;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 8;
    private static final int BG_DIM = 0x88000000;
    private static final int PANEL_BG = 0xFF12131A;
    private static final int TITLE_COLOR = 0xFFE6E8EF;
    private static final int BODY_COLOR = 0xFFB4B8C8;

    private final String title;
    private final String body;
    private final Consumer<Choice> onChoice;
    private List<String> lines = List.of();
    private int panelX;
    private int panelY;
    private int panelHeight;

    public AutoBlackzoneScreen(String title, String body, Consumer<Choice> onChoice) {
        super(Component.literal(title));
        this.title = title;
        this.body = body;
        this.onChoice = onChoice;
    }

    @Override
    protected void init() {
        lines = TextWrap.wrap(body, PANEL_WIDTH - PAD * 2, font::width);
        panelHeight = PAD + LINE_HEIGHT + 6 + lines.size() * LINE_HEIGHT + 10 + BUTTON_HEIGHT + PAD;
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = Math.max(4, (height - panelHeight) / 2);

        int buttonY = panelY + panelHeight - PAD - BUTTON_HEIGHT;
        int rowWidth = BUTTON_WIDTH * 3 + BUTTON_GAP * 2;
        int x = panelX + (PANEL_WIDTH - rowWidth) / 2;
        for (Choice choice : Choice.values()) {
            String label = switch (choice) {
                case OK -> "OK";
                case CANCEL -> "Cancel";
                case MODIFY -> "Modify";
            };
            addRenderableWidget(Button.builder(Component.literal(label), button -> onChoice.accept(choice))
                    .bounds(x, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
            x += BUTTON_WIDTH + BUTTON_GAP;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, BG_DIM);
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, PANEL_BG);
        context.text(font, title, panelX + PAD, panelY + PAD, TITLE_COLOR, true);
        int y = panelY + PAD + LINE_HEIGHT + 6;
        for (String line : lines) {
            context.text(font, line, panelX + PAD, y, BODY_COLOR, true);
            y += LINE_HEIGHT;
        }
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

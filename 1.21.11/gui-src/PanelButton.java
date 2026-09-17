package tools.argus.uploader.fabric.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.text.Text;

/**
 * 1.21.11's port of fabric-common's PanelButton - see this version's GuiLauncher javadoc for why
 * this module needs its own copy. On this version {@link PressableWidget#onPress()} became
 * {@code onPress(AbstractInput)}, and {@code renderWidget(DrawContext, int, int, float)} is gone
 * entirely: {@code drawButton(DrawContext)} exists but is {@code final} (confirmed by the real
 * compiler, not just the mappings - the actual override point is the abstract
 * {@code drawIcon(DrawContext, int, int, float)} hook, which is where all of this class's custom
 * rendering now lives. Verified against the real 1.21.11 Yarn mappings
 * (net.fabricmc:yarn:1.21.11+build.6:v2) and a real compile, not guessed. The original never used
 * mouseX/mouseY/delta in its body, so having them available again here (unused) changes nothing
 * functionally.
 */
public class PanelButton extends PressableWidget {

    protected final Runnable onPress;
    private int background;
    private int textColor;
    private int borderColor = 0xFF2A2D3A;

    public PanelButton(int x, int y, int width, int height, Text message, Runnable onPress) {
        super(x, y, width, height, message);
        this.onPress = onPress;
        this.background = 0xFF1A1C25;
        this.textColor = 0xFFE6E8EF;
    }

    public PanelButton colors(int background, int textColor) {
        this.background = background;
        this.textColor = textColor;
        return this;
    }

    public PanelButton border(int borderColor) {
        this.borderColor = borderColor;
        return this;
    }

    @Override
    public void onPress(AbstractInput input) {
        if (onPress != null) {
            onPress.run();
        }
    }

    @Override
    protected void drawIcon(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        int bg = this.isHovered() && this.active ? lighten(background) : background;
        context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
        if (borderColor != 0) {
            drawBorder(context, getX(), getY(), getWidth(), getHeight(), borderColor);
        }
        context.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, getMessage(),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, textColor);
    }

    /** A 1px outline drawn from four fills rather than DrawContext.drawBorder(), whose exact
     *  signature/availability is less certain than fill() across versions - shared with
     *  ArgusGuiScreen's own panel/tile borders. */
    static void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    static int lighten(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 16);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 16);
        int b = Math.min(255, (argb & 0xFF) + 16);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        this.appendDefaultNarrations(builder);
    }
}

package tools.argus.uploader.fabric.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.text.Text;

/**
 * A plain, hand-drawn rectangular button - deliberately not vanilla {@code ButtonWidget}, whose
 * own renderWidget draws a fixed vanilla texture that can't be recolored. Used for tabs, the
 * plain action buttons, and (via {@link ToggleButton}) the on/off rows, so the whole panel keeps
 * one consistent Nightwire-dark-with-violet-accent look instead of mixing in vanilla grey.
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
    public void onPress() {
        if (onPress != null) {
            onPress.run();
        }
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
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

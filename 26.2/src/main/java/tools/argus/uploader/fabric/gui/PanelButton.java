package tools.argus.uploader.fabric.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * 26.2's port of 1.21.11's PanelButton - verified via javap against the real 26.2 client jar,
 * not assumed to carry over from 1.21.11's own already-unusual widget API. The widget hierarchy
 * shifted again for this version, consistent with the wider "extract"-prefixed rendering rename
 * seen across GuiGraphicsExtractor/AbstractWidget/Screen (a Vulkan-pipeline-era restructuring,
 * not just a mapping-scheme swap): {@code PressableWidget} -&gt; {@code AbstractButton},
 * {@code onPress(AbstractInput)} -&gt; {@code onPress(InputWithModifiers)}, and the actual custom-
 * rendering override point is {@code extractContents(GuiGraphicsExtractor, int, int, float)} -
 * {@code AbstractButton}'s own {@code extractWidgetRenderState} is {@code final} and calls it
 * internally, the same relationship 1.21.11's final {@code drawButton}/{@code drawIcon} had.
 *
 * <p>No narration override here (1.21.11's just forwarded to the default anyway) -
 * {@code AbstractWidget.createNarrationMessage()}'s inherited default is equivalent, and the
 * narration API itself was restructured too ({@code NarrationMessageBuilder} is gone).
 */
public class PanelButton extends AbstractButton {

    protected final Runnable onPress;
    private int background;
    private int textColor;
    private int borderColor = 0xFF2A2D3A;

    public PanelButton(int x, int y, int width, int height, Component message, Runnable onPress) {
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
    public void onPress(InputWithModifiers input) {
        if (onPress != null) {
            onPress.run();
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        int bg = this.isHovered() && this.active ? lighten(background) : background;
        context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
        if (borderColor != 0) {
            drawBorder(context, getX(), getY(), getWidth(), getHeight(), borderColor);
        }
        context.centeredText(Minecraft.getInstance().font, getMessage(),
                getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, textColor);
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput builder) {
        this.defaultButtonNarrationText(builder);
    }

    static void drawBorder(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
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
}

package tools.argus.uploader.fabric.gui;

import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 26.2's port of 1.21.11's ToggleButton - extends this module's own {@link PanelButton}. Only
 * the {@code onPress} override's parameter type changed, same as PanelButton's own javadoc.
 */
public class ToggleButton extends PanelButton {

    private static final int ON_BG = 0xFF362A5E;
    private static final int OFF_BG = 0xFF1A1C25;
    private static final int ON_TEXT = 0xFFB79CFF;
    private static final int OFF_TEXT = 0xFF7D8296;

    private boolean on;
    private final Consumer<Boolean> onChange;

    public ToggleButton(int x, int y, int width, int height, boolean initial, Consumer<Boolean> onChange) {
        super(x, y, width, height, label(initial), null);
        this.on = initial;
        this.onChange = onChange;
        applyStyle();
    }

    public boolean isOn() {
        return on;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        on = !on;
        setMessage(label(on));
        applyStyle();
        onChange.accept(on);
    }

    private void applyStyle() {
        colors(on ? ON_BG : OFF_BG, on ? ON_TEXT : OFF_TEXT);
    }

    private static Component label(boolean on) {
        return Component.literal(on ? "ON" : "OFF");
    }
}

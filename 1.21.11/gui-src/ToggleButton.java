package tools.argus.uploader.fabric.gui;

import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * 1.21.11's port of fabric-common's ToggleButton - extends this module's own {@link PanelButton}
 * (see its javadoc for what changed). Behavior is identical to fabric-common's copy; only the
 * {@code onPress} override's parameter is new.
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
    public void onPress(AbstractInput input) {
        on = !on;
        setMessage(label(on));
        applyStyle();
        onChange.accept(on);
    }

    private void applyStyle() {
        colors(on ? ON_BG : OFF_BG, on ? ON_TEXT : OFF_TEXT);
    }

    private static Text label(boolean on) {
        return Text.literal(on ? "ON" : "OFF");
    }
}

package tools.argus.uploader.fabric.gui;

import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * An integer-valued slider snapped to a step, with a caller-supplied label formatter (e.g.
 * "3000 ms"). Keeps vanilla's own groove/handle rendering (SliderWidget draws that itself, not
 * something worth reimplementing) - the Nightwire treatment here is limited to the label text.
 */
public class LabeledSlider extends SliderWidget {

    private final int min;
    private final int max;
    private final int step;
    private final IntFunction<String> labelFor;
    private final IntConsumer onChange;

    public LabeledSlider(int x, int y, int width, int height, int min, int max, int step, int initial,
                          IntFunction<String> labelFor, IntConsumer onChange) {
        super(x, y, width, height, Text.empty(), normalize(clamp(initial, min, max), min, max));
        this.min = min;
        this.max = max;
        this.step = step;
        this.labelFor = labelFor;
        this.onChange = onChange;
        updateMessage();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double normalize(int v, int min, int max) {
        return max == min ? 0.0 : (v - min) / (double) (max - min);
    }

    private int currentValue() {
        int raw = min + (int) Math.round(this.value * (max - min));
        return Math.round(raw / (float) step) * step;
    }

    @Override
    protected void updateMessage() {
        setMessage(Text.literal(labelFor.apply(currentValue())));
    }

    @Override
    protected void applyValue() {
        onChange.accept(currentValue());
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        this.appendDefaultNarrations(builder);
    }
}

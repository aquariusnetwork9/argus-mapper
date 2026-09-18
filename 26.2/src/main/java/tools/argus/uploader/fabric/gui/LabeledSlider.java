package tools.argus.uploader.fabric.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * 26.2's port of fabric-common's LabeledSlider. {@code SliderWidget} -&gt;
 * {@code AbstractSliderButton}, verified via javap against the real 26.2 client jar - unlike the
 * button hierarchy, this one kept the exact same shape (protected {@code value} field, abstract
 * {@code updateMessage()}/{@code applyValue()}, same constructor signature), so this file is
 * otherwise unchanged from fabric-common's copy besides the import path and Text -&gt; Component.
 */
public class LabeledSlider extends AbstractSliderButton {

    private final int min;
    private final int max;
    private final int step;
    private final IntFunction<String> labelFor;
    private final IntConsumer onChange;

    public LabeledSlider(int x, int y, int width, int height, int min, int max, int step, int initial,
                          IntFunction<String> labelFor, IntConsumer onChange) {
        super(x, y, width, height, Component.empty(), normalize(clamp(initial, min, max), min, max));
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
        setMessage(Component.literal(labelFor.apply(currentValue())));
    }

    @Override
    protected void applyValue() {
        onChange.accept(currentValue());
    }
}

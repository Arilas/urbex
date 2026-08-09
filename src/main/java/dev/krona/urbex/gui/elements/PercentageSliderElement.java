package dev.krona.urbex.gui.elements;

import dev.krona.urbex.config.Configuration;
import dev.krona.urbex.gui.GuiLCConfig;
import dev.krona.urbex.varia.ComponentFactory;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;

public class PercentageSliderElement extends GuiElement {

    private final GuiLCConfig gui;
    private final String attribute;
    private final PercentageSlider field;
    private String label = null;

    public PercentageSliderElement(GuiLCConfig gui, String page, int x, int y, String attribute) {
        super(page, x, y);
        this.gui = gui;
        this.attribute = attribute;
        float initialValue = gui.getLocalSetup().get()
                .map(profile -> ((Number) profile.toConfiguration().get(attribute)).floatValue())
                .orElse(0.0f);
        field = new PercentageSlider(x, y, initialValue);
        gui.addWidget(field);
    }

    static float snap(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return Math.round(clamped * 100.0) / 100.0f;
    }

    static int percent(float value) {
        return Math.round(snap(value) * 100.0f);
    }

    public PercentageSliderElement label(String label) {
        this.label = label;
        return this;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics) {
        if (label != null && field.visible) {
            graphics.text(gui.getFont(), label, 10, y + 5, 0xffffffff);
        }
    }

    @Override
    public void update() {
        gui.getLocalSetup().get().ifPresent(profile -> {
            Number result = profile.toConfiguration().get(attribute);
            field.synchronize(result.floatValue());
        });
    }

    @Override
    public void setEnabled(boolean enabled) {
        field.active = enabled;
    }

    @Override
    public void setBasedOnMode(String mode) {
        field.visible = page.equalsIgnoreCase(mode);
    }

    private final class PercentageSlider extends AbstractSliderButton {
        private boolean synchronizing;

        private PercentageSlider(int x, int y, float initialValue) {
            super(x, y, 120, 16, ComponentFactory.literal(percent(initialValue) + "%"), snap(initialValue));
        }

        private void synchronize(float value) {
            synchronizing = true;
            try {
                setValue(snap(value));
            } finally {
                synchronizing = false;
            }
        }

        @Override
        protected void updateMessage() {
            setMessage(ComponentFactory.literal(percent((float) value) + "%"));
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void applyValue() {
            if (synchronizing) {
                return;
            }

            float snapped = snap(value);
            value = snapped;
            updateMessage();
            gui.getLocalSetup().get().ifPresent(profile -> {
                Configuration configuration = profile.toConfiguration();
                Configuration.Value<Float> configurationValue = configuration.getValue(attribute);
                configurationValue.set(snapped);
                configurationValue.constrain();
                profile.copyFromConfiguration(configuration);
                gui.refreshPreview();
            });
        }
    }
}

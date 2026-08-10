package dev.krona.urbex.gui.settings;

import dev.krona.urbex.config.Configuration;
import dev.krona.urbex.config.UrbexProfile;
import net.minecraft.network.chat.Component;

/**
 * Percent-slider math kept over from the old world-creation config screen's widget set (deleted in
 * the Phase 2 GUI redesign): snapping a raw slider position to a whole percent, formatting it for
 * display, and reading/writing/annotating the backing {@link Configuration} value.
 * <p>
 * Standalone rather than deleted alongside the rest of that widget set because
 * {@code PercentageSliderMathTest} still pins this exact snapping/rounding behaviour - the current
 * editor ({@code CustomizeScreen} via {@link SettingControls}) drives its own density sliders
 * through {@link LogValueMapper} instead, so nothing in production calls this any more.
 */
final class PercentageSliderMath {

    private PercentageSliderMath() {
    }

    static float snap(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return Math.round(clamped * 100.0) / 100.0f;
    }

    static int percent(float value) {
        return Math.round(snap(value) * 100.0f);
    }

    static float read(Configuration configuration, String attribute) {
        return ((Number) configuration.get(attribute)).floatValue();
    }

    static Component comment(Configuration configuration, String attribute) {
        return configuration.getValue(attribute).getComment();
    }

    @SuppressWarnings("unchecked")
    static float apply(UrbexProfile profile, String attribute, double value) {
        Configuration configuration = profile.toConfiguration();
        Configuration.Value<Float> configurationValue = configuration.getValue(attribute);
        configurationValue.set(snap(value));
        configurationValue.constrain();
        profile.copyFromConfiguration(configuration);
        return configurationValue.get();
    }
}

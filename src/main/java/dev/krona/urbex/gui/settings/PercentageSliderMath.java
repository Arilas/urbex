package dev.krona.urbex.gui.settings;

/**
 * Percent-slider math kept over from the old world-creation config screen's widget set (deleted in
 * the Phase 2 GUI redesign): snapping a raw slider position to a whole percent and formatting it for
 * display.
 * <p>
 * Standalone rather than deleted alongside the rest of that widget set because
 * {@code PercentageSliderMathTest} still pins this exact snapping/rounding behaviour - the current
 * editor ({@code CustomizeScreen} via {@link SettingControls}) drives its own density sliders
 * through {@link LogValueMapper} instead, so nothing in production calls this any more. The
 * {@code UrbexProfile}/{@code Configuration}-binding half of this class (Task 4's predecessor) was
 * removed with the rest of that legacy surface: this package may no longer reference either type.
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
}

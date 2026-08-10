package dev.krona.urbex.gui.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the pure snapping contract of {@link SettingControls#snapToStep}: a linear slider's continuous value is
 * rounded to the nearest multiple of its declared {@code step} (anchored at {@code min}), clamped to
 * {@code [min, max]}, with {@code step <= 0} meaning "no snapping". The method is pure (no GL, no widget state),
 * so it is exercised directly — this is the math the {@code SliderWidget} value-read path relies on so that both
 * the written field value and the readout label reflect the same stepped value.
 */
class SliderStepMathTest {

    private static final double EPS = 1e-9;

    // ---- nearest-multiple snapping -----------------------------------------

    @Test
    void snapsToNearestMultiple() {
        // step 0.01 over [0, 1]: rounds to the nearest hundredth.
        assertEquals(0.37, SettingControls.snapToStep(0.374, 0.0, 1.0, 0.01), EPS);
        assertEquals(0.38, SettingControls.snapToStep(0.376, 0.0, 1.0, 0.01), EPS);
    }

    @Test
    void snapsWithHalfUnitStep() {
        // CITY_PERLIN_SCALE-style: [0.5, 25] step 0.5. The default 3 is already on-grid; nudges round to it.
        assertEquals(3.0, SettingControls.snapToStep(3.0, 0.5, 25.0, 0.5), EPS);
        assertEquals(3.0, SettingControls.snapToStep(3.2, 0.5, 25.0, 0.5), EPS);
        assertEquals(3.5, SettingControls.snapToStep(3.3, 0.5, 25.0, 0.5), EPS);
    }

    @Test
    void gridIsAnchoredAtMinNotZero() {
        // min 0.5 is not itself a multiple of step 0.5's zero-anchored grid; anchoring at min keeps values on-range.
        assertEquals(0.5, SettingControls.snapToStep(0.6, 0.5, 25.0, 0.5), EPS);
        assertEquals(1.0, SettingControls.snapToStep(0.8, 0.5, 25.0, 0.5), EPS);
    }

    @Test
    void snapsWithNegativeMinAnchor() {
        // CITY_PERLIN_OFFSET-style: [-1, 1] step 0.01, default 0.1 mid-track.
        assertEquals(0.1, SettingControls.snapToStep(0.103, -1.0, 1.0, 0.01), EPS);
        assertEquals(-0.5, SettingControls.snapToStep(-0.497, -1.0, 1.0, 0.01), EPS);
    }

    // ---- bounds ------------------------------------------------------------

    @Test
    void respectsBounds() {
        assertEquals(0.5, SettingControls.snapToStep(-4.0, 0.5, 25.0, 0.5), EPS);
        assertEquals(25.0, SettingControls.snapToStep(999.0, 0.5, 25.0, 0.5), EPS);
    }

    @Test
    void endpointsAreReachable() {
        assertEquals(1.0, SettingControls.snapToStep(1.0, 0.0, 1.0, 0.01), EPS);
        assertEquals(0.0, SettingControls.snapToStep(0.0, 0.0, 1.0, 0.01), EPS);
    }

    // ---- step <= 0 means no snapping ---------------------------------------

    @Test
    void nonPositiveStepPassesValueThrough() {
        assertEquals(0.123456, SettingControls.snapToStep(0.123456, 0.0, 1.0, 0.0), EPS);
        assertEquals(0.123456, SettingControls.snapToStep(0.123456, 0.0, 1.0, -1.0), EPS);
    }
}

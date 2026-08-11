package dev.krona.urbex.gui.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PercentageSliderMathTest {

    @Test
    void snapsToWholePercentAndClamps() {
        assertEquals(0.00f, PercentageSliderMath.snap(-0.4));
        assertEquals(0.37f, PercentageSliderMath.snap(0.374));
        assertEquals(0.38f, PercentageSliderMath.snap(0.376));
        assertEquals(1.00f, PercentageSliderMath.snap(1.4));
    }

    @Test
    void formatsNormalizedValuesAsPercent() {
        assertEquals(0, PercentageSliderMath.percent(0.0f));
        assertEquals(65, PercentageSliderMath.percent(0.65f));
        assertEquals(100, PercentageSliderMath.percent(1.0f));
    }
}

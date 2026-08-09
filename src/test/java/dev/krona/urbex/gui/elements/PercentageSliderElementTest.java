package dev.krona.urbex.gui.elements;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PercentageSliderElementTest {

    @Test
    void snapsToWholePercentAndClamps() {
        assertEquals(0.00f, PercentageSliderElement.snap(-0.4));
        assertEquals(0.37f, PercentageSliderElement.snap(0.374));
        assertEquals(0.38f, PercentageSliderElement.snap(0.376));
        assertEquals(1.00f, PercentageSliderElement.snap(1.4));
    }

    @Test
    void formatsNormalizedValuesAsPercent() {
        assertEquals(0, PercentageSliderElement.percent(0.0f));
        assertEquals(65, PercentageSliderElement.percent(0.65f));
        assertEquals(100, PercentageSliderElement.percent(1.0f));
    }
}

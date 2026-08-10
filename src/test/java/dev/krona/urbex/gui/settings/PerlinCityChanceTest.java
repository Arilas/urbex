package dev.krona.urbex.gui.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GL composite widget behind {@code CITY_CHANCE} can't be exercised headless, but its state mapping is a
 * pure function ({@link PerlinCityChance}), so the round-trips that make {@code -1} survive the editor - and
 * that a positive value is never written while perlin is on - are pinned here.
 */
class PerlinCityChanceTest {

    @Test
    void minusOneIsPerlinMode() {
        assertTrue(PerlinCityChance.isPerlin(-1.0));
        assertTrue(PerlinCityChance.isPerlin(0.0));
        assertFalse(PerlinCityChance.isPerlin(0.0001));
        assertFalse(PerlinCityChance.isPerlin(0.01));
        assertFalse(PerlinCityChance.isPerlin(1.0));
    }

    @Test
    void sliderValueFallsBackOnlyInPerlinMode() {
        // Perlin mode: slider position is irrelevant, so the fallback is used.
        assertEquals(0.0001, PerlinCityChance.sliderValue(-1.0, 0.0001));
        // Positive value: the field value is shown verbatim.
        assertEquals(0.02, PerlinCityChance.sliderValue(0.02, 0.0001));
    }

    @Test
    void toFieldWritesSentinelWhenPerlinOnAndSliderValueWhenOff() {
        // Perlin on always yields -1, regardless of the slider's position.
        assertEquals(-1.0, PerlinCityChance.toField(true, 0.5));
        assertEquals(-1.0, PerlinCityChance.toField(true, 0.0001));
        // Perlin off yields the slider's positive value verbatim.
        assertEquals(0.5, PerlinCityChance.toField(false, 0.5));
    }

    @Test
    void perlinFieldRoundTrips() {
        double field = -1.0;
        boolean perlinOn = PerlinCityChance.isPerlin(field);
        double slider = PerlinCityChance.sliderValue(field, 0.0001);
        assertEquals(field, PerlinCityChance.toField(perlinOn, slider),
                "a -1 field must round-trip back to -1 so the largecities preset is preserved");
    }

    @Test
    void positiveFieldRoundTrips() {
        double field = 0.02;
        boolean perlinOn = PerlinCityChance.isPerlin(field);
        double slider = PerlinCityChance.sliderValue(field, 0.0001);
        assertEquals(field, PerlinCityChance.toField(perlinOn, slider),
                "a positive field must round-trip back to the same value");
    }
}

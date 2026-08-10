package dev.krona.urbex.gui.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogValueMapper} backs the log-scale sliders on the General tab (the chance knobs, mapped
 * (1e-4, 1)): a linear slider would waste almost all of its travel above 0.01, so these instead move
 * proportionally to the exponent. Two properties make that trustworthy: the mapping actually round-trips
 * (so dragging the slider and reading the value back doesn't drift), and it covers the whole range
 * monotonically (so dragging right never decreases the value). {@link #format} is tested separately
 * because it is what makes 0.0001 and 0.001 distinguishable in the slider's value readout despite both
 * looking like "0.000..." at a glance.
 */
class LogValueMapperTest {

    private static final double MIN = 1e-4;
    private static final double MAX = 1.0;
    private static final LogValueMapper MAPPER = new LogValueMapper(MIN, MAX);

    private static void assertRelativelyClose(double expected, double actual) {
        double relativeError = Math.abs((actual - expected) / expected);
        assertTrue(relativeError < 1e-9,
                "expected " + expected + " but was " + actual + " (relative error " + relativeError + ")");
    }

    @Test
    void roundTripsAtMin() {
        assertRelativelyClose(MIN, MAPPER.fromSlider(MAPPER.toSlider(MIN)));
    }

    @Test
    void roundTripsAtMax() {
        assertRelativelyClose(MAX, MAPPER.fromSlider(MAPPER.toSlider(MAX)));
    }

    @Test
    void roundTripsAtSevenInteriorPoints() {
        // Log-spaced points strictly between min and max: fromSlider(i/8) for i in 1..7.
        for (int i = 1; i <= 7; i++) {
            double t = i / 8.0;
            double value = MAPPER.fromSlider(t);
            assertTrue(value > MIN && value < MAX, "interior point " + value + " should lie strictly within (min, max)");
            assertRelativelyClose(value, MAPPER.fromSlider(MAPPER.toSlider(value)));
        }
    }

    @Test
    void endpointsMapToZeroAndOne() {
        assertEquals(0.0, MAPPER.toSlider(MIN), 0.0);
        assertEquals(1.0, MAPPER.toSlider(MAX), 0.0);
    }

    @Test
    void formatDistinguishesOneAndTenTimesSmaller() {
        assertNotEquals(LogValueMapper.format(0.0001), LogValueMapper.format(0.001));
    }

    @Test
    void toSliderIsMonotonicAcrossTheRange() {
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= 100; i++) {
            // Sample values log-spaced across the range (linear sampling would spend 99% of the
            // samples above 0.01, which says nothing about resolution near the small end).
            double t = i / 100.0;
            double value = MAPPER.fromSlider(t);
            double sliderPosition = MAPPER.toSlider(value);
            assertTrue(sliderPosition >= previous,
                    "toSlider must be non-decreasing: value " + value + " mapped to " + sliderPosition
                            + " which is less than the previous " + previous);
            previous = sliderPosition;
        }
    }

    @Test
    void fromSliderIsMonotonicAcrossZeroToOne() {
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= 100; i++) {
            double t = i / 100.0;
            double value = MAPPER.fromSlider(t);
            assertTrue(value >= previous,
                    "fromSlider must be non-decreasing: t " + t + " mapped to " + value
                            + " which is less than the previous " + previous);
            previous = value;
        }
    }

    @Test
    void toSliderClampsOutOfRangeInput() {
        assertEquals(0.0, MAPPER.toSlider(MIN / 10.0), 0.0);
        assertEquals(1.0, MAPPER.toSlider(MAX * 10.0), 0.0);
    }

    @Test
    void fromSliderClampsOutOfRangeInput() {
        assertEquals(MIN, MAPPER.fromSlider(-1.0), 0.0);
        assertEquals(MAX, MAPPER.fromSlider(2.0), 0.0);
    }

    @Test
    void constructorRejectsNonPositiveMin() {
        try {
            new LogValueMapper(0.0, 1.0);
            assertTrue(false, "expected IllegalArgumentException for min <= 0");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    void constructorRejectsMaxNotGreaterThanMin() {
        try {
            new LogValueMapper(1.0, 1.0);
            assertTrue(false, "expected IllegalArgumentException for max <= min");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}

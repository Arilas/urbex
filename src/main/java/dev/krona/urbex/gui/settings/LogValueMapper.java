package dev.krona.urbex.gui.settings;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Maps a value in a strictly positive range {@code [min, max]} to/from a normalized slider position in
 * {@code [0, 1]} on a logarithmic scale, and formats values to a fixed number of significant digits.
 *
 * <p>Backs {@link ControlKind#SLIDER} descriptors with {@link SettingDescriptor#logScale()} set (the chance
 * knobs on the {@link SettingCategory#GENERAL} tab, mapped {@code (1e-4, 1)}). A linear slider over that range
 * would spend the vast majority of its travel above {@code 0.01} and give no usable resolution near the small
 * end, where the difference between {@code 0.0001} and {@code 0.001} is a 10x change in city rarity; the log
 * mapping instead moves proportionally to the exponent.</p>
 *
 * @param min inclusive lower bound of the mapped range; must be {@code > 0}.
 * @param max inclusive upper bound of the mapped range; must be {@code > min}.
 */
public record LogValueMapper(double min, double max) {

    /** Slider readouts show three significant digits: enough to tell 0.0001 and 0.001 apart at a glance. */
    private static final int SIGNIFICANT_DIGITS = 3;

    public LogValueMapper {
        if (!(min > 0.0)) {
            throw new IllegalArgumentException("LogValueMapper requires min > 0 (was " + min + ")");
        }
        if (!(max > min)) {
            throw new IllegalArgumentException("LogValueMapper requires max > min (was " + min + ".." + max + ")");
        }
    }

    /** Maps a value in {@code [min, max]} to a slider position in {@code [0, 1]}; out-of-range input clamps. */
    public double toSlider(double value) {
        double clampedValue = Math.max(min, Math.min(max, value));
        double t = (Math.log(clampedValue) - Math.log(min)) / (Math.log(max) - Math.log(min));
        return Math.max(0.0, Math.min(1.0, t));
    }

    /**
     * Maps a slider position in {@code [0, 1]} back to a value in {@code [min, max]}; out-of-range input clamps.
     * The endpoints are special-cased rather than run through {@code exp(log(x))}, which is not guaranteed
     * bit-exact for arbitrary {@code x} — this keeps {@code fromSlider(0) == min} and {@code fromSlider(1) == max}
     * exactly, matching {@link #toSlider}'s exact endpoints.
     */
    public double fromSlider(double t) {
        if (t <= 0.0) {
            return min;
        }
        if (t >= 1.0) {
            return max;
        }
        return Math.exp(Math.log(min) + t * (Math.log(max) - Math.log(min)));
    }

    /**
     * Formats {@code value} to {@value #SIGNIFICANT_DIGITS} significant digits, without trailing zeros or
     * scientific notation, so values that look identical at a coarse glance (0.0001 vs 0.001) still render
     * distinctly.
     *
     * <p>Static, not an instance method: unlike {@link #toSlider} and {@link #fromSlider} this has nothing to
     * do with the {@code [min, max]} range — {@link SettingControls} uses it for every slider's value readout,
     * including linear ones whose range can include {@code 0} or negative bounds where a {@code LogValueMapper}
     * could not even be constructed.</p>
     */
    public static String format(double value) {
        if (value == 0.0) {
            return "0";
        }
        BigDecimal rounded = new BigDecimal(value, new MathContext(SIGNIFICANT_DIGITS));
        return rounded.stripTrailingZeros().toPlainString();
    }
}

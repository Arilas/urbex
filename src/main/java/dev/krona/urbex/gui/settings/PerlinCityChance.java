package dev.krona.urbex.gui.settings;

/**
 * The pure state mapping behind the {@link ControlKind#CHANCE_PERLIN} composite control (only
 * {@code CITY_CHANCE} uses it).
 *
 * <p>{@code CITY_CHANCE == -1} is not a magnitude but a <em>mode</em>: it selects a perlin-noise city map
 * (the {@code largecities} preset relies on it). A plain slider cannot represent that, so the editor pairs a
 * positive-range log slider with a "perlin city map" toggle. This class is the single source of truth for the
 * two-way mapping between the backing field value and the {@code (perlinOn, sliderValue)} widget state, kept
 * free of any GL/widget dependency so it can be unit-tested directly.</p>
 */
public final class PerlinCityChance {

    /** The sentinel the field carries while the perlin-city-map mode is on. */
    public static final double PERLIN_SENTINEL = -1.0;

    private PerlinCityChance() {
    }

    /**
     * Whether a backing field value means "perlin city map". Any non-positive value maps to the mode (the
     * canonical value is {@code -1}); a positive value is a real chance magnitude.
     */
    public static boolean isPerlin(double fieldValue) {
        return fieldValue <= 0.0;
    }

    /**
     * The positive value the log slider should display for a given field value. When the field is in perlin
     * mode (non-positive) the slider position is irrelevant, so this returns the supplied {@code fallback}
     * (typically the last positive value, or the slider's minimum).
     */
    public static double sliderValue(double fieldValue, double fallback) {
        return isPerlin(fieldValue) ? fallback : fieldValue;
    }

    /**
     * The field value to write for a given widget state. Perlin on always yields {@link #PERLIN_SENTINEL}
     * (never a positive value); perlin off yields the slider's current positive value verbatim.
     */
    public static double toField(boolean perlinOn, double sliderValue) {
        return perlinOn ? PERLIN_SENTINEL : sliderValue;
    }
}

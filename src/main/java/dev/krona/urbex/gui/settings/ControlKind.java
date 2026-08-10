package dev.krona.urbex.gui.settings;

/**
 * The visual control a {@link SettingDescriptor} renders as in the Phase 2 editor.
 *
 * <ul>
 *     <li>{@link #SLIDER} — a numeric field (int/float/double). Values are always boxed as {@link Double}.</li>
 *     <li>{@link #TOGGLE} — a boolean field. Values are boxed as {@link Boolean}.</li>
 *     <li>{@link #CYCLE} — an enum field (e.g. {@code LandscapeType}). Values are boxed as the enum type itself.</li>
 *     <li>{@link #TEXT} — a {@code String} or {@code String[]} field (block ids, biome ids, name lists).</li>
 *     <li>{@link #NUMBER} — a typed numeric field (int/float/double) for genuinely open-ended values a slider
 *         cannot express (noise scales with no natural maximum, million-block distances, huge attempt counts,
 *         power-of-two bit masks). Values box as {@link Double}, exactly like {@link #SLIDER}; the setter
 *         narrows to the field's real type. {@link SettingDescriptor#integerOnly()} says whether the box
 *         accepts decimals.</li>
 *     <li>{@link #CHANCE_PERLIN} — a logarithmic chance slider paired with a "perlin city map" toggle that
 *         represents the {@code -1} sentinel; only {@code CITY_CHANCE} uses it. Values box as {@link Double},
 *         like {@link #SLIDER}, and bounds/step/{@code logScale} are read the same way.</li>
 * </ul>
 */
public enum ControlKind {
    SLIDER,
    TOGGLE,
    CYCLE,
    TEXT,
    NUMBER,
    CHANCE_PERLIN
}

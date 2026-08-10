package dev.krona.urbex.gui.settings;

/**
 * The visual control a {@link SettingDescriptor} renders as in the Phase 2 editor.
 *
 * <ul>
 *     <li>{@link #SLIDER} — a numeric field (int/float/double). Values are always boxed as {@link Double}.</li>
 *     <li>{@link #TOGGLE} — a boolean field. Values are boxed as {@link Boolean}.</li>
 *     <li>{@link #CYCLE} — an enum field (e.g. {@code LandscapeType}). Values are boxed as the enum type itself.</li>
 *     <li>{@link #TEXT} — a {@code String} or {@code String[]} field (block ids, biome ids, name lists).</li>
 * </ul>
 */
public enum ControlKind {
    SLIDER,
    TOGGLE,
    CYCLE,
    TEXT
}

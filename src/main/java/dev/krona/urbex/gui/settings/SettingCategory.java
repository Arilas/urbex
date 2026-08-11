package dev.krona.urbex.gui.settings;

import java.util.Locale;

/**
 * The tab a {@link SettingDescriptor} lives under in the Phase 2 editor.
 *
 * <p>Every field is described by exactly one descriptor and so belongs to exactly one category (no duplicates).
 * {@link #GENERAL} is the real home of a curated handful of the most impactful knobs (city chance, radii,
 * floors, ruin/explosion chances, loot &amp; lighting density, landscape type); those are removed from their
 * former categories so the same control never appears twice.</p>
 *
 * <p>{@link #ROADS} and {@link #TRANSPORT} split what used to be one crowded tab along the line each
 * category's own preview actually draws: {@link #TRANSPORT} keeps highways and railways (its overlay's
 * network), and {@link #ROADS} owns the hierarchical street grid - primary/secondary/tertiary roads and
 * planned bridges, including {@code MULTI_BUILDING_STREET_CONFLICT} - previewed by
 * {@code CityPreview.Mode.ROADS}. See {@code CustomizeScreen#modeForCategory} for the category-to-preview
 * wiring.</p>
 */
public enum SettingCategory {
    GENERAL,
    CITIES,
    BUILDINGS,
    DAMAGE,
    TRANSPORT,
    ROADS,
    TERRAIN,
    SPAWN,
    ADVANCED;

    /** Translation key for this category's tab label, e.g. {@code urbex.category.cities}. */
    public String labelKey() {
        return "urbex.category." + name().toLowerCase(Locale.ROOT);
    }
}

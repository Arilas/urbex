package dev.krona.urbex.gui.settings;

import java.util.Locale;

/**
 * The tab a {@link SettingDescriptor} lives under in the Phase 2 editor.
 *
 * <p>Every field is described by exactly one descriptor and so belongs to exactly one category (no duplicates).
 * {@link #GENERAL} is the real home of a curated handful of the most impactful knobs (city chance, radii,
 * floors, ruin/explosion chances, loot &amp; lighting density, landscape type); those are removed from their
 * former categories so the same control never appears twice.</p>
 */
public enum SettingCategory {
    GENERAL,
    CITIES,
    BUILDINGS,
    DAMAGE,
    TRANSPORT,
    SPHERES,
    TERRAIN,
    SPAWN,
    ADVANCED;

    /** Translation key for this category's tab label, e.g. {@code urbex.category.cities}. */
    public String labelKey() {
        return "urbex.category." + name().toLowerCase(Locale.ROOT);
    }
}

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
 * <p>{@link #ROADS} is the one exception to "every category has settings": the sixteen primary/secondary/
 * tertiary/bridge sliders stay under {@link #TRANSPORT} (that placement is settled, not this enum's to
 * revisit), so this tab carries no descriptors of its own. It exists purely to host the road-class preview
 * ({@code CityPreview.Mode.ROADS}) as a first-class, selectable view alongside {@link #TRANSPORT}'s
 * highway/rail overlay - see {@code CustomizeScreen#modeForCategory}.</p>
 */
public enum SettingCategory {
    GENERAL,
    CITIES,
    BUILDINGS,
    DAMAGE,
    TRANSPORT,
    ROADS,
    SPHERES,
    TERRAIN,
    SPAWN,
    ADVANCED;

    /** Translation key for this category's tab label, e.g. {@code urbex.category.cities}. */
    public String labelKey() {
        return "urbex.category." + name().toLowerCase(Locale.ROOT);
    }
}

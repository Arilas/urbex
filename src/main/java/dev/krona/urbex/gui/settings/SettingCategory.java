package dev.krona.urbex.gui.settings;

import java.util.Locale;

/**
 * The tab a {@link SettingDescriptor} lives under in the Phase 2 editor.
 *
 * <p>{@link #GENERAL} is special: it is never the home category of a field. Instead the ~dozen curated
 * "general" descriptors carry it as a second, {@code general=true} copy of an existing field descriptor so
 * the General tab can surface the most impactful knobs without owning any field exclusively. Every field is
 * owned by exactly one of the other eight categories.</p>
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

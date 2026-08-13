package dev.krona.urbex.gui.settings;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Metadata describing a single editable {@link Preset} setting: which control renders it, its bounds,
 * and how to read/write the backing field.
 *
 * <p><b>Direct field access, on purpose.</b> The {@link #getter} and {@link #setter} read and write the public
 * {@code Preset} field directly (e.g. {@code p -> p.cityChance()}) rather than routing through a config-file
 * bridge class. This was deliberate: it let issue #75 part 2 delete the old {@code Configuration} bridge
 * (Task 5) without having to touch this framework.</p>
 *
 * <p><b>Boxing convention.</b> Values crossing the getter/setter boundary are boxed consistently so the Task 5
 * control layer can coerce them uniformly:</p>
 * <ul>
 *     <li>{@link ControlKind#SLIDER} — always {@link Double}, even when the field is an {@code int} or {@code float}.
 *         The getter widens to {@code Double}; the setter narrows back (rounding for integer fields).</li>
 *     <li>{@link ControlKind#TOGGLE} — {@link Boolean}.</li>
 *     <li>{@link ControlKind#CYCLE} — the field's enum type (e.g. {@code LandscapeType}).</li>
 *     <li>{@link ControlKind#TEXT} — {@link String}, or {@code String[]} for list-valued fields.</li>
 * </ul>
 *
 * <p>{@link #min}, {@link #max}, {@link #step} and {@link #logScale} are only meaningful for {@link ControlKind#SLIDER};
 * other kinds pass {@code 0} bounds. A {@code logScale} slider must have {@code min > 0}. {@link #integerOnly} is
 * only meaningful for {@link ControlKind#NUMBER} (whether the typed box accepts decimals); every other kind passes
 * {@code false}.</p>
 *
 * @param key         the backing {@code Preset} public field name; also the lang-key suffix
 *                    ({@code urbex.setting.<key>} and {@code urbex.setting.<key>.tooltip}).
 * @param category    the tab this descriptor lives under; each field is described by exactly one descriptor
 *                    (no duplicates), so a curated few carry {@link SettingCategory#GENERAL} as their real home.
 * @param section     the sub-section grouping this descriptor within its category (a stable lowercase id like
 *                    {@code "placement"} or {@code "rarity_map"}). The editor renders a labelled header above the
 *                    first setting of each section; sections appear in first-seen (declaration) order. Also the
 *                    lang-key suffix ({@code urbex.section.<category>.<section>} and {@code ….desc}).
 * @param kind        the control to render.
 * @param min         slider lower bound (mined from the {@code Preset.init} min argument).
 * @param max         slider upper bound (mined from the {@code Preset.init} max argument).
 * @param step        slider increment.
 * @param logScale    {@code true} for logarithmic sliders (the chance fields); requires {@code min > 0}.
 * @param integerOnly {@code true} for a {@link ControlKind#NUMBER} descriptor backing an {@code int} field, so the
 *                    typed box rejects decimals; ignored by every other kind.
 * @param getter      reads the boxed current value from a profile.
 * @param setter      writes a boxed value back into a profile.
 */
public record SettingDescriptor(
        String key,
        SettingCategory category,
        String section,
        ControlKind kind,
        double min,
        double max,
        double step,
        boolean logScale,
        boolean integerOnly,
        Function<PresetDraft, Object> getter,
        BiConsumer<PresetDraft, Object> setter
) {
    /** Lang key for this setting's display name. */
    public String nameKey() {
        return "urbex.setting." + key;
    }

    /** Lang key for this setting's tooltip. */
    public String tooltipKey() {
        return "urbex.setting." + key + ".tooltip";
    }

    /** Lang key for this descriptor's sub-section name, e.g. {@code urbex.section.cities.rarity_map}. */
    public String sectionNameKey() {
        return "urbex.section." + category.name().toLowerCase(Locale.ROOT) + "." + section;
    }

    /** Lang key for this descriptor's sub-section one-line description. */
    public String sectionDescKey() {
        return sectionNameKey() + ".desc";
    }
}

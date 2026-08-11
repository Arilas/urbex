package dev.krona.urbex.gui.settings;

import dev.krona.urbex.config.LandscapeType;
import dev.krona.urbex.config.MultiBuildingStreetConflict;
import dev.krona.urbex.config.UrbexProfile;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The complete registry of editable {@link UrbexProfile} settings for the Phase 2 editor.
 *
 * <p>{@link #ALL} holds exactly one descriptor per editable profile field — no duplicates. Each field
 * therefore lives in exactly one {@link SettingCategory}; a curated handful of the most impactful knobs carry
 * {@link SettingCategory#GENERAL} as their real home (and are absent from their former categories) so the same
 * control never renders twice. The {@code SettingsCompletenessTest} guarantees the descriptors cover every
 * public {@code UrbexProfile} field exactly once (minus a small, justified excluded set).</p>
 *
 * <p>Within a category the descriptors are grouped into ordered <em>sub-sections</em> (see
 * {@link SettingDescriptor#section()}): every descriptor is stamped with the {@link Reg#section current section}
 * as it is added, and declaration order is display order, so sections appear in first-seen order and their
 * members render contiguously under a labelled header. The descriptors below are deliberately ordered so each
 * section's members are consecutive.</p>
 *
 * <p>See {@link SettingDescriptor} for the boxing convention and the rationale for direct field access.</p>
 */
public final class Settings {

    public static final List<SettingDescriptor> ALL = buildAll();

    private Settings() {
    }

    /**
     * Accumulates descriptors while stamping each with the {@link #section current sub-section}. Call
     * {@link #section(String)} to open a section, then the typed add-methods for each descriptor in it; every
     * descriptor added carries whatever section is currently open. Keeping the section as accumulator state
     * (rather than a per-call argument) is what lets the builder read as grouped blocks below.
     */
    private static final class Reg {
        private final List<SettingDescriptor> all = new ArrayList<>();
        private String section;

        /** Opens a sub-section; every descriptor added afterwards is stamped with {@code id} until the next call. */
        private void section(String id) {
            this.section = id;
        }

        private void add(String key, SettingCategory cat, ControlKind kind, double min, double max, double step,
                         boolean logScale, boolean integerOnly,
                         Function<UrbexProfile, Object> getter, BiConsumer<UrbexProfile, Object> setter) {
            if (section == null) {
                throw new IllegalStateException("descriptor " + key + " added before any section() was opened");
            }
            all.add(new SettingDescriptor(key, cat, section, kind, min, max, step, logScale, integerOnly, getter, setter));
        }

        private void slider(String key, SettingCategory cat, double min, double max, double step,
                            Function<UrbexProfile, Object> getter, BiConsumer<UrbexProfile, Object> setter) {
            add(key, cat, ControlKind.SLIDER, min, max, step, false, false, getter, setter);
        }

        /** A logarithmic slider (min &gt; 0) for the chance knobs where 0.0001 and 0.001 must be distinguishable. */
        private void logSlider(String key, SettingCategory cat, double min, double max, double step,
                               Function<UrbexProfile, Object> getter, BiConsumer<UrbexProfile, Object> setter) {
            add(key, cat, ControlKind.SLIDER, min, max, step, true, false, getter, setter);
        }

        /**
         * A typed numeric field for genuinely open-ended values a slider cannot honestly express: noise scales with
         * no natural maximum, million-block distances, huge attempt counts, or a power-of-two bit mask. Values box as
         * {@link Double} exactly like {@link #slider}; {@code integerOnly} makes the box reject decimals for
         * {@code int}-backed fields.
         *
         * <p>{@code min}/{@code max} still carry the field's real accepted range (the config validation bounds it
         * was mined from) even though there is no slider track: {@link SettingControls} refuses to write a typed
         * value outside them, so a NUMBER field can never corrupt the profile with an out-of-range (or, for an
         * {@code int} field, an overflowing) value.</p>
         */
        private void number(String key, SettingCategory cat, double min, double max, boolean integerOnly,
                            Function<UrbexProfile, Object> getter, BiConsumer<UrbexProfile, Object> setter) {
            add(key, cat, ControlKind.NUMBER, min, max, 0, false, integerOnly, getter, setter);
        }

        /**
         * The {@code CITY_CHANCE}-only composite: a log slider over the positive range plus a "perlin city map"
         * toggle representing the {@code -1} sentinel (see {@link PerlinCityChance}). The getter/setter stay plain
         * field access; the widget coordinates the sentinel against the single field.
         */
        private void chancePerlin(String key, SettingCategory cat, double min, double max, double step,
                                  Function<UrbexProfile, Object> getter, BiConsumer<UrbexProfile, Object> setter) {
            add(key, cat, ControlKind.CHANCE_PERLIN, min, max, step, true, false, getter, setter);
        }

        private void toggle(String key, SettingCategory cat,
                            Function<UrbexProfile, Object> getter, BiConsumer<UrbexProfile, Object> setter) {
            add(key, cat, ControlKind.TOGGLE, 0, 0, 0, false, false, getter, setter);
        }

        private void cycle(String key, SettingCategory cat,
                           Function<UrbexProfile, Object> getter, BiConsumer<UrbexProfile, Object> setter) {
            add(key, cat, ControlKind.CYCLE, 0, 0, 0, false, false, getter, setter);
        }

        private void text(String key, SettingCategory cat,
                          Function<UrbexProfile, Object> getter, BiConsumer<UrbexProfile, Object> setter) {
            add(key, cat, ControlKind.TEXT, 0, 0, 0, false, false, getter, setter);
        }
    }

    private static List<SettingDescriptor> buildAll() {
        Reg r = new Reg();

        // ==== GENERAL ========================================================
        // The curated, highest-impact knobs. These live ONLY here (removed from their former categories) so the
        // same control never shows twice. The chance knobs use a log scale with min > 0; city chance adds the
        // perlin-city-map toggle for its -1 sentinel.
        r.section("city");
        r.chancePerlin("CITY_CHANCE", SettingCategory.GENERAL, 0.0001, 1.0, 0.0001,
                p -> p.CITY_CHANCE, (p, v) -> p.CITY_CHANCE = (Double) v);
        r.slider("CITY_MINRADIUS", SettingCategory.GENERAL, 1, 2000, 1,
                p -> (double) p.CITY_MINRADIUS, (p, v) -> p.CITY_MINRADIUS = (int) Math.round((Double) v));
        r.slider("CITY_MAXRADIUS", SettingCategory.GENERAL, 1, 2000, 1,
                p -> (double) p.CITY_MAXRADIUS, (p, v) -> p.CITY_MAXRADIUS = (int) Math.round((Double) v));

        r.section("buildings");
        r.slider("BUILDING_MINFLOORS", SettingCategory.GENERAL, 0, 60, 1,
                p -> (double) p.BUILDING_MINFLOORS, (p, v) -> p.BUILDING_MINFLOORS = (int) Math.round((Double) v));
        r.slider("BUILDING_MAXFLOORS", SettingCategory.GENERAL, 0, 60, 1,
                p -> (double) p.BUILDING_MAXFLOORS, (p, v) -> p.BUILDING_MAXFLOORS = (int) Math.round((Double) v));

        r.section("damage");
        r.slider("RUIN_CHANCE", SettingCategory.GENERAL, 0.0, 1.0, 0.01,
                p -> (double) p.RUIN_CHANCE, (p, v) -> p.RUIN_CHANCE = ((Double) v).floatValue());
        r.logSlider("EXPLOSION_CHANCE", SettingCategory.GENERAL, 0.0001, 1.0, 0.0001,
                p -> (double) p.EXPLOSION_CHANCE, (p, v) -> p.EXPLOSION_CHANCE = ((Double) v).floatValue());
        r.logSlider("MINI_EXPLOSION_CHANCE", SettingCategory.GENERAL, 0.0001, 1.0, 0.0001,
                p -> (double) p.MINI_EXPLOSION_CHANCE, (p, v) -> p.MINI_EXPLOSION_CHANCE = ((Double) v).floatValue());

        r.section("interior");
        r.slider("LOOT_DENSITY", SettingCategory.GENERAL, 0.0, 1.0, 0.01,
                p -> (double) p.LOOT_DENSITY, (p, v) -> p.LOOT_DENSITY = ((Double) v).floatValue());
        r.slider("LIGHTING_DENSITY", SettingCategory.GENERAL, 0.0, 1.0, 0.01,
                p -> (double) p.LIGHTING_DENSITY, (p, v) -> p.LIGHTING_DENSITY = ((Double) v).floatValue());

        r.section("world");
        r.cycle("LANDSCAPE_TYPE", SettingCategory.GENERAL,
                p -> p.LANDSCAPE_TYPE, (p, v) -> p.LANDSCAPE_TYPE = (LandscapeType) v);

        // ==== CITIES =========================================================
        // City placement thresholds, per-level heights and spawn distances. (Chance and radii live in GENERAL.)
        // The rarity noise is factor = perlinCity.getValue(cx/scale, cz/scale) * innerScale - offset, clamped at 0
        // and compared to CITY_THRESHOLD. The config allows ±1,000,000 on all three, but the noise output is ~[-1,1]
        // so every value that meaningfully shapes the map lives in a small band. These are stepped sliders over that
        // practical band (the defaults 3 / 0.1 / 0.1 land comfortably inside), not the config's degenerate ceiling.
        r.section("rarity_map");
        // Coordinate divisor: larger = the noise varies more slowly = larger, rarer city regions. Default 3 sits ~10%
        // in; up to 25 gives continent-scale regions, 0.5 gives tight ones. Half-unit steps are fine for a divisor.
        r.slider("CITY_PERLIN_SCALE", SettingCategory.CITIES, 0.5, 25.0, 0.5,
                p -> p.CITY_PERLIN_SCALE, (p, v) -> p.CITY_PERLIN_SCALE = (Double) v);
        // Noise amplitude multiplier: default 0.1 (10% in). 0 flattens the map; ~0.3+ starts clearing the threshold;
        // >1 makes nearly everywhere a city. 0.01 steps give precise control near the useful low end.
        r.slider("CITY_PERLIN_INNERSCALE", SettingCategory.CITIES, 0.0, 1.0, 0.01,
                p -> p.CITY_PERLIN_INNERSCALE, (p, v) -> p.CITY_PERLIN_INNERSCALE = (Double) v);
        // Noise shift (subtracted before the threshold): default 0.1 (mid-track). Symmetric ±1 spans the noise range —
        // positive raises the city bar, negative lowers it. 0.01 steps match INNERSCALE's resolution.
        r.slider("CITY_PERLIN_OFFSET", SettingCategory.CITIES, -1.0, 1.0, 0.01,
                p -> p.CITY_PERLIN_OFFSET, (p, v) -> p.CITY_PERLIN_OFFSET = (Double) v);
        r.slider("CITY_THRESHOLD", SettingCategory.CITIES, 0.0, 1.0, 0.01,
                p -> (double) p.CITY_THRESHOLD, (p, v) -> p.CITY_THRESHOLD = ((Double) v).floatValue());

        // Distance-based spawn scaling: block distances up to millions (0 = disabled, so a slider would pin the
        // default uselessly) paired with the multiplier applied past each ring.
        r.section("spawn_scaling");
        r.number("CITY_SPAWN_DISTANCE1", SettingCategory.CITIES, 0, 10000000, true,
                p -> (double) p.CITY_SPAWN_DISTANCE1, (p, v) -> p.CITY_SPAWN_DISTANCE1 = (int) Math.round((Double) v));
        r.number("CITY_SPAWN_DISTANCE2", SettingCategory.CITIES, 0, 10000000, true,
                p -> (double) p.CITY_SPAWN_DISTANCE2, (p, v) -> p.CITY_SPAWN_DISTANCE2 = (int) Math.round((Double) v));
        r.slider("CITY_SPAWN_MULTIPLIER1", SettingCategory.CITIES, 0.0, 1.0, 0.01,
                p -> p.CITY_SPAWN_MULTIPLIER1, (p, v) -> p.CITY_SPAWN_MULTIPLIER1 = (Double) v);
        r.slider("CITY_SPAWN_MULTIPLIER2", SettingCategory.CITIES, 0.0, 1.0, 0.01,
                p -> p.CITY_SPAWN_MULTIPLIER2, (p, v) -> p.CITY_SPAWN_MULTIPLIER2 = (Double) v);

        r.section("style");
        r.slider("CITY_STYLE_THRESHOLD", SettingCategory.CITIES, 0.0, 1.0, 0.01,
                p -> (double) p.CITY_STYLE_THRESHOLD, (p, v) -> p.CITY_STYLE_THRESHOLD = ((Double) v).floatValue());

        // Where a city is allowed to sit: the terrain-height gate in City.getCityFactor plus the void guard. The
        // config ceiling (-1024..2048) is far past any real world Y, so min/max height are tightened to the
        // vanilla buildable range so the handle tracks meaningful heights.
        r.section("placement");
        r.toggle("CITY_AVOID_VOID", SettingCategory.CITIES,
                p -> p.CITY_AVOID_VOID, (p, v) -> p.CITY_AVOID_VOID = (Boolean) v);
        r.slider("CITY_MINHEIGHT", SettingCategory.CITIES, -64, 384, 1,
                p -> (double) p.CITY_MINHEIGHT, (p, v) -> p.CITY_MINHEIGHT = (int) Math.round((Double) v));
        r.slider("CITY_MAXHEIGHT", SettingCategory.CITIES, -64, 384, 1,
                p -> (double) p.CITY_MAXHEIGHT, (p, v) -> p.CITY_MAXHEIGHT = (int) Math.round((Double) v));

        // The eight per-level Y offsets a city can stamp its floors at.
        r.section("levels");
        r.slider("CITY_LEVEL0_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL0_HEIGHT, (p, v) -> p.CITY_LEVEL0_HEIGHT = (int) Math.round((Double) v));
        r.slider("CITY_LEVEL1_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL1_HEIGHT, (p, v) -> p.CITY_LEVEL1_HEIGHT = (int) Math.round((Double) v));
        r.slider("CITY_LEVEL2_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL2_HEIGHT, (p, v) -> p.CITY_LEVEL2_HEIGHT = (int) Math.round((Double) v));
        r.slider("CITY_LEVEL3_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL3_HEIGHT, (p, v) -> p.CITY_LEVEL3_HEIGHT = (int) Math.round((Double) v));
        r.slider("CITY_LEVEL4_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL4_HEIGHT, (p, v) -> p.CITY_LEVEL4_HEIGHT = (int) Math.round((Double) v));
        r.slider("CITY_LEVEL5_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL5_HEIGHT, (p, v) -> p.CITY_LEVEL5_HEIGHT = (int) Math.round((Double) v));
        r.slider("CITY_LEVEL6_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL6_HEIGHT, (p, v) -> p.CITY_LEVEL6_HEIGHT = (int) Math.round((Double) v));
        r.slider("CITY_LEVEL7_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL7_HEIGHT, (p, v) -> p.CITY_LEVEL7_HEIGHT = (int) Math.round((Double) v));

        // ==== BUILDINGS ======================================================
        // Buildings, ruins, city features and foliage/rubble. (Floors and loot/lighting density live in GENERAL.)
        r.section("buildings");
        r.slider("BUILDING_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.BUILDING_CHANCE, (p, v) -> p.BUILDING_CHANCE = ((Double) v).floatValue());
        r.slider("BUILDING_MINFLOORS_CHANCE", SettingCategory.BUILDINGS, 1, 60, 1,
                p -> (double) p.BUILDING_MINFLOORS_CHANCE, (p, v) -> p.BUILDING_MINFLOORS_CHANCE = (int) Math.round((Double) v));
        r.slider("BUILDING_MAXFLOORS_CHANCE", SettingCategory.BUILDINGS, 1, 60, 1,
                p -> (double) p.BUILDING_MAXFLOORS_CHANCE, (p, v) -> p.BUILDING_MAXFLOORS_CHANCE = (int) Math.round((Double) v));
        r.slider("BUILDING_MINCELLARS", SettingCategory.BUILDINGS, 0, 20, 1,
                p -> (double) p.BUILDING_MINCELLARS, (p, v) -> p.BUILDING_MINCELLARS = (int) Math.round((Double) v));
        r.slider("BUILDING_MAXCELLARS", SettingCategory.BUILDINGS, 0, 20, 1,
                p -> (double) p.BUILDING_MAXCELLARS, (p, v) -> p.BUILDING_MAXCELLARS = (int) Math.round((Double) v));
        r.slider("BUILDING_DOORWAYCHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.BUILDING_DOORWAYCHANCE, (p, v) -> p.BUILDING_DOORWAYCHANCE = ((Double) v).floatValue());
        r.slider("BUILDING_FRONTCHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.BUILDING_FRONTCHANCE, (p, v) -> p.BUILDING_FRONTCHANCE = ((Double) v).floatValue());

        r.section("ruins");
        r.slider("RUIN_MINLEVEL_PERCENT", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.RUIN_MINLEVEL_PERCENT, (p, v) -> p.RUIN_MINLEVEL_PERCENT = ((Double) v).floatValue());
        r.slider("RUIN_MAXLEVEL_PERCENT", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.RUIN_MAXLEVEL_PERCENT, (p, v) -> p.RUIN_MAXLEVEL_PERCENT = ((Double) v).floatValue());

        // Parks, bridges, corridors and fountains: the non-building structures that fill the streets between them.
        r.section("features");
        r.slider("OPEN_LOT_PARK_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.OPEN_LOT_PARK_CHANCE, (p, v) -> p.OPEN_LOT_PARK_CHANCE = ((Double) v).floatValue());
        r.slider("CORRIDOR_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.CORRIDOR_CHANCE, (p, v) -> p.CORRIDOR_CHANCE = ((Double) v).floatValue());
        r.slider("BRIDGE_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.BRIDGE_CHANCE, (p, v) -> p.BRIDGE_CHANCE = ((Double) v).floatValue());
        r.slider("FOUNTAIN_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.FOUNTAIN_CHANCE, (p, v) -> p.FOUNTAIN_CHANCE = ((Double) v).floatValue());
        r.toggle("BRIDGE_SUPPORTS", SettingCategory.BUILDINGS,
                p -> p.BRIDGE_SUPPORTS, (p, v) -> p.BRIDGE_SUPPORTS = (Boolean) v);
        r.toggle("PARK_ELEVATION", SettingCategory.BUILDINGS,
                p -> p.PARK_ELEVATION, (p, v) -> p.PARK_ELEVATION = (Boolean) v);
        r.toggle("PARK_BORDER", SettingCategory.BUILDINGS,
                p -> p.PARK_BORDER, (p, v) -> p.PARK_BORDER = (Boolean) v);
        r.slider("PARK_STREET_THRESHOLD", SettingCategory.BUILDINGS, 0, 8, 1,
                p -> (double) p.PARK_STREET_THRESHOLD, (p, v) -> p.PARK_STREET_THRESHOLD = (int) Math.round((Double) v));

        // Overgrowth and debris: stray leaf blocks, the scattered-structure multiplier and the rubble layer.
        r.section("foliage");
        r.slider("CHANCE_OF_RANDOM_LEAFBLOCKS", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.CHANCE_OF_RANDOM_LEAFBLOCKS, (p, v) -> p.CHANCE_OF_RANDOM_LEAFBLOCKS = ((Double) v).floatValue());
        r.slider("THICKNESS_OF_RANDOM_LEAFBLOCKS", SettingCategory.BUILDINGS, 1, 8, 1,
                p -> (double) p.THICKNESS_OF_RANDOM_LEAFBLOCKS, (p, v) -> p.THICKNESS_OF_RANDOM_LEAFBLOCKS = (int) Math.round((Double) v));
        r.toggle("AVOID_FOLIAGE", SettingCategory.BUILDINGS,
                p -> p.AVOID_FOLIAGE, (p, v) -> p.AVOID_FOLIAGE = (Boolean) v);
        r.slider("SCATTERED_CHANCE_MULTIPLIER", SettingCategory.BUILDINGS, 0.0, 100.0, 0.1,
                p -> (double) p.SCATTERED_CHANCE_MULTIPLIER, (p, v) -> p.SCATTERED_CHANCE_MULTIPLIER = ((Double) v).floatValue());
        r.toggle("RUBBLELAYER", SettingCategory.BUILDINGS,
                p -> p.RUBBLELAYER, (p, v) -> p.RUBBLELAYER = (Boolean) v);
        r.slider("RUBBLE_DIRT_SCALE", SettingCategory.BUILDINGS, 0.0, 100.0, 0.1,
                p -> (double) p.RUBBLE_DIRT_SCALE, (p, v) -> p.RUBBLE_DIRT_SCALE = ((Double) v).floatValue());
        r.slider("RUBBLE_LEAVE_SCALE", SettingCategory.BUILDINGS, 0.0, 100.0, 0.1,
                p -> (double) p.RUBBLE_LEAVE_SCALE, (p, v) -> p.RUBBLE_LEAVE_SCALE = ((Double) v).floatValue());

        r.section("spawners");
        r.toggle("GENERATE_SPAWNERS", SettingCategory.BUILDINGS,
                p -> p.GENERATE_SPAWNERS, (p, v) -> p.GENERATE_SPAWNERS = (Boolean) v);

        // ==== DAMAGE =========================================================
        // Explosion radii/heights and debris overflow. (Explosion chances live in GENERAL.)
        r.section("explosions");
        r.slider("EXPLOSION_MINRADIUS", SettingCategory.DAMAGE, 1, 256, 1,
                p -> (double) p.EXPLOSION_MINRADIUS, (p, v) -> p.EXPLOSION_MINRADIUS = (int) Math.round((Double) v));
        r.slider("EXPLOSION_MAXRADIUS", SettingCategory.DAMAGE, 1, 256, 1,
                p -> (double) p.EXPLOSION_MAXRADIUS, (p, v) -> p.EXPLOSION_MAXRADIUS = (int) Math.round((Double) v));
        r.slider("EXPLOSION_MINHEIGHT", SettingCategory.DAMAGE, 1, 256, 1,
                p -> (double) p.EXPLOSION_MINHEIGHT, (p, v) -> p.EXPLOSION_MINHEIGHT = (int) Math.round((Double) v));
        r.slider("EXPLOSION_MAXHEIGHT", SettingCategory.DAMAGE, 1, 256, 1,
                p -> (double) p.EXPLOSION_MAXHEIGHT, (p, v) -> p.EXPLOSION_MAXHEIGHT = (int) Math.round((Double) v));
        r.toggle("EXPLOSIONS_IN_CITIES_ONLY", SettingCategory.DAMAGE,
                p -> p.EXPLOSIONS_IN_CITIES_ONLY, (p, v) -> p.EXPLOSIONS_IN_CITIES_ONLY = (Boolean) v);

        // Mini explosions are much smaller than the full-size ones (defaults 5/12), so their radii get a smaller
        // dedicated cap than the 1..256 above, keeping the default off the left edge.
        r.section("mini_explosions");
        r.slider("MINI_EXPLOSION_MINRADIUS", SettingCategory.DAMAGE, 1, 100, 1,
                p -> (double) p.MINI_EXPLOSION_MINRADIUS, (p, v) -> p.MINI_EXPLOSION_MINRADIUS = (int) Math.round((Double) v));
        r.slider("MINI_EXPLOSION_MAXRADIUS", SettingCategory.DAMAGE, 1, 100, 1,
                p -> (double) p.MINI_EXPLOSION_MAXRADIUS, (p, v) -> p.MINI_EXPLOSION_MAXRADIUS = (int) Math.round((Double) v));
        r.slider("MINI_EXPLOSION_MINHEIGHT", SettingCategory.DAMAGE, 1, 256, 1,
                p -> (double) p.MINI_EXPLOSION_MINHEIGHT, (p, v) -> p.MINI_EXPLOSION_MINHEIGHT = (int) Math.round((Double) v));
        r.slider("MINI_EXPLOSION_MAXHEIGHT", SettingCategory.DAMAGE, 1, 256, 1,
                p -> (double) p.MINI_EXPLOSION_MAXHEIGHT, (p, v) -> p.MINI_EXPLOSION_MAXHEIGHT = (int) Math.round((Double) v));

        r.section("debris");
        r.slider("DEBRIS_TO_NEARBYCHUNK_FACTOR", SettingCategory.DAMAGE, 1, 2000, 1,
                p -> (double) p.DEBRIS_TO_NEARBYCHUNK_FACTOR, (p, v) -> p.DEBRIS_TO_NEARBYCHUNK_FACTOR = (int) Math.round((Double) v));

        // ==== TRANSPORT ======================================================
        // The networks the Transport preview overlay actually draws: highways and railways.
        // The hierarchical street grid below has its own ROADS tab and its own preview.
        //
        // A power-of-two-minus-1 bit mask (config ceiling Integer.MAX_VALUE, default 7); only a handful of masks
        // are valid, so a slider is meaningless. Typed field instead.
        r.section("highways");
        r.toggle("HIGHWAY_REQUIRES_TWO_CITIES", SettingCategory.TRANSPORT,
                p -> p.HIGHWAY_REQUIRES_TWO_CITIES, (p, v) -> p.HIGHWAY_REQUIRES_TWO_CITIES = (Boolean) v);
        r.slider("HIGHWAY_LEVEL_FROM_CITIES_MODE", SettingCategory.TRANSPORT, 0, 3, 1,
                p -> (double) p.HIGHWAY_LEVEL_FROM_CITIES_MODE, (p, v) -> p.HIGHWAY_LEVEL_FROM_CITIES_MODE = (int) Math.round((Double) v));
        r.slider("HIGHWAY_MAINPERLIN_SCALE", SettingCategory.TRANSPORT, 1.0, 200.0, 0.1,
                p -> (double) p.HIGHWAY_MAINPERLIN_SCALE, (p, v) -> p.HIGHWAY_MAINPERLIN_SCALE = ((Double) v).floatValue());
        r.slider("HIGHWAY_SECONDARYPERLIN_SCALE", SettingCategory.TRANSPORT, 1.0, 100.0, 0.1,
                p -> (double) p.HIGHWAY_SECONDARYPERLIN_SCALE, (p, v) -> p.HIGHWAY_SECONDARYPERLIN_SCALE = ((Double) v).floatValue());
        // Threshold compared against the highway perlin output (4-octave PerlinNoiseGenerator14,
        // default 2.0). Bounded to a non-negative useful band: raising it makes highways rarer, and
        // the floor of 0.0 keeps the editor from setting a value below the perlin's output range,
        // which would make every chunk a "highway" (the worldgen scan is bounded regardless, but this
        // keeps the pathological value out of easy reach). Fine step for precise tuning.
        r.slider("HIGHWAY_PERLIN_FACTOR", SettingCategory.TRANSPORT, 0.0, 10.0, 0.05,
                p -> (double) p.HIGHWAY_PERLIN_FACTOR, (p, v) -> p.HIGHWAY_PERLIN_FACTOR = ((Double) v).floatValue());
        r.number("HIGHWAY_DISTANCE_MASK", SettingCategory.TRANSPORT, 0, Integer.MAX_VALUE, true,
                p -> (double) p.HIGHWAY_DISTANCE_MASK, (p, v) -> p.HIGHWAY_DISTANCE_MASK = (int) Math.round((Double) v));
        r.toggle("HIGHWAY_SUPPORTS", SettingCategory.TRANSPORT,
                p -> p.HIGHWAY_SUPPORTS, (p, v) -> p.HIGHWAY_SUPPORTS = (Boolean) v);

        r.section("railways");
        r.slider("RAILWAY_DUNGEON_CHANCE", SettingCategory.TRANSPORT, 0.0, 1.0, 0.01,
                p -> (double) p.RAILWAY_DUNGEON_CHANCE, (p, v) -> p.RAILWAY_DUNGEON_CHANCE = ((Double) v).floatValue());
        r.toggle("RAILWAYS_CAN_END", SettingCategory.TRANSPORT,
                p -> p.RAILWAYS_CAN_END, (p, v) -> p.RAILWAYS_CAN_END = (Boolean) v);
        r.toggle("RAILWAYS_ENABLED", SettingCategory.TRANSPORT,
                p -> p.RAILWAYS_ENABLED, (p, v) -> p.RAILWAYS_ENABLED = (Boolean) v);
        r.toggle("RAILWAY_STATIONS_ENABLED", SettingCategory.TRANSPORT,
                p -> p.RAILWAY_STATIONS_ENABLED, (p, v) -> p.RAILWAY_STATIONS_ENABLED = (Boolean) v);
        r.toggle("RAILWAY_SURFACE_STATIONS_ENABLED", SettingCategory.TRANSPORT,
                p -> p.RAILWAY_SURFACE_STATIONS_ENABLED, (p, v) -> p.RAILWAY_SURFACE_STATIONS_ENABLED = (Boolean) v);

        // ==== ROADS ==========================================================
        // The hierarchical road field: primary corridors, the secondary streets filling the blocks between
        // them, the access-road stubs inside those, and how primaries cross water. Every slider's own
        // min/max mirrors GridSettings' per-field validation exactly - but three pairs of these sliders
        // are independent controls over what GridSettings treats as one min/max pair (secondary count X,
        // secondary count Z, tertiary length), so dragging one past the other is a reachable, momentarily
        // inconsistent state that GridSettings' compact constructor correctly rejects. CityPreview.recompute
        // catches exactly that IllegalArgumentException and keeps showing the last good preview rather than
        // going blank mid-drag; a profile still inconsistent at world creation fails there, field named.
        r.section("roads_primary");
        r.slider("PRIMARY_ROAD_SPACING_X", SettingCategory.ROADS, 8, 128, 1,
                p -> (double) p.PRIMARY_ROAD_SPACING_X, (p, v) -> p.PRIMARY_ROAD_SPACING_X = (int) Math.round((Double) v));
        r.slider("PRIMARY_ROAD_SPACING_Z", SettingCategory.ROADS, 8, 128, 1,
                p -> (double) p.PRIMARY_ROAD_SPACING_Z, (p, v) -> p.PRIMARY_ROAD_SPACING_Z = (int) Math.round((Double) v));
        r.slider("PRIMARY_ROAD_OPTIONAL_CHANCE", SettingCategory.ROADS, 0.0, 1.0, 0.01,
                p -> (double) p.PRIMARY_ROAD_OPTIONAL_CHANCE, (p, v) -> p.PRIMARY_ROAD_OPTIONAL_CHANCE = ((Double) v).floatValue());
        r.slider("PRIMARY_ROAD_FORCE_EVERY", SettingCategory.ROADS, 1, 16, 1,
                p -> (double) p.PRIMARY_ROAD_FORCE_EVERY, (p, v) -> p.PRIMARY_ROAD_FORCE_EVERY = (int) Math.round((Double) v));

        r.section("roads_secondary");
        r.slider("SECONDARY_ROAD_MIN_COUNT_X", SettingCategory.ROADS, 0, 128, 1,
                p -> (double) p.SECONDARY_ROAD_MIN_COUNT_X, (p, v) -> p.SECONDARY_ROAD_MIN_COUNT_X = (int) Math.round((Double) v));
        r.slider("SECONDARY_ROAD_MAX_COUNT_X", SettingCategory.ROADS, 0, 128, 1,
                p -> (double) p.SECONDARY_ROAD_MAX_COUNT_X, (p, v) -> p.SECONDARY_ROAD_MAX_COUNT_X = (int) Math.round((Double) v));
        r.slider("SECONDARY_ROAD_MIN_COUNT_Z", SettingCategory.ROADS, 0, 128, 1,
                p -> (double) p.SECONDARY_ROAD_MIN_COUNT_Z, (p, v) -> p.SECONDARY_ROAD_MIN_COUNT_Z = (int) Math.round((Double) v));
        r.slider("SECONDARY_ROAD_MAX_COUNT_Z", SettingCategory.ROADS, 0, 128, 1,
                p -> (double) p.SECONDARY_ROAD_MAX_COUNT_Z, (p, v) -> p.SECONDARY_ROAD_MAX_COUNT_Z = (int) Math.round((Double) v));
        r.slider("MINIMUM_ROAD_SEPARATION", SettingCategory.ROADS, 2, 32, 1,
                p -> (double) p.MINIMUM_ROAD_SEPARATION, (p, v) -> p.MINIMUM_ROAD_SEPARATION = (int) Math.round((Double) v));
        r.slider("MINIMUM_ROAD_EDGE_DISTANCE", SettingCategory.ROADS, 2, 32, 1,
                p -> (double) p.MINIMUM_ROAD_EDGE_DISTANCE, (p, v) -> p.MINIMUM_ROAD_EDGE_DISTANCE = (int) Math.round((Double) v));

        r.section("roads_tertiary");
        r.slider("TERTIARY_ROAD_CHANCE", SettingCategory.ROADS, 0.0, 1.0, 0.01,
                p -> (double) p.TERTIARY_ROAD_CHANCE, (p, v) -> p.TERTIARY_ROAD_CHANCE = ((Double) v).floatValue());
        r.slider("TERTIARY_ROAD_MIN_LENGTH", SettingCategory.ROADS, 1, 32, 1,
                p -> (double) p.TERTIARY_ROAD_MIN_LENGTH, (p, v) -> p.TERTIARY_ROAD_MIN_LENGTH = (int) Math.round((Double) v));
        r.slider("TERTIARY_ROAD_MAX_LENGTH", SettingCategory.ROADS, 1, 32, 1,
                p -> (double) p.TERTIARY_ROAD_MAX_LENGTH, (p, v) -> p.TERTIARY_ROAD_MAX_LENGTH = (int) Math.round((Double) v));

        r.section("roads_bridges");
        r.slider("PLANNED_PRIMARY_BRIDGE_CHANCE", SettingCategory.ROADS, 0.0, 1.0, 0.01,
                p -> (double) p.PLANNED_PRIMARY_BRIDGE_CHANCE, (p, v) -> p.PLANNED_PRIMARY_BRIDGE_CHANCE = ((Double) v).floatValue());
        r.slider("PLANNED_PRIMARY_BRIDGE_MAX_LENGTH", SettingCategory.ROADS, 1, 64, 1,
                p -> (double) p.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH, (p, v) -> p.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH = (int) Math.round((Double) v));
        r.cycle("MULTI_BUILDING_STREET_CONFLICT", SettingCategory.ROADS,
                p -> p.MULTI_BUILDING_STREET_CONFLICT, (p, v) -> p.MULTI_BUILDING_STREET_CONFLICT = (MultiBuildingStreetConflict) v);

        // ==== TERRAIN ========================================================
        // Ground/sea level, terrain-adjustment offsets and bedrock. (Landscape type lives in GENERAL.)
        r.section("levels");
        r.slider("GROUNDLEVEL", SettingCategory.TERRAIN, 2, 256, 1,
                p -> (double) p.GROUNDLEVEL, (p, v) -> p.GROUNDLEVEL = (int) Math.round((Double) v));
        r.slider("SEALEVEL", SettingCategory.TERRAIN, -1, 256, 1,
                p -> (double) p.SEALEVEL, (p, v) -> p.SEALEVEL = (int) Math.round((Double) v));
        r.slider("BEDROCK_LAYER", SettingCategory.TERRAIN, 0, 10, 1,
                p -> (double) p.BEDROCK_LAYER, (p, v) -> p.BEDROCK_LAYER = (int) Math.round((Double) v));

        r.section("water");
        r.slider("OCEAN_CORRECTION_BORDER", SettingCategory.TERRAIN, -255, 255, 1,
                p -> (double) p.OCEAN_CORRECTION_BORDER, (p, v) -> p.OCEAN_CORRECTION_BORDER = (int) Math.round((Double) v));
        r.toggle("AVOID_WATER", SettingCategory.TERRAIN,
                p -> p.AVOID_WATER, (p, v) -> p.AVOID_WATER = (Boolean) v);

        // The min/max offsets that smooth city plots into surrounding terrain, split by lower and upper edge.
        r.section("adaptation");
        r.slider("TERRAIN_FIX_LOWER_MIN_OFFSET", SettingCategory.TERRAIN, -40, 40, 1,
                p -> (double) p.TERRAIN_FIX_LOWER_MIN_OFFSET, (p, v) -> p.TERRAIN_FIX_LOWER_MIN_OFFSET = (int) Math.round((Double) v));
        r.slider("TERRAIN_FIX_LOWER_MAX_OFFSET", SettingCategory.TERRAIN, -40, 40, 1,
                p -> (double) p.TERRAIN_FIX_LOWER_MAX_OFFSET, (p, v) -> p.TERRAIN_FIX_LOWER_MAX_OFFSET = (int) Math.round((Double) v));
        r.slider("TERRAIN_FIX_UPPER_MIN_OFFSET", SettingCategory.TERRAIN, -40, 40, 1,
                p -> (double) p.TERRAIN_FIX_UPPER_MIN_OFFSET, (p, v) -> p.TERRAIN_FIX_UPPER_MIN_OFFSET = (int) Math.round((Double) v));
        r.slider("TERRAIN_FIX_UPPER_MAX_OFFSET", SettingCategory.TERRAIN, -40, 40, 1,
                p -> (double) p.TERRAIN_FIX_UPPER_MAX_OFFSET, (p, v) -> p.TERRAIN_FIX_UPPER_MAX_OFFSET = (int) Math.round((Double) v));

        r.section("dimension");
        r.toggle("GENERATE_NETHER", SettingCategory.TERRAIN,
                p -> p.GENERATE_NETHER, (p, v) -> p.GENERATE_NETHER = (Boolean) v);

        // ==== SPAWN ==========================================================
        // Player spawn placement (identifier fields live under ADVANCED as TEXT).
        r.section("placement");
        r.toggle("SPAWN_NOT_IN_BUILDING", SettingCategory.SPAWN,
                p -> p.SPAWN_NOT_IN_BUILDING, (p, v) -> p.SPAWN_NOT_IN_BUILDING = (Boolean) v);
        r.toggle("FORCE_SPAWN_IN_BUILDING", SettingCategory.SPAWN,
                p -> p.FORCE_SPAWN_IN_BUILDING, (p, v) -> p.FORCE_SPAWN_IN_BUILDING = (Boolean) v);

        // The spawn-search knobs all run to large validation ceilings (radii to 100000, attempts to a million)
        // with defaults sitting in a sliver of any slider track, so all three are typed NUMBER fields.
        r.section("search");
        r.number("SPAWN_CHECK_RADIUS", SettingCategory.SPAWN, 1, 100000, true,
                p -> (double) p.SPAWN_CHECK_RADIUS, (p, v) -> p.SPAWN_CHECK_RADIUS = (int) Math.round((Double) v));
        r.number("SPAWN_RADIUS_INCREASE", SettingCategory.SPAWN, 1, 100000, true,
                p -> (double) p.SPAWN_RADIUS_INCREASE, (p, v) -> p.SPAWN_RADIUS_INCREASE = (int) Math.round((Double) v));
        r.number("SPAWN_CHECK_ATTEMPTS", SettingCategory.SPAWN, 1, 1000000, true,
                p -> (double) p.SPAWN_CHECK_ATTEMPTS, (p, v) -> p.SPAWN_CHECK_ATTEMPTS = (int) Math.round((Double) v));

        // ==== ADVANCED =======================================================
        // Identifier/list TEXT fields, low-level generation switches, and client fog/horizon tuning.
        r.section("identifiers");
        r.text("CITY_STYLE_ALTERNATIVE", SettingCategory.ADVANCED,
                p -> p.CITY_STYLE_ALTERNATIVE, (p, v) -> p.CITY_STYLE_ALTERNATIVE = (String) v);
        r.text("SPAWN_BIOME", SettingCategory.ADVANCED,
                p -> p.SPAWN_BIOME, (p, v) -> p.SPAWN_BIOME = (String) v);
        r.text("SPAWN_CITY", SettingCategory.ADVANCED,
                p -> p.SPAWN_CITY, (p, v) -> p.SPAWN_CITY = (String) v);
        r.text("FORCE_SPAWN_BUILDINGS", SettingCategory.ADVANCED,
                p -> p.FORCE_SPAWN_BUILDINGS, (p, v) -> p.FORCE_SPAWN_BUILDINGS = (String[]) v);
        r.text("FORCE_SPAWN_PARTS", SettingCategory.ADVANCED,
                p -> p.FORCE_SPAWN_PARTS, (p, v) -> p.FORCE_SPAWN_PARTS = (String[]) v);

        r.section("misc");
        r.toggle("MULTI_USE_CORNER", SettingCategory.ADVANCED,
                p -> p.MULTI_USE_CORNER, (p, v) -> p.MULTI_USE_CORNER = (Boolean) v);
        r.toggle("USE_AVG_HEIGHTMAP", SettingCategory.ADVANCED,
                p -> p.USE_AVG_HEIGHTMAP, (p, v) -> p.USE_AVG_HEIGHTMAP = (Boolean) v);

        r.section("visuals");
        r.slider("HORIZON", SettingCategory.ADVANCED, -1, 256, 1,
                p -> (double) p.HORIZON, (p, v) -> p.HORIZON = ((Double) v).floatValue());
        r.slider("FOG_RED", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_RED, (p, v) -> p.FOG_RED = ((Double) v).floatValue());
        r.slider("FOG_GREEN", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_GREEN, (p, v) -> p.FOG_GREEN = ((Double) v).floatValue());
        r.slider("FOG_BLUE", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_BLUE, (p, v) -> p.FOG_BLUE = ((Double) v).floatValue());
        r.slider("FOG_DENSITY", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_DENSITY, (p, v) -> p.FOG_DENSITY = ((Double) v).floatValue());

        return List.copyOf(r.all);
    }

    /** All descriptors for a tab, in declaration order. */
    public static List<SettingDescriptor> byCategory(SettingCategory category) {
        List<SettingDescriptor> result = new ArrayList<>();
        for (SettingDescriptor d : ALL) {
            if (d.category() == category) {
                result.add(d);
            }
        }
        return result;
    }

    /**
     * Case-insensitive substring match on the localized display name. Each field has a single descriptor, so
     * hits are already unique on {@link SettingDescriptor#key()}.
     */
    public static List<SettingDescriptor> search(String localizedQuery) {
        String needle = localizedQuery == null ? "" : localizedQuery.toLowerCase(Locale.ROOT).strip();
        List<SettingDescriptor> result = new ArrayList<>();
        if (needle.isEmpty()) {
            return result;
        }
        for (SettingDescriptor d : ALL) {
            String name = I18n.get(d.nameKey());
            if (name.toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(d);
            }
        }
        return result;
    }
}

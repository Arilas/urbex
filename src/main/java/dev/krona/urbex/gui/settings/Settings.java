package dev.krona.urbex.gui.settings;

import dev.krona.urbex.config.LandscapeType;
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
 * <p>{@link #ALL} holds one {@code general=false} descriptor per editable profile field (its "home" category)
 * plus a handful of {@code general=true} duplicates that also surface on the {@link SettingCategory#GENERAL} tab.
 * The {@code SettingsCompletenessTest} guarantees the home descriptors cover every public {@code UrbexProfile}
 * field exactly once (minus a small, justified excluded set).</p>
 *
 * <p>See {@link SettingDescriptor} for the boxing convention and the rationale for direct field access.</p>
 */
public final class Settings {

    public static final List<SettingDescriptor> ALL = buildAll();

    private Settings() {
    }

    // ---- descriptor builders ------------------------------------------------

    private static SettingDescriptor slider(String key, SettingCategory cat, double min, double max, double step,
                                            Function<UrbexProfile, Object> getter,
                                            BiConsumer<UrbexProfile, Object> setter) {
        return new SettingDescriptor(key, cat, false, ControlKind.SLIDER, min, max, step, false, getter, setter);
    }

    private static SettingDescriptor toggle(String key, SettingCategory cat,
                                            Function<UrbexProfile, Object> getter,
                                            BiConsumer<UrbexProfile, Object> setter) {
        return new SettingDescriptor(key, cat, false, ControlKind.TOGGLE, 0, 0, 0, false, getter, setter);
    }

    private static SettingDescriptor cycle(String key, SettingCategory cat,
                                           Function<UrbexProfile, Object> getter,
                                           BiConsumer<UrbexProfile, Object> setter) {
        return new SettingDescriptor(key, cat, false, ControlKind.CYCLE, 0, 0, 0, false, getter, setter);
    }

    private static SettingDescriptor text(String key, SettingCategory cat,
                                          Function<UrbexProfile, Object> getter,
                                          BiConsumer<UrbexProfile, Object> setter) {
        return new SettingDescriptor(key, cat, false, ControlKind.TEXT, 0, 0, 0, false, getter, setter);
    }

    /** General-tab duplicate that mirrors a home descriptor's control and bounds verbatim. */
    private static SettingDescriptor generalOf(SettingDescriptor d) {
        return new SettingDescriptor(d.key(), SettingCategory.GENERAL, true, d.kind(),
                d.min(), d.max(), d.step(), d.logScale(), d.getter(), d.setter());
    }

    /** General-tab duplicate that overrides the slider bounds/scale (used for the log-scale chance knobs). */
    private static SettingDescriptor generalSlider(SettingDescriptor d, double min, double max, double step, boolean logScale) {
        return new SettingDescriptor(d.key(), SettingCategory.GENERAL, true, ControlKind.SLIDER,
                min, max, step, logScale, d.getter(), d.setter());
    }

    private static List<SettingDescriptor> buildAll() {
        List<SettingDescriptor> all = new ArrayList<>();

        // ---- CITIES ---------------------------------------------------------
        // City placement, rarity, radius, per-level heights and thresholds.
        SettingDescriptor cityChance = slider("CITY_CHANCE", SettingCategory.CITIES, -1.0, 1.0, 0.001,
                p -> p.CITY_CHANCE, (p, v) -> p.CITY_CHANCE = (Double) v);
        all.add(cityChance);
        SettingDescriptor cityMinRadius = slider("CITY_MINRADIUS", SettingCategory.CITIES, 1, 2000, 1,
                p -> (double) p.CITY_MINRADIUS, (p, v) -> p.CITY_MINRADIUS = (int) Math.round((Double) v));
        all.add(cityMinRadius);
        SettingDescriptor cityMaxRadius = slider("CITY_MAXRADIUS", SettingCategory.CITIES, 1, 2000, 1,
                p -> (double) p.CITY_MAXRADIUS, (p, v) -> p.CITY_MAXRADIUS = (int) Math.round((Double) v));
        all.add(cityMaxRadius);
        all.add(slider("CITY_PERLIN_SCALE", SettingCategory.CITIES, -1000000, 1000000, 0.1,
                p -> p.CITY_PERLIN_SCALE, (p, v) -> p.CITY_PERLIN_SCALE = (Double) v));
        all.add(slider("CITY_PERLIN_INNERSCALE", SettingCategory.CITIES, -1000000, 1000000, 0.1,
                p -> p.CITY_PERLIN_INNERSCALE, (p, v) -> p.CITY_PERLIN_INNERSCALE = (Double) v));
        all.add(slider("CITY_PERLIN_OFFSET", SettingCategory.CITIES, -1000000, 1000000, 0.1,
                p -> p.CITY_PERLIN_OFFSET, (p, v) -> p.CITY_PERLIN_OFFSET = (Double) v));
        all.add(slider("CITY_THRESHOLD", SettingCategory.CITIES, 0.0, 1.0, 0.01,
                p -> (double) p.CITY_THRESHOLD, (p, v) -> p.CITY_THRESHOLD = ((Double) v).floatValue()));
        all.add(slider("CITY_SPAWN_DISTANCE1", SettingCategory.CITIES, 0, 10000000, 1,
                p -> (double) p.CITY_SPAWN_DISTANCE1, (p, v) -> p.CITY_SPAWN_DISTANCE1 = (int) Math.round((Double) v)));
        all.add(slider("CITY_SPAWN_DISTANCE2", SettingCategory.CITIES, 0, 10000000, 1,
                p -> (double) p.CITY_SPAWN_DISTANCE2, (p, v) -> p.CITY_SPAWN_DISTANCE2 = (int) Math.round((Double) v)));
        all.add(slider("CITY_SPAWN_MULTIPLIER1", SettingCategory.CITIES, 0.0, 1.0, 0.01,
                p -> p.CITY_SPAWN_MULTIPLIER1, (p, v) -> p.CITY_SPAWN_MULTIPLIER1 = (Double) v));
        all.add(slider("CITY_SPAWN_MULTIPLIER2", SettingCategory.CITIES, 0.0, 1.0, 0.01,
                p -> p.CITY_SPAWN_MULTIPLIER2, (p, v) -> p.CITY_SPAWN_MULTIPLIER2 = (Double) v));
        all.add(slider("CITY_STYLE_THRESHOLD", SettingCategory.CITIES, 0.0, 1.0, 0.01,
                p -> (double) p.CITY_STYLE_THRESHOLD, (p, v) -> p.CITY_STYLE_THRESHOLD = ((Double) v).floatValue()));
        all.add(toggle("CITY_AVOID_VOID", SettingCategory.CITIES,
                p -> p.CITY_AVOID_VOID, (p, v) -> p.CITY_AVOID_VOID = (Boolean) v));
        all.add(slider("CITY_LEVEL0_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL0_HEIGHT, (p, v) -> p.CITY_LEVEL0_HEIGHT = (int) Math.round((Double) v)));
        all.add(slider("CITY_LEVEL1_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL1_HEIGHT, (p, v) -> p.CITY_LEVEL1_HEIGHT = (int) Math.round((Double) v)));
        all.add(slider("CITY_LEVEL2_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL2_HEIGHT, (p, v) -> p.CITY_LEVEL2_HEIGHT = (int) Math.round((Double) v)));
        all.add(slider("CITY_LEVEL3_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL3_HEIGHT, (p, v) -> p.CITY_LEVEL3_HEIGHT = (int) Math.round((Double) v)));
        all.add(slider("CITY_LEVEL4_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL4_HEIGHT, (p, v) -> p.CITY_LEVEL4_HEIGHT = (int) Math.round((Double) v)));
        all.add(slider("CITY_LEVEL5_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL5_HEIGHT, (p, v) -> p.CITY_LEVEL5_HEIGHT = (int) Math.round((Double) v)));
        all.add(slider("CITY_LEVEL6_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL6_HEIGHT, (p, v) -> p.CITY_LEVEL6_HEIGHT = (int) Math.round((Double) v)));
        all.add(slider("CITY_LEVEL7_HEIGHT", SettingCategory.CITIES, 1, 384, 1,
                p -> (double) p.CITY_LEVEL7_HEIGHT, (p, v) -> p.CITY_LEVEL7_HEIGHT = (int) Math.round((Double) v)));
        all.add(slider("CITY_MINHEIGHT", SettingCategory.CITIES, -1024, 2048, 1,
                p -> (double) p.CITY_MINHEIGHT, (p, v) -> p.CITY_MINHEIGHT = (int) Math.round((Double) v)));
        all.add(slider("CITY_MAXHEIGHT", SettingCategory.CITIES, -1024, 2048, 1,
                p -> (double) p.CITY_MAXHEIGHT, (p, v) -> p.CITY_MAXHEIGHT = (int) Math.round((Double) v)));

        // ---- BUILDINGS ------------------------------------------------------
        // Buildings, ruins, parks, bridges, corridors, foliage/rubble and loot/lighting density.
        SettingDescriptor ruinChance = slider("RUIN_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.RUIN_CHANCE, (p, v) -> p.RUIN_CHANCE = ((Double) v).floatValue());
        all.add(ruinChance);
        all.add(slider("RUIN_MINLEVEL_PERCENT", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.RUIN_MINLEVEL_PERCENT, (p, v) -> p.RUIN_MINLEVEL_PERCENT = ((Double) v).floatValue()));
        all.add(slider("RUIN_MAXLEVEL_PERCENT", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.RUIN_MAXLEVEL_PERCENT, (p, v) -> p.RUIN_MAXLEVEL_PERCENT = ((Double) v).floatValue()));
        all.add(slider("VINE_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.VINE_CHANCE, (p, v) -> p.VINE_CHANCE = ((Double) v).floatValue()));
        all.add(slider("CHANCE_OF_RANDOM_LEAFBLOCKS", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.CHANCE_OF_RANDOM_LEAFBLOCKS, (p, v) -> p.CHANCE_OF_RANDOM_LEAFBLOCKS = ((Double) v).floatValue()));
        all.add(slider("THICKNESS_OF_RANDOM_LEAFBLOCKS", SettingCategory.BUILDINGS, 1, 8, 1,
                p -> (double) p.THICKNESS_OF_RANDOM_LEAFBLOCKS, (p, v) -> p.THICKNESS_OF_RANDOM_LEAFBLOCKS = (int) Math.round((Double) v)));
        all.add(toggle("AVOID_FOLIAGE", SettingCategory.BUILDINGS,
                p -> p.AVOID_FOLIAGE, (p, v) -> p.AVOID_FOLIAGE = (Boolean) v));
        all.add(slider("SCATTERED_CHANCE_MULTIPLIER", SettingCategory.BUILDINGS, 0.0, 100.0, 0.1,
                p -> (double) p.SCATTERED_CHANCE_MULTIPLIER, (p, v) -> p.SCATTERED_CHANCE_MULTIPLIER = ((Double) v).floatValue()));
        all.add(toggle("RUBBLELAYER", SettingCategory.BUILDINGS,
                p -> p.RUBBLELAYER, (p, v) -> p.RUBBLELAYER = (Boolean) v));
        all.add(slider("RUBBLE_DIRT_SCALE", SettingCategory.BUILDINGS, 0.0, 100.0, 0.1,
                p -> (double) p.RUBBLE_DIRT_SCALE, (p, v) -> p.RUBBLE_DIRT_SCALE = ((Double) v).floatValue()));
        all.add(slider("RUBBLE_LEAVE_SCALE", SettingCategory.BUILDINGS, 0.0, 100.0, 0.1,
                p -> (double) p.RUBBLE_LEAVE_SCALE, (p, v) -> p.RUBBLE_LEAVE_SCALE = ((Double) v).floatValue()));
        all.add(slider("BUILDING_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.BUILDING_CHANCE, (p, v) -> p.BUILDING_CHANCE = ((Double) v).floatValue()));
        SettingDescriptor buildingMinFloors = slider("BUILDING_MINFLOORS", SettingCategory.BUILDINGS, 0, 60, 1,
                p -> (double) p.BUILDING_MINFLOORS, (p, v) -> p.BUILDING_MINFLOORS = (int) Math.round((Double) v));
        all.add(buildingMinFloors);
        SettingDescriptor buildingMaxFloors = slider("BUILDING_MAXFLOORS", SettingCategory.BUILDINGS, 0, 60, 1,
                p -> (double) p.BUILDING_MAXFLOORS, (p, v) -> p.BUILDING_MAXFLOORS = (int) Math.round((Double) v));
        all.add(buildingMaxFloors);
        all.add(slider("BUILDING_MINFLOORS_CHANCE", SettingCategory.BUILDINGS, 1, 60, 1,
                p -> (double) p.BUILDING_MINFLOORS_CHANCE, (p, v) -> p.BUILDING_MINFLOORS_CHANCE = (int) Math.round((Double) v)));
        all.add(slider("BUILDING_MAXFLOORS_CHANCE", SettingCategory.BUILDINGS, 1, 60, 1,
                p -> (double) p.BUILDING_MAXFLOORS_CHANCE, (p, v) -> p.BUILDING_MAXFLOORS_CHANCE = (int) Math.round((Double) v)));
        all.add(slider("BUILDING_MINCELLARS", SettingCategory.BUILDINGS, 0, 20, 1,
                p -> (double) p.BUILDING_MINCELLARS, (p, v) -> p.BUILDING_MINCELLARS = (int) Math.round((Double) v)));
        all.add(slider("BUILDING_MAXCELLARS", SettingCategory.BUILDINGS, 0, 20, 1,
                p -> (double) p.BUILDING_MAXCELLARS, (p, v) -> p.BUILDING_MAXCELLARS = (int) Math.round((Double) v)));
        all.add(slider("BUILDING_DOORWAYCHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.BUILDING_DOORWAYCHANCE, (p, v) -> p.BUILDING_DOORWAYCHANCE = ((Double) v).floatValue()));
        all.add(slider("BUILDING_FRONTCHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.BUILDING_FRONTCHANCE, (p, v) -> p.BUILDING_FRONTCHANCE = ((Double) v).floatValue()));
        all.add(slider("PARK_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.PARK_CHANCE, (p, v) -> p.PARK_CHANCE = ((Double) v).floatValue()));
        all.add(slider("CORRIDOR_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.CORRIDOR_CHANCE, (p, v) -> p.CORRIDOR_CHANCE = ((Double) v).floatValue()));
        all.add(slider("BRIDGE_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.BRIDGE_CHANCE, (p, v) -> p.BRIDGE_CHANCE = ((Double) v).floatValue()));
        all.add(slider("FOUNTAIN_CHANCE", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.FOUNTAIN_CHANCE, (p, v) -> p.FOUNTAIN_CHANCE = ((Double) v).floatValue()));
        all.add(toggle("BRIDGE_SUPPORTS", SettingCategory.BUILDINGS,
                p -> p.BRIDGE_SUPPORTS, (p, v) -> p.BRIDGE_SUPPORTS = (Boolean) v));
        all.add(toggle("PARK_ELEVATION", SettingCategory.BUILDINGS,
                p -> p.PARK_ELEVATION, (p, v) -> p.PARK_ELEVATION = (Boolean) v));
        all.add(toggle("PARK_BORDER", SettingCategory.BUILDINGS,
                p -> p.PARK_BORDER, (p, v) -> p.PARK_BORDER = (Boolean) v));
        all.add(slider("PARK_STREET_THRESHOLD", SettingCategory.BUILDINGS, 0, 8, 1,
                p -> (double) p.PARK_STREET_THRESHOLD, (p, v) -> p.PARK_STREET_THRESHOLD = (int) Math.round((Double) v)));
        all.add(toggle("GENERATE_SPAWNERS", SettingCategory.BUILDINGS,
                p -> p.GENERATE_SPAWNERS, (p, v) -> p.GENERATE_SPAWNERS = (Boolean) v));
        SettingDescriptor lightingDensity = slider("LIGHTING_DENSITY", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.LIGHTING_DENSITY, (p, v) -> p.LIGHTING_DENSITY = ((Double) v).floatValue());
        all.add(lightingDensity);
        SettingDescriptor lootDensity = slider("LOOT_DENSITY", SettingCategory.BUILDINGS, 0.0, 1.0, 0.01,
                p -> (double) p.LOOT_DENSITY, (p, v) -> p.LOOT_DENSITY = ((Double) v).floatValue());
        all.add(lootDensity);

        // ---- DAMAGE ---------------------------------------------------------
        // Explosions, mini-explosions and debris overflow.
        all.add(slider("DEBRIS_TO_NEARBYCHUNK_FACTOR", SettingCategory.DAMAGE, 1, 10000, 1,
                p -> (double) p.DEBRIS_TO_NEARBYCHUNK_FACTOR, (p, v) -> p.DEBRIS_TO_NEARBYCHUNK_FACTOR = (int) Math.round((Double) v)));
        SettingDescriptor explosionChance = slider("EXPLOSION_CHANCE", SettingCategory.DAMAGE, 0.0, 1.0, 0.001,
                p -> (double) p.EXPLOSION_CHANCE, (p, v) -> p.EXPLOSION_CHANCE = ((Double) v).floatValue());
        all.add(explosionChance);
        all.add(slider("EXPLOSION_MINRADIUS", SettingCategory.DAMAGE, 1, 1000, 1,
                p -> (double) p.EXPLOSION_MINRADIUS, (p, v) -> p.EXPLOSION_MINRADIUS = (int) Math.round((Double) v)));
        all.add(slider("EXPLOSION_MAXRADIUS", SettingCategory.DAMAGE, 1, 3000, 1,
                p -> (double) p.EXPLOSION_MAXRADIUS, (p, v) -> p.EXPLOSION_MAXRADIUS = (int) Math.round((Double) v)));
        all.add(slider("EXPLOSION_MINHEIGHT", SettingCategory.DAMAGE, 1, 256, 1,
                p -> (double) p.EXPLOSION_MINHEIGHT, (p, v) -> p.EXPLOSION_MINHEIGHT = (int) Math.round((Double) v)));
        all.add(slider("EXPLOSION_MAXHEIGHT", SettingCategory.DAMAGE, 1, 256, 1,
                p -> (double) p.EXPLOSION_MAXHEIGHT, (p, v) -> p.EXPLOSION_MAXHEIGHT = (int) Math.round((Double) v)));
        SettingDescriptor miniExplosionChance = slider("MINI_EXPLOSION_CHANCE", SettingCategory.DAMAGE, 0.0, 1.0, 0.001,
                p -> (double) p.MINI_EXPLOSION_CHANCE, (p, v) -> p.MINI_EXPLOSION_CHANCE = ((Double) v).floatValue());
        all.add(miniExplosionChance);
        all.add(slider("MINI_EXPLOSION_MINRADIUS", SettingCategory.DAMAGE, 1, 1000, 1,
                p -> (double) p.MINI_EXPLOSION_MINRADIUS, (p, v) -> p.MINI_EXPLOSION_MINRADIUS = (int) Math.round((Double) v)));
        all.add(slider("MINI_EXPLOSION_MAXRADIUS", SettingCategory.DAMAGE, 1, 3000, 1,
                p -> (double) p.MINI_EXPLOSION_MAXRADIUS, (p, v) -> p.MINI_EXPLOSION_MAXRADIUS = (int) Math.round((Double) v)));
        all.add(slider("MINI_EXPLOSION_MINHEIGHT", SettingCategory.DAMAGE, 1, 256, 1,
                p -> (double) p.MINI_EXPLOSION_MINHEIGHT, (p, v) -> p.MINI_EXPLOSION_MINHEIGHT = (int) Math.round((Double) v)));
        all.add(slider("MINI_EXPLOSION_MAXHEIGHT", SettingCategory.DAMAGE, 1, 256, 1,
                p -> (double) p.MINI_EXPLOSION_MAXHEIGHT, (p, v) -> p.MINI_EXPLOSION_MAXHEIGHT = (int) Math.round((Double) v)));
        all.add(toggle("EXPLOSIONS_IN_CITIES_ONLY", SettingCategory.DAMAGE,
                p -> p.EXPLOSIONS_IN_CITIES_ONLY, (p, v) -> p.EXPLOSIONS_IN_CITIES_ONLY = (Boolean) v));

        // ---- TRANSPORT ------------------------------------------------------
        // Highways and railways.
        all.add(toggle("HIGHWAY_REQUIRES_TWO_CITIES", SettingCategory.TRANSPORT,
                p -> p.HIGHWAY_REQUIRES_TWO_CITIES, (p, v) -> p.HIGHWAY_REQUIRES_TWO_CITIES = (Boolean) v));
        all.add(slider("HIGHWAY_LEVEL_FROM_CITIES_MODE", SettingCategory.TRANSPORT, 0, 3, 1,
                p -> (double) p.HIGHWAY_LEVEL_FROM_CITIES_MODE, (p, v) -> p.HIGHWAY_LEVEL_FROM_CITIES_MODE = (int) Math.round((Double) v)));
        all.add(slider("HIGHWAY_MAINPERLIN_SCALE", SettingCategory.TRANSPORT, 1.0, 1000.0, 0.1,
                p -> (double) p.HIGHWAY_MAINPERLIN_SCALE, (p, v) -> p.HIGHWAY_MAINPERLIN_SCALE = ((Double) v).floatValue()));
        all.add(slider("HIGHWAY_SECONDARYPERLIN_SCALE", SettingCategory.TRANSPORT, 1.0, 1000.0, 0.1,
                p -> (double) p.HIGHWAY_SECONDARYPERLIN_SCALE, (p, v) -> p.HIGHWAY_SECONDARYPERLIN_SCALE = ((Double) v).floatValue()));
        all.add(slider("HIGHWAY_PERLIN_FACTOR", SettingCategory.TRANSPORT, -100.0, 100.0, 0.1,
                p -> (double) p.HIGHWAY_PERLIN_FACTOR, (p, v) -> p.HIGHWAY_PERLIN_FACTOR = ((Double) v).floatValue()));
        all.add(slider("HIGHWAY_DISTANCE_MASK", SettingCategory.TRANSPORT, 0, Integer.MAX_VALUE, 1,
                p -> (double) p.HIGHWAY_DISTANCE_MASK, (p, v) -> p.HIGHWAY_DISTANCE_MASK = (int) Math.round((Double) v)));
        all.add(toggle("HIGHWAY_SUPPORTS", SettingCategory.TRANSPORT,
                p -> p.HIGHWAY_SUPPORTS, (p, v) -> p.HIGHWAY_SUPPORTS = (Boolean) v));
        all.add(slider("RAILWAY_DUNGEON_CHANCE", SettingCategory.TRANSPORT, 0.0, 1.0, 0.01,
                p -> (double) p.RAILWAY_DUNGEON_CHANCE, (p, v) -> p.RAILWAY_DUNGEON_CHANCE = ((Double) v).floatValue()));
        all.add(toggle("RAILWAYS_CAN_END", SettingCategory.TRANSPORT,
                p -> p.RAILWAYS_CAN_END, (p, v) -> p.RAILWAYS_CAN_END = (Boolean) v));
        all.add(toggle("RAILWAYS_ENABLED", SettingCategory.TRANSPORT,
                p -> p.RAILWAYS_ENABLED, (p, v) -> p.RAILWAYS_ENABLED = (Boolean) v));
        all.add(toggle("RAILWAY_STATIONS_ENABLED", SettingCategory.TRANSPORT,
                p -> p.RAILWAY_STATIONS_ENABLED, (p, v) -> p.RAILWAY_STATIONS_ENABLED = (Boolean) v));
        all.add(toggle("RAILWAY_SURFACE_STATIONS_ENABLED", SettingCategory.TRANSPORT,
                p -> p.RAILWAY_SURFACE_STATIONS_ENABLED, (p, v) -> p.RAILWAY_SURFACE_STATIONS_ENABLED = (Boolean) v));

        // ---- SPHERES --------------------------------------------------------
        // City spheres (the 'space' landscape).
        all.add(toggle("CITYSPHERE_32GRID", SettingCategory.SPHERES,
                p -> p.CITYSPHERE_32GRID, (p, v) -> p.CITYSPHERE_32GRID = (Boolean) v));
        all.add(slider("CITYSPHERE_FACTOR", SettingCategory.SPHERES, 0.1, 10.0, 0.1,
                p -> (double) p.CITYSPHERE_FACTOR, (p, v) -> p.CITYSPHERE_FACTOR = ((Double) v).floatValue()));
        all.add(slider("CITYSPHERE_CHANCE", SettingCategory.SPHERES, 0.0, 1.0, 0.01,
                p -> (double) p.CITYSPHERE_CHANCE, (p, v) -> p.CITYSPHERE_CHANCE = ((Double) v).floatValue()));
        all.add(slider("CITYSPHERE_SURFACE_VARIATION", SettingCategory.SPHERES, 0.0, 1.0, 0.01,
                p -> (double) p.CITYSPHERE_SURFACE_VARIATION, (p, v) -> p.CITYSPHERE_SURFACE_VARIATION = ((Double) v).floatValue()));
        all.add(slider("CITYSPHERE_OUTSIDE_SURFACE_VARIATION", SettingCategory.SPHERES, 0.0, 1.0, 0.01,
                p -> (double) p.CITYSPHERE_OUTSIDE_SURFACE_VARIATION, (p, v) -> p.CITYSPHERE_OUTSIDE_SURFACE_VARIATION = ((Double) v).floatValue()));
        all.add(slider("CITYSPHERE_MONORAIL_CHANCE", SettingCategory.SPHERES, 0.0, 1.0, 0.01,
                p -> (double) p.CITYSPHERE_MONORAIL_CHANCE, (p, v) -> p.CITYSPHERE_MONORAIL_CHANCE = ((Double) v).floatValue()));
        all.add(slider("CITYSPHERE_CLEARABOVE", SettingCategory.SPHERES, 0, 1024, 1,
                p -> (double) p.CITYSPHERE_CLEARABOVE, (p, v) -> p.CITYSPHERE_CLEARABOVE = (int) Math.round((Double) v)));
        all.add(slider("CITYSPHERE_CLEARBELOW", SettingCategory.SPHERES, 0, 1024, 1,
                p -> (double) p.CITYSPHERE_CLEARBELOW, (p, v) -> p.CITYSPHERE_CLEARBELOW = (int) Math.round((Double) v)));
        all.add(toggle("CITYSPHERE_CLEARABOVE_UNTIL_AIR", SettingCategory.SPHERES,
                p -> p.CITYSPHERE_CLEARABOVE_UNTIL_AIR, (p, v) -> p.CITYSPHERE_CLEARABOVE_UNTIL_AIR = (Boolean) v));
        all.add(toggle("CITYSPHERE_CLEARBELOW_UNTIL_AIR", SettingCategory.SPHERES,
                p -> p.CITYSPHERE_CLEARBELOW_UNTIL_AIR, (p, v) -> p.CITYSPHERE_CLEARBELOW_UNTIL_AIR = (Boolean) v));
        all.add(slider("CITYSPHERE_OUTSIDE_GROUNDLEVEL", SettingCategory.SPHERES, -1, 256, 1,
                p -> (double) p.CITYSPHERE_OUTSIDE_GROUNDLEVEL, (p, v) -> p.CITYSPHERE_OUTSIDE_GROUNDLEVEL = (int) Math.round((Double) v)));
        all.add(toggle("CITYSPHERE_ONLY_PREDEFINED", SettingCategory.SPHERES,
                p -> p.CITYSPHERE_ONLY_PREDEFINED, (p, v) -> p.CITYSPHERE_ONLY_PREDEFINED = (Boolean) v));
        all.add(slider("CITYSPHERE_MONORAIL_HEIGHT_OFFSET", SettingCategory.SPHERES, -100, 100, 1,
                p -> (double) p.CITYSPHERE_MONORAIL_HEIGHT_OFFSET, (p, v) -> p.CITYSPHERE_MONORAIL_HEIGHT_OFFSET = (int) Math.round((Double) v)));

        // ---- TERRAIN --------------------------------------------------------
        // Ground/sea level, terrain-adjustment offsets, bedrock and the landscape type itself.
        SettingDescriptor landscapeType = cycle("LANDSCAPE_TYPE", SettingCategory.TERRAIN,
                p -> p.LANDSCAPE_TYPE, (p, v) -> p.LANDSCAPE_TYPE = (LandscapeType) v);
        all.add(landscapeType);
        all.add(slider("GROUNDLEVEL", SettingCategory.TERRAIN, 2, 256, 1,
                p -> (double) p.GROUNDLEVEL, (p, v) -> p.GROUNDLEVEL = (int) Math.round((Double) v)));
        all.add(slider("SEALEVEL", SettingCategory.TERRAIN, -1, 256, 1,
                p -> (double) p.SEALEVEL, (p, v) -> p.SEALEVEL = (int) Math.round((Double) v)));
        all.add(slider("OCEAN_CORRECTION_BORDER", SettingCategory.TERRAIN, -255, 255, 1,
                p -> (double) p.OCEAN_CORRECTION_BORDER, (p, v) -> p.OCEAN_CORRECTION_BORDER = (int) Math.round((Double) v)));
        all.add(slider("TERRAIN_FIX_LOWER_MIN_OFFSET", SettingCategory.TERRAIN, -40, 40, 1,
                p -> (double) p.TERRAIN_FIX_LOWER_MIN_OFFSET, (p, v) -> p.TERRAIN_FIX_LOWER_MIN_OFFSET = (int) Math.round((Double) v)));
        all.add(slider("TERRAIN_FIX_LOWER_MAX_OFFSET", SettingCategory.TERRAIN, -40, 40, 1,
                p -> (double) p.TERRAIN_FIX_LOWER_MAX_OFFSET, (p, v) -> p.TERRAIN_FIX_LOWER_MAX_OFFSET = (int) Math.round((Double) v)));
        all.add(slider("TERRAIN_FIX_UPPER_MIN_OFFSET", SettingCategory.TERRAIN, -40, 40, 1,
                p -> (double) p.TERRAIN_FIX_UPPER_MIN_OFFSET, (p, v) -> p.TERRAIN_FIX_UPPER_MIN_OFFSET = (int) Math.round((Double) v)));
        all.add(slider("TERRAIN_FIX_UPPER_MAX_OFFSET", SettingCategory.TERRAIN, -40, 40, 1,
                p -> (double) p.TERRAIN_FIX_UPPER_MAX_OFFSET, (p, v) -> p.TERRAIN_FIX_UPPER_MAX_OFFSET = (int) Math.round((Double) v)));
        all.add(slider("BEDROCK_LAYER", SettingCategory.TERRAIN, 0, 10, 1,
                p -> (double) p.BEDROCK_LAYER, (p, v) -> p.BEDROCK_LAYER = (int) Math.round((Double) v)));
        all.add(toggle("GENERATE_NETHER", SettingCategory.TERRAIN,
                p -> p.GENERATE_NETHER, (p, v) -> p.GENERATE_NETHER = (Boolean) v));
        all.add(toggle("AVOID_WATER", SettingCategory.TERRAIN,
                p -> p.AVOID_WATER, (p, v) -> p.AVOID_WATER = (Boolean) v));

        // ---- SPAWN ----------------------------------------------------------
        // Player spawn placement (identifier fields live under ADVANCED as TEXT).
        all.add(toggle("SPAWN_NOT_IN_BUILDING", SettingCategory.SPAWN,
                p -> p.SPAWN_NOT_IN_BUILDING, (p, v) -> p.SPAWN_NOT_IN_BUILDING = (Boolean) v));
        all.add(toggle("FORCE_SPAWN_IN_BUILDING", SettingCategory.SPAWN,
                p -> p.FORCE_SPAWN_IN_BUILDING, (p, v) -> p.FORCE_SPAWN_IN_BUILDING = (Boolean) v));
        all.add(slider("SPAWN_CHECK_RADIUS", SettingCategory.SPAWN, 1, 100000, 1,
                p -> (double) p.SPAWN_CHECK_RADIUS, (p, v) -> p.SPAWN_CHECK_RADIUS = (int) Math.round((Double) v)));
        all.add(slider("SPAWN_RADIUS_INCREASE", SettingCategory.SPAWN, 1, 100000, 1,
                p -> (double) p.SPAWN_RADIUS_INCREASE, (p, v) -> p.SPAWN_RADIUS_INCREASE = (int) Math.round((Double) v)));
        all.add(slider("SPAWN_CHECK_ATTEMPTS", SettingCategory.SPAWN, 1, 1000000, 1,
                p -> (double) p.SPAWN_CHECK_ATTEMPTS, (p, v) -> p.SPAWN_CHECK_ATTEMPTS = (int) Math.round((Double) v)));

        // ---- ADVANCED -------------------------------------------------------
        // Identifier/list TEXT fields, client fog/horizon tuning, and low-level performance switches.
        all.add(text("CITY_STYLE_ALTERNATIVE", SettingCategory.ADVANCED,
                p -> p.CITY_STYLE_ALTERNATIVE, (p, v) -> p.CITY_STYLE_ALTERNATIVE = (String) v));
        all.add(text("CITYSPHERE_OUTSIDE_PROFILE", SettingCategory.ADVANCED,
                p -> p.CITYSPHERE_OUTSIDE_PROFILE, (p, v) -> p.CITYSPHERE_OUTSIDE_PROFILE = (String) v));
        all.add(text("SPAWN_BIOME", SettingCategory.ADVANCED,
                p -> p.SPAWN_BIOME, (p, v) -> p.SPAWN_BIOME = (String) v));
        all.add(text("SPAWN_CITY", SettingCategory.ADVANCED,
                p -> p.SPAWN_CITY, (p, v) -> p.SPAWN_CITY = (String) v));
        all.add(text("SPAWN_SPHERE", SettingCategory.ADVANCED,
                p -> p.SPAWN_SPHERE, (p, v) -> p.SPAWN_SPHERE = (String) v));
        all.add(text("FORCE_SPAWN_BUILDINGS", SettingCategory.ADVANCED,
                p -> p.FORCE_SPAWN_BUILDINGS, (p, v) -> p.FORCE_SPAWN_BUILDINGS = (String[]) v));
        all.add(text("FORCE_SPAWN_PARTS", SettingCategory.ADVANCED,
                p -> p.FORCE_SPAWN_PARTS, (p, v) -> p.FORCE_SPAWN_PARTS = (String[]) v));
        all.add(toggle("MULTI_USE_CORNER", SettingCategory.ADVANCED,
                p -> p.MULTI_USE_CORNER, (p, v) -> p.MULTI_USE_CORNER = (Boolean) v));
        all.add(toggle("USE_AVG_HEIGHTMAP", SettingCategory.ADVANCED,
                p -> p.USE_AVG_HEIGHTMAP, (p, v) -> p.USE_AVG_HEIGHTMAP = (Boolean) v));
        all.add(slider("HORIZON", SettingCategory.ADVANCED, -1, 256, 1,
                p -> (double) p.HORIZON, (p, v) -> p.HORIZON = ((Double) v).floatValue()));
        all.add(slider("FOG_RED", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_RED, (p, v) -> p.FOG_RED = ((Double) v).floatValue()));
        all.add(slider("FOG_GREEN", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_GREEN, (p, v) -> p.FOG_GREEN = ((Double) v).floatValue()));
        all.add(slider("FOG_BLUE", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_BLUE, (p, v) -> p.FOG_BLUE = ((Double) v).floatValue()));
        all.add(slider("FOG_DENSITY", SettingCategory.ADVANCED, -1.0, 1.0, 0.01,
                p -> (double) p.FOG_DENSITY, (p, v) -> p.FOG_DENSITY = ((Double) v).floatValue()));

        // ---- GENERAL (curated duplicates) -----------------------------------
        // Same field, same getter/setter; a second copy flagged general so the General tab can show the
        // highest-impact knobs. The chance knobs switch to a log scale with min > 0 here.
        all.add(generalSlider(cityChance, 0.0001, 1.0, 0.0001, true));
        all.add(generalOf(cityMinRadius));
        all.add(generalOf(cityMaxRadius));
        all.add(generalOf(buildingMinFloors));
        all.add(generalOf(buildingMaxFloors));
        all.add(generalOf(ruinChance));
        all.add(generalSlider(explosionChance, 0.0001, 1.0, 0.0001, true));
        all.add(generalSlider(miniExplosionChance, 0.0001, 1.0, 0.0001, true));
        all.add(generalOf(lootDensity));
        all.add(generalOf(lightingDensity));
        all.add(generalOf(landscapeType));

        return List.copyOf(all);
    }

    /** All descriptors for a tab. For {@link SettingCategory#GENERAL} this is the curated general set. */
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
     * Case-insensitive substring match on the localized display name. Duplicate keys (a home descriptor and its
     * general duplicate) can both match; callers that want uniqueness should de-duplicate on {@link SettingDescriptor#key()}.
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

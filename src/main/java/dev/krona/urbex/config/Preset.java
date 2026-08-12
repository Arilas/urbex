package dev.krona.urbex.config;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.setup.ModSetup;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.AtmosphereSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.BuildingSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.CitySettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.DecorationSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.DestructionSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.HighwaySettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.MiscSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.RailwaySettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.RoadSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.SpawnSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.preset.TerrainSettings;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * A fully-resolved preset: the runtime settings used by worldgen, produced by walking a
 * {@link PresetRE}'s extends chain onto these code defaults (see {@code Presets.resolve}).
 * <p>
 * Public field names match the runtime-generated profile format this class replaced (same names,
 * same types) so that the worldgen consumers migrated by a pure type rename. Exceptions: there is
 * no {@code worldStyle} field (world style selection is now a separate first-class value), no
 * {@code isPublic}, and no config-file binding of any kind. {@link #USE_AVG_HEIGHTMAP} defaults
 * to {@code true} here, unlike the old default of {@code false}.
 */
public class Preset {

    private final Identifier id;

    private String name = "";
    private String description = "Default generation, common cities, explosions";
    private String extraDescription = "";
    private String warning = "";
    private String iconFile = "";
    private Identifier icon;

    private BlockState liquidBlock;
    private BlockState baseBlock;

    public LandscapeType LANDSCAPE_TYPE = LandscapeType.DEFAULT;
    public int GROUNDLEVEL = 71;
    public int SEALEVEL = -1;
    public String LIQUID_BLOCK = "minecraft:water";
    public String BASE_BLOCK = "minecraft:stone";
    public int BEDROCK_LAYER = 1;
    public int TERRAIN_FIX_LOWER_MIN_OFFSET = -4;
    public int TERRAIN_FIX_LOWER_MAX_OFFSET = -3;
    public int TERRAIN_FIX_UPPER_MIN_OFFSET = -1;
    public int TERRAIN_FIX_UPPER_MAX_OFFSET = 1;
    public int OCEAN_CORRECTION_BORDER = 4;
    public boolean AVOID_WATER = false;
    public boolean USE_AVG_HEIGHTMAP = true;
    public double CITY_CHANCE = .01;
    public int CITY_MINRADIUS = 50;
    public int CITY_MAXRADIUS = 128;
    public double CITY_PERLIN_SCALE = 3;
    public double CITY_PERLIN_OFFSET = .1;
    public double CITY_PERLIN_INNERSCALE = .1;
    public float CITY_THRESHOLD = .2f;
    public int CITY_SPAWN_DISTANCE1 = 0;
    public int CITY_SPAWN_DISTANCE2 = 0;
    public double CITY_SPAWN_MULTIPLIER1 = 1.0;
    public double CITY_SPAWN_MULTIPLIER2 = 1.0;
    public float CITY_STYLE_THRESHOLD = -1f;
    public String CITY_STYLE_ALTERNATIVE = "";
    public boolean CITY_AVOID_VOID = true;
    public int CITY_LEVEL0_HEIGHT = 75;
    public int CITY_LEVEL1_HEIGHT = 83;
    public int CITY_LEVEL2_HEIGHT = 91;
    public int CITY_LEVEL3_HEIGHT = 99;
    public int CITY_LEVEL4_HEIGHT = 107;
    public int CITY_LEVEL5_HEIGHT = 115;
    public int CITY_LEVEL6_HEIGHT = 123;
    public int CITY_LEVEL7_HEIGHT = 131;
    public int CITY_MINHEIGHT = 50;
    public int CITY_MAXHEIGHT = 150;
    public float SCATTERED_CHANCE_MULTIPLIER = 1.0f;
    public float BUILDING_CHANCE = .3f;
    public int BUILDING_MINFLOORS = 0;
    public int BUILDING_MAXFLOORS = 8;
    public int BUILDING_MINFLOORS_CHANCE = 4;
    public int BUILDING_MAXFLOORS_CHANCE = 6;
    public int BUILDING_MINCELLARS = 0;
    public int BUILDING_MAXCELLARS = 3;
    public float BUILDING_DOORWAYCHANCE = .6f;
    public float BUILDING_FRONTCHANCE = .2f;
    public boolean MULTI_USE_CORNER = false;
    public MultiBuildingStreetConflict MULTI_BUILDING_STREET_CONFLICT = MultiBuildingStreetConflict.OVERRIDE_MINOR;
    public boolean GENERATE_SPAWNERS = true;
    public int PRIMARY_ROAD_SPACING_X = 8;
    public int PRIMARY_ROAD_SPACING_Z = 8;
    public float PRIMARY_ROAD_OPTIONAL_CHANCE = .45f;
    public int PRIMARY_ROAD_FORCE_EVERY = 4;
    public int SECONDARY_ROAD_MIN_COUNT_X = 0;
    public int SECONDARY_ROAD_MAX_COUNT_X = 2;
    public int SECONDARY_ROAD_MIN_COUNT_Z = 0;
    public int SECONDARY_ROAD_MAX_COUNT_Z = 2;
    public int MINIMUM_ROAD_SEPARATION = 4;
    public int MINIMUM_ROAD_EDGE_DISTANCE = 3;
    public float TERTIARY_ROAD_CHANCE = .40f;
    public int TERTIARY_ROAD_MIN_LENGTH = 2;
    public int TERTIARY_ROAD_MAX_LENGTH = 5;
    public float PLANNED_PRIMARY_BRIDGE_CHANCE = 1.0f;
    public int PLANNED_PRIMARY_BRIDGE_MAX_LENGTH = 12;
    public float OPEN_LOT_PARK_CHANCE = .8f;
    public boolean PARK_ELEVATION = true;
    public boolean PARK_BORDER = true;
    public int PARK_STREET_THRESHOLD = 3;
    public float FOUNTAIN_CHANCE = .05f;
    public float CORRIDOR_CHANCE = .7f;
    public float BRIDGE_CHANCE = .7f;
    public boolean BRIDGE_SUPPORTS = true;
    public boolean HIGHWAY_REQUIRES_TWO_CITIES = true;
    public int HIGHWAY_LEVEL_FROM_CITIES_MODE = 0;
    public int HIGHWAY_DISTANCE_MASK = 7;
    public float HIGHWAY_MAINPERLIN_SCALE = 50.0f;
    public float HIGHWAY_SECONDARYPERLIN_SCALE = 10.0f;
    public float HIGHWAY_PERLIN_FACTOR = 2.0f;
    public boolean HIGHWAY_SUPPORTS = true;
    public boolean RAILWAYS_ENABLED = true;
    public boolean RAILWAY_STATIONS_ENABLED = true;
    public boolean RAILWAY_SURFACE_STATIONS_ENABLED = true;
    public boolean RAILWAYS_CAN_END = false;
    public float RAILWAY_DUNGEON_CHANCE = .01f;
    public float RUIN_CHANCE = 0.05f;
    public float RUIN_MINLEVEL_PERCENT = 0.8f;
    public float RUIN_MAXLEVEL_PERCENT = 1.0f;
    public boolean RUBBLELAYER = true;
    public float RUBBLE_DIRT_SCALE = 3.0f;
    public float RUBBLE_LEAVE_SCALE = 6.0f;
    public float EXPLOSION_CHANCE = .002f;
    public int EXPLOSION_MINRADIUS = 15;
    public int EXPLOSION_MAXRADIUS = 35;
    public int EXPLOSION_MINHEIGHT = 75;
    public int EXPLOSION_MAXHEIGHT = 90;
    public float MINI_EXPLOSION_CHANCE = .03f;
    public int MINI_EXPLOSION_MINRADIUS = 5;
    public int MINI_EXPLOSION_MAXRADIUS = 12;
    public int MINI_EXPLOSION_MINHEIGHT = 60;
    public int MINI_EXPLOSION_MAXHEIGHT = 100;
    public boolean EXPLOSIONS_IN_CITIES_ONLY = true;
    public int DEBRIS_TO_NEARBYCHUNK_FACTOR = 200;
    public float CHANCE_OF_RANDOM_LEAFBLOCKS = .1f;
    public int THICKNESS_OF_RANDOM_LEAFBLOCKS = 2;
    public boolean AVOID_FOLIAGE = false;
    public float LIGHTING_DENSITY = 0.15f;
    public float LOOT_DENSITY = 0.65f;
    public String SPAWN_BIOME = "";
    public String SPAWN_CITY = "";
    public boolean SPAWN_NOT_IN_BUILDING = false;
    public boolean FORCE_SPAWN_IN_BUILDING = false;
    public List<String> FORCE_SPAWN_BUILDINGS = List.of();
    public List<String> FORCE_SPAWN_PARTS = List.of();
    public int SPAWN_CHECK_RADIUS = 200;
    public int SPAWN_RADIUS_INCREASE = 100;
    public int SPAWN_CHECK_ATTEMPTS = 20000;
    public float HORIZON = -1f;
    public float FOG_RED = -1.0f;
    public float FOG_GREEN = -1.0f;
    public float FOG_BLUE = -1.0f;
    public float FOG_DENSITY = -1.0f;
    public boolean EDITMODE = false;
    public boolean GENERATE_NETHER = false;

    public Preset(Identifier id) {
        this.id = id;
    }

    public Identifier getId() {
        return id;
    }

    /**
     * The authored display name, or {@code ""} when nothing in the {@code extends} chain declared
     * one. Callers rendering a label want {@link #getDisplayName()}, which fills that gap in; this
     * raw form exists so {@link #toRE()} round-trips "no name was authored" as an empty string
     * rather than inventing the id as an authored value.
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * What a UI should label this preset: the authored {@code name}, falling back to the
     * fully-qualified id. The fallback is what every preset showed before the field existed, so a
     * datapack that declares no name reads exactly as it did then rather than going blank.
     */
    public String getDisplayName() {
        return name == null || name.isEmpty() ? id.toString() : name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExtraDescription() {
        return extraDescription;
    }

    public void setExtraDescription(String extraDescription) {
        this.extraDescription = extraDescription;
    }

    public String getWarning() {
        return warning;
    }

    public void setWarning(String warning) {
        this.warning = warning;
    }

    public void setIconFile(String iconFile) {
        this.iconFile = iconFile;
        this.icon = null;
    }

    public String getIconFile() {
        return iconFile;
    }

    public Identifier getIcon() {
        if (icon != null) {
            return icon;
        }
        if (iconFile == null || iconFile.isEmpty()) {
            return null;
        }
        icon = Identifier.fromNamespaceAndPath(Urbex.MODID, iconFile);
        return icon;
    }

    public boolean isDefault() {
        return LANDSCAPE_TYPE == LandscapeType.DEFAULT;
    }

    public boolean isFloating() {
        return LANDSCAPE_TYPE == LandscapeType.FLOATING;
    }

    public boolean isCavern() {
        return LANDSCAPE_TYPE == LandscapeType.CAVERN;
    }

    public BlockState getLiquidBlock() {
        if (liquidBlock == null) {
            Optional<Holder.Reference<Block>> b = BuiltInRegistries.BLOCK.get(Identifier.parse(LIQUID_BLOCK));
            if (b.isEmpty()) {
                ModSetup.getLogger().error("Bad liquid block: {}!", LIQUID_BLOCK);
                liquidBlock = Blocks.WATER.defaultBlockState();
            } else {
                liquidBlock = b.get().value().defaultBlockState();
            }
        }
        return liquidBlock;
    }

    public BlockState getBaseBlock() {
        if (baseBlock == null) {
            Optional<Holder.Reference<Block>> b = BuiltInRegistries.BLOCK.get(Identifier.parse(BASE_BLOCK));
            if (b.isEmpty()) {
                ModSetup.getLogger().error("Bad base block: {}!", BASE_BLOCK);
                baseBlock = Blocks.STONE.defaultBlockState();
            } else {
                baseBlock = b.get().value().defaultBlockState();
            }
        }
        return baseBlock;
    }

    /** Field-by-field clone, used by the GUI editor to stage changes without mutating the original. */
    public Preset copy() {
        Preset p = new Preset(id);
        p.name = name;
        p.description = description;
        p.extraDescription = extraDescription;
        p.warning = warning;
        p.iconFile = iconFile;
        p.icon = icon;
        // liquidBlock/baseBlock (the resolved BlockState cache) deliberately do NOT carry over:
        // TerrainSettings.apply() writes LIQUID_BLOCK/BASE_BLOCK (the string fields) directly, with
        // no setter to invalidate a cache. Presets.applyOverrides() is copy() followed by an
        // apply() of the override's sections; if the base's cache was already warmed (getLiquidBlock()
        // called before the override) and the override changes the block string, a copied cache
        // would make getLiquidBlock()/getBaseBlock() keep returning the pre-override block. Leaving
        // them null here forces a fresh lazy resolve against whatever LIQUID_BLOCK/BASE_BLOCK ends
        // up being on the copy.

        p.LANDSCAPE_TYPE = LANDSCAPE_TYPE;
        p.GROUNDLEVEL = GROUNDLEVEL;
        p.SEALEVEL = SEALEVEL;
        p.LIQUID_BLOCK = LIQUID_BLOCK;
        p.BASE_BLOCK = BASE_BLOCK;
        p.BEDROCK_LAYER = BEDROCK_LAYER;
        p.TERRAIN_FIX_LOWER_MIN_OFFSET = TERRAIN_FIX_LOWER_MIN_OFFSET;
        p.TERRAIN_FIX_LOWER_MAX_OFFSET = TERRAIN_FIX_LOWER_MAX_OFFSET;
        p.TERRAIN_FIX_UPPER_MIN_OFFSET = TERRAIN_FIX_UPPER_MIN_OFFSET;
        p.TERRAIN_FIX_UPPER_MAX_OFFSET = TERRAIN_FIX_UPPER_MAX_OFFSET;
        p.OCEAN_CORRECTION_BORDER = OCEAN_CORRECTION_BORDER;
        p.AVOID_WATER = AVOID_WATER;
        p.USE_AVG_HEIGHTMAP = USE_AVG_HEIGHTMAP;
        p.CITY_CHANCE = CITY_CHANCE;
        p.CITY_MINRADIUS = CITY_MINRADIUS;
        p.CITY_MAXRADIUS = CITY_MAXRADIUS;
        p.CITY_PERLIN_SCALE = CITY_PERLIN_SCALE;
        p.CITY_PERLIN_OFFSET = CITY_PERLIN_OFFSET;
        p.CITY_PERLIN_INNERSCALE = CITY_PERLIN_INNERSCALE;
        p.CITY_THRESHOLD = CITY_THRESHOLD;
        p.CITY_SPAWN_DISTANCE1 = CITY_SPAWN_DISTANCE1;
        p.CITY_SPAWN_DISTANCE2 = CITY_SPAWN_DISTANCE2;
        p.CITY_SPAWN_MULTIPLIER1 = CITY_SPAWN_MULTIPLIER1;
        p.CITY_SPAWN_MULTIPLIER2 = CITY_SPAWN_MULTIPLIER2;
        p.CITY_STYLE_THRESHOLD = CITY_STYLE_THRESHOLD;
        p.CITY_STYLE_ALTERNATIVE = CITY_STYLE_ALTERNATIVE;
        p.CITY_AVOID_VOID = CITY_AVOID_VOID;
        p.CITY_LEVEL0_HEIGHT = CITY_LEVEL0_HEIGHT;
        p.CITY_LEVEL1_HEIGHT = CITY_LEVEL1_HEIGHT;
        p.CITY_LEVEL2_HEIGHT = CITY_LEVEL2_HEIGHT;
        p.CITY_LEVEL3_HEIGHT = CITY_LEVEL3_HEIGHT;
        p.CITY_LEVEL4_HEIGHT = CITY_LEVEL4_HEIGHT;
        p.CITY_LEVEL5_HEIGHT = CITY_LEVEL5_HEIGHT;
        p.CITY_LEVEL6_HEIGHT = CITY_LEVEL6_HEIGHT;
        p.CITY_LEVEL7_HEIGHT = CITY_LEVEL7_HEIGHT;
        p.CITY_MINHEIGHT = CITY_MINHEIGHT;
        p.CITY_MAXHEIGHT = CITY_MAXHEIGHT;
        p.SCATTERED_CHANCE_MULTIPLIER = SCATTERED_CHANCE_MULTIPLIER;
        p.BUILDING_CHANCE = BUILDING_CHANCE;
        p.BUILDING_MINFLOORS = BUILDING_MINFLOORS;
        p.BUILDING_MAXFLOORS = BUILDING_MAXFLOORS;
        p.BUILDING_MINFLOORS_CHANCE = BUILDING_MINFLOORS_CHANCE;
        p.BUILDING_MAXFLOORS_CHANCE = BUILDING_MAXFLOORS_CHANCE;
        p.BUILDING_MINCELLARS = BUILDING_MINCELLARS;
        p.BUILDING_MAXCELLARS = BUILDING_MAXCELLARS;
        p.BUILDING_DOORWAYCHANCE = BUILDING_DOORWAYCHANCE;
        p.BUILDING_FRONTCHANCE = BUILDING_FRONTCHANCE;
        p.MULTI_USE_CORNER = MULTI_USE_CORNER;
        p.MULTI_BUILDING_STREET_CONFLICT = MULTI_BUILDING_STREET_CONFLICT;
        p.GENERATE_SPAWNERS = GENERATE_SPAWNERS;
        p.PRIMARY_ROAD_SPACING_X = PRIMARY_ROAD_SPACING_X;
        p.PRIMARY_ROAD_SPACING_Z = PRIMARY_ROAD_SPACING_Z;
        p.PRIMARY_ROAD_OPTIONAL_CHANCE = PRIMARY_ROAD_OPTIONAL_CHANCE;
        p.PRIMARY_ROAD_FORCE_EVERY = PRIMARY_ROAD_FORCE_EVERY;
        p.SECONDARY_ROAD_MIN_COUNT_X = SECONDARY_ROAD_MIN_COUNT_X;
        p.SECONDARY_ROAD_MAX_COUNT_X = SECONDARY_ROAD_MAX_COUNT_X;
        p.SECONDARY_ROAD_MIN_COUNT_Z = SECONDARY_ROAD_MIN_COUNT_Z;
        p.SECONDARY_ROAD_MAX_COUNT_Z = SECONDARY_ROAD_MAX_COUNT_Z;
        p.MINIMUM_ROAD_SEPARATION = MINIMUM_ROAD_SEPARATION;
        p.MINIMUM_ROAD_EDGE_DISTANCE = MINIMUM_ROAD_EDGE_DISTANCE;
        p.TERTIARY_ROAD_CHANCE = TERTIARY_ROAD_CHANCE;
        p.TERTIARY_ROAD_MIN_LENGTH = TERTIARY_ROAD_MIN_LENGTH;
        p.TERTIARY_ROAD_MAX_LENGTH = TERTIARY_ROAD_MAX_LENGTH;
        p.PLANNED_PRIMARY_BRIDGE_CHANCE = PLANNED_PRIMARY_BRIDGE_CHANCE;
        p.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH = PLANNED_PRIMARY_BRIDGE_MAX_LENGTH;
        p.OPEN_LOT_PARK_CHANCE = OPEN_LOT_PARK_CHANCE;
        p.PARK_ELEVATION = PARK_ELEVATION;
        p.PARK_BORDER = PARK_BORDER;
        p.PARK_STREET_THRESHOLD = PARK_STREET_THRESHOLD;
        p.FOUNTAIN_CHANCE = FOUNTAIN_CHANCE;
        p.CORRIDOR_CHANCE = CORRIDOR_CHANCE;
        p.BRIDGE_CHANCE = BRIDGE_CHANCE;
        p.BRIDGE_SUPPORTS = BRIDGE_SUPPORTS;
        p.HIGHWAY_REQUIRES_TWO_CITIES = HIGHWAY_REQUIRES_TWO_CITIES;
        p.HIGHWAY_LEVEL_FROM_CITIES_MODE = HIGHWAY_LEVEL_FROM_CITIES_MODE;
        p.HIGHWAY_DISTANCE_MASK = HIGHWAY_DISTANCE_MASK;
        p.HIGHWAY_MAINPERLIN_SCALE = HIGHWAY_MAINPERLIN_SCALE;
        p.HIGHWAY_SECONDARYPERLIN_SCALE = HIGHWAY_SECONDARYPERLIN_SCALE;
        p.HIGHWAY_PERLIN_FACTOR = HIGHWAY_PERLIN_FACTOR;
        p.HIGHWAY_SUPPORTS = HIGHWAY_SUPPORTS;
        p.RAILWAYS_ENABLED = RAILWAYS_ENABLED;
        p.RAILWAY_STATIONS_ENABLED = RAILWAY_STATIONS_ENABLED;
        p.RAILWAY_SURFACE_STATIONS_ENABLED = RAILWAY_SURFACE_STATIONS_ENABLED;
        p.RAILWAYS_CAN_END = RAILWAYS_CAN_END;
        p.RAILWAY_DUNGEON_CHANCE = RAILWAY_DUNGEON_CHANCE;
        p.RUIN_CHANCE = RUIN_CHANCE;
        p.RUIN_MINLEVEL_PERCENT = RUIN_MINLEVEL_PERCENT;
        p.RUIN_MAXLEVEL_PERCENT = RUIN_MAXLEVEL_PERCENT;
        p.RUBBLELAYER = RUBBLELAYER;
        p.RUBBLE_DIRT_SCALE = RUBBLE_DIRT_SCALE;
        p.RUBBLE_LEAVE_SCALE = RUBBLE_LEAVE_SCALE;
        p.EXPLOSION_CHANCE = EXPLOSION_CHANCE;
        p.EXPLOSION_MINRADIUS = EXPLOSION_MINRADIUS;
        p.EXPLOSION_MAXRADIUS = EXPLOSION_MAXRADIUS;
        p.EXPLOSION_MINHEIGHT = EXPLOSION_MINHEIGHT;
        p.EXPLOSION_MAXHEIGHT = EXPLOSION_MAXHEIGHT;
        p.MINI_EXPLOSION_CHANCE = MINI_EXPLOSION_CHANCE;
        p.MINI_EXPLOSION_MINRADIUS = MINI_EXPLOSION_MINRADIUS;
        p.MINI_EXPLOSION_MAXRADIUS = MINI_EXPLOSION_MAXRADIUS;
        p.MINI_EXPLOSION_MINHEIGHT = MINI_EXPLOSION_MINHEIGHT;
        p.MINI_EXPLOSION_MAXHEIGHT = MINI_EXPLOSION_MAXHEIGHT;
        p.EXPLOSIONS_IN_CITIES_ONLY = EXPLOSIONS_IN_CITIES_ONLY;
        p.DEBRIS_TO_NEARBYCHUNK_FACTOR = DEBRIS_TO_NEARBYCHUNK_FACTOR;
        p.CHANCE_OF_RANDOM_LEAFBLOCKS = CHANCE_OF_RANDOM_LEAFBLOCKS;
        p.THICKNESS_OF_RANDOM_LEAFBLOCKS = THICKNESS_OF_RANDOM_LEAFBLOCKS;
        p.AVOID_FOLIAGE = AVOID_FOLIAGE;
        p.LIGHTING_DENSITY = LIGHTING_DENSITY;
        p.LOOT_DENSITY = LOOT_DENSITY;
        p.SPAWN_BIOME = SPAWN_BIOME;
        p.SPAWN_CITY = SPAWN_CITY;
        p.SPAWN_NOT_IN_BUILDING = SPAWN_NOT_IN_BUILDING;
        p.FORCE_SPAWN_IN_BUILDING = FORCE_SPAWN_IN_BUILDING;
        p.FORCE_SPAWN_BUILDINGS = List.copyOf(FORCE_SPAWN_BUILDINGS);
        p.FORCE_SPAWN_PARTS = List.copyOf(FORCE_SPAWN_PARTS);
        p.SPAWN_CHECK_RADIUS = SPAWN_CHECK_RADIUS;
        p.SPAWN_RADIUS_INCREASE = SPAWN_RADIUS_INCREASE;
        p.SPAWN_CHECK_ATTEMPTS = SPAWN_CHECK_ATTEMPTS;
        p.HORIZON = HORIZON;
        p.FOG_RED = FOG_RED;
        p.FOG_GREEN = FOG_GREEN;
        p.FOG_BLUE = FOG_BLUE;
        p.FOG_DENSITY = FOG_DENSITY;
        p.EDITMODE = EDITMODE;
        p.GENERATE_NETHER = GENERATE_NETHER;
        return p;
    }

    /**
     * Encodes this preset as a {@link PresetRE} with every field present (fully-populated
     * sections). Used for round-trip tests, saved-data overrides, and the export command.
     */
    public PresetRE toRE() {
        TerrainSettings terrain =
                new TerrainSettings(
                Optional.of(LANDSCAPE_TYPE),
                Optional.of(GROUNDLEVEL),
                Optional.of(SEALEVEL),
                Optional.of(LIQUID_BLOCK),
                Optional.of(BASE_BLOCK),
                Optional.of(BEDROCK_LAYER),
                Optional.of(TERRAIN_FIX_LOWER_MIN_OFFSET),
                Optional.of(TERRAIN_FIX_LOWER_MAX_OFFSET),
                Optional.of(TERRAIN_FIX_UPPER_MIN_OFFSET),
                Optional.of(TERRAIN_FIX_UPPER_MAX_OFFSET),
                Optional.of(OCEAN_CORRECTION_BORDER),
                Optional.of(AVOID_WATER),
                Optional.of(USE_AVG_HEIGHTMAP));
        CitySettings cities =
                new CitySettings(
                Optional.of(CITY_CHANCE),
                Optional.of(CITY_MINRADIUS),
                Optional.of(CITY_MAXRADIUS),
                Optional.of(CITY_PERLIN_SCALE),
                Optional.of(CITY_PERLIN_OFFSET),
                Optional.of(CITY_PERLIN_INNERSCALE),
                Optional.of(CITY_THRESHOLD),
                Optional.of(CITY_SPAWN_DISTANCE1),
                Optional.of(CITY_SPAWN_DISTANCE2),
                Optional.of(CITY_SPAWN_MULTIPLIER1),
                Optional.of(CITY_SPAWN_MULTIPLIER2),
                Optional.of(CITY_STYLE_THRESHOLD),
                Optional.of(CITY_STYLE_ALTERNATIVE),
                Optional.of(CITY_AVOID_VOID),
                Optional.of(CITY_LEVEL0_HEIGHT),
                Optional.of(CITY_LEVEL1_HEIGHT),
                Optional.of(CITY_LEVEL2_HEIGHT),
                Optional.of(CITY_LEVEL3_HEIGHT),
                Optional.of(CITY_LEVEL4_HEIGHT),
                Optional.of(CITY_LEVEL5_HEIGHT),
                Optional.of(CITY_LEVEL6_HEIGHT),
                Optional.of(CITY_LEVEL7_HEIGHT),
                Optional.of(CITY_MINHEIGHT),
                Optional.of(CITY_MAXHEIGHT),
                Optional.of(SCATTERED_CHANCE_MULTIPLIER));
        BuildingSettings buildings =
                new BuildingSettings(
                Optional.of(BUILDING_CHANCE),
                Optional.of(BUILDING_MINFLOORS),
                Optional.of(BUILDING_MAXFLOORS),
                Optional.of(BUILDING_MINFLOORS_CHANCE),
                Optional.of(BUILDING_MAXFLOORS_CHANCE),
                Optional.of(BUILDING_MINCELLARS),
                Optional.of(BUILDING_MAXCELLARS),
                Optional.of(BUILDING_DOORWAYCHANCE),
                Optional.of(BUILDING_FRONTCHANCE),
                Optional.of(MULTI_USE_CORNER),
                Optional.of(MULTI_BUILDING_STREET_CONFLICT),
                Optional.of(GENERATE_SPAWNERS));
        RoadSettings roads =
                new RoadSettings(
                Optional.of(PRIMARY_ROAD_SPACING_X),
                Optional.of(PRIMARY_ROAD_SPACING_Z),
                Optional.of(PRIMARY_ROAD_OPTIONAL_CHANCE),
                Optional.of(PRIMARY_ROAD_FORCE_EVERY),
                Optional.of(SECONDARY_ROAD_MIN_COUNT_X),
                Optional.of(SECONDARY_ROAD_MAX_COUNT_X),
                Optional.of(SECONDARY_ROAD_MIN_COUNT_Z),
                Optional.of(SECONDARY_ROAD_MAX_COUNT_Z),
                Optional.of(MINIMUM_ROAD_SEPARATION),
                Optional.of(MINIMUM_ROAD_EDGE_DISTANCE),
                Optional.of(TERTIARY_ROAD_CHANCE),
                Optional.of(TERTIARY_ROAD_MIN_LENGTH),
                Optional.of(TERTIARY_ROAD_MAX_LENGTH),
                Optional.of(PLANNED_PRIMARY_BRIDGE_CHANCE),
                Optional.of(PLANNED_PRIMARY_BRIDGE_MAX_LENGTH),
                Optional.of(OPEN_LOT_PARK_CHANCE),
                Optional.of(PARK_ELEVATION),
                Optional.of(PARK_BORDER),
                Optional.of(PARK_STREET_THRESHOLD),
                Optional.of(FOUNTAIN_CHANCE),
                Optional.of(CORRIDOR_CHANCE),
                Optional.of(BRIDGE_CHANCE),
                Optional.of(BRIDGE_SUPPORTS));
        HighwaySettings highways =
                new HighwaySettings(
                Optional.of(HIGHWAY_REQUIRES_TWO_CITIES),
                Optional.of(HIGHWAY_LEVEL_FROM_CITIES_MODE),
                Optional.of(HIGHWAY_DISTANCE_MASK),
                Optional.of(HIGHWAY_MAINPERLIN_SCALE),
                Optional.of(HIGHWAY_SECONDARYPERLIN_SCALE),
                Optional.of(HIGHWAY_PERLIN_FACTOR),
                Optional.of(HIGHWAY_SUPPORTS));
        RailwaySettings railways =
                new RailwaySettings(
                Optional.of(RAILWAYS_ENABLED),
                Optional.of(RAILWAY_STATIONS_ENABLED),
                Optional.of(RAILWAY_SURFACE_STATIONS_ENABLED),
                Optional.of(RAILWAYS_CAN_END),
                Optional.of(RAILWAY_DUNGEON_CHANCE));
        DestructionSettings destruction =
                new DestructionSettings(
                Optional.of(RUIN_CHANCE),
                Optional.of(RUIN_MINLEVEL_PERCENT),
                Optional.of(RUIN_MAXLEVEL_PERCENT),
                Optional.of(RUBBLELAYER),
                Optional.of(RUBBLE_DIRT_SCALE),
                Optional.of(RUBBLE_LEAVE_SCALE),
                Optional.of(EXPLOSION_CHANCE),
                Optional.of(EXPLOSION_MINRADIUS),
                Optional.of(EXPLOSION_MAXRADIUS),
                Optional.of(EXPLOSION_MINHEIGHT),
                Optional.of(EXPLOSION_MAXHEIGHT),
                Optional.of(MINI_EXPLOSION_CHANCE),
                Optional.of(MINI_EXPLOSION_MINRADIUS),
                Optional.of(MINI_EXPLOSION_MAXRADIUS),
                Optional.of(MINI_EXPLOSION_MINHEIGHT),
                Optional.of(MINI_EXPLOSION_MAXHEIGHT),
                Optional.of(EXPLOSIONS_IN_CITIES_ONLY),
                Optional.of(DEBRIS_TO_NEARBYCHUNK_FACTOR));
        DecorationSettings decoration =
                new DecorationSettings(
                Optional.of(CHANCE_OF_RANDOM_LEAFBLOCKS),
                Optional.of(THICKNESS_OF_RANDOM_LEAFBLOCKS),
                Optional.of(AVOID_FOLIAGE),
                Optional.of(LIGHTING_DENSITY),
                Optional.of(LOOT_DENSITY));
        SpawnSettings spawn =
                new SpawnSettings(
                Optional.of(SPAWN_BIOME),
                Optional.of(SPAWN_CITY),
                Optional.of(SPAWN_NOT_IN_BUILDING),
                Optional.of(FORCE_SPAWN_IN_BUILDING),
                Optional.of(FORCE_SPAWN_BUILDINGS),
                Optional.of(FORCE_SPAWN_PARTS),
                Optional.of(SPAWN_CHECK_RADIUS),
                Optional.of(SPAWN_RADIUS_INCREASE),
                Optional.of(SPAWN_CHECK_ATTEMPTS));
        AtmosphereSettings atmosphere =
                new AtmosphereSettings(
                Optional.of(HORIZON),
                Optional.of(FOG_RED),
                Optional.of(FOG_GREEN),
                Optional.of(FOG_BLUE),
                Optional.of(FOG_DENSITY));
        MiscSettings misc =
                new MiscSettings(
                Optional.of(EDITMODE),
                Optional.of(GENERATE_NETHER));

        return new PresetRE(
                Optional.empty(),
                Optional.of(name),
                Optional.of(description),
                Optional.of(extraDescription),
                Optional.of(warning),
                Optional.of(iconFile),
                Optional.of(terrain),
                Optional.of(cities),
                Optional.of(buildings),
                Optional.of(roads),
                Optional.of(highways),
                Optional.of(railways),
                Optional.of(destruction),
                Optional.of(decoration),
                Optional.of(spawn),
                Optional.of(atmosphere),
                Optional.of(misc));
    }
}

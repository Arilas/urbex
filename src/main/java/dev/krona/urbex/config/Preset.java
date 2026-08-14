package dev.krona.urbex.config;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.setup.ModSetup;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
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
 * A fully-resolved preset: the runtime settings worldgen generates from, settled from a
 * {@link PresetDraft} once its {@code extends} chain and any customization patch have been applied
 * (see {@code Presets.resolve}).
 *
 * <p><strong>Immutable.</strong> Every value is {@code final} and there is no setter, so nothing
 * generation is handed can be written to. That used to be a convention: 119 public mutable fields,
 * a {@code copy()} the editor called because it needed a scratch copy, and a comment asking everyone
 * else not to. What replaced the convention is the type - editing happens on a
 * {@link PresetDraft}, and the two are not interchangeable (issue #10). It is the same move issue
 * #126 made for chunk plans, where an audit that searched for assignments still missed a field being
 * decremented on every call.</p>
 *
 * <p>Field names match the runtime-generated profile format this class replaced (same names, same
 * types), and each is read through the accessor named for the JSON key its codec reads it from.
 * Exceptions: there is no {@code worldStyle} field (world style selection is a separate first-class
 * value), no {@code isPublic}, and no config-file binding of any kind. {@link #useAvgHeightmap()}
 * defaults to {@code true} here, unlike the old default of {@code false}.</p>
 */
public class Preset {

    private final Identifier id;

    private final String name;
    private final String description;
    private final String extraDescription;
    private final String warning;
    private final String iconFile;
    /*
     * The three memoized derivations, and the only fields here that are not final. They are not
     * state: each is a pure function of a final field above (the icon file name, the liquid and base
     * block ids), computed on first ask because resolving a block needs a registry this class is not
     * handed. Nothing can observe a difference between one that has been computed and one that has
     * not, which is what makes writing them compatible with the rest being immutable (issue #10).
     */
    private Identifier icon;
    private BlockState liquidBlock;
    private BlockState baseBlock;

    private final LandscapeType LANDSCAPE_TYPE;
    private final int GROUNDLEVEL;
    private final int SEALEVEL;
    private final String LIQUID_BLOCK;
    private final String BASE_BLOCK;
    private final int BEDROCK_LAYER;
    private final int TERRAIN_FIX_LOWER_MIN_OFFSET;
    private final int TERRAIN_FIX_LOWER_MAX_OFFSET;
    private final int TERRAIN_FIX_UPPER_MIN_OFFSET;
    private final int TERRAIN_FIX_UPPER_MAX_OFFSET;
    private final int OCEAN_CORRECTION_BORDER;
    private final boolean AVOID_WATER;
    private final boolean USE_AVG_HEIGHTMAP;
    private final double CITY_CHANCE;
    private final int CITY_MINRADIUS;
    private final int CITY_MAXRADIUS;
    private final double CITY_PERLIN_SCALE;
    private final double CITY_PERLIN_OFFSET;
    private final double CITY_PERLIN_INNERSCALE;
    private final float CITY_THRESHOLD;
    private final int CITY_SPAWN_DISTANCE1;
    private final int CITY_SPAWN_DISTANCE2;
    private final double CITY_SPAWN_MULTIPLIER1;
    private final double CITY_SPAWN_MULTIPLIER2;
    private final boolean CITY_AVOID_VOID;
    private final int CITY_LEVEL0_HEIGHT;
    private final int CITY_LEVEL1_HEIGHT;
    private final int CITY_LEVEL2_HEIGHT;
    private final int CITY_LEVEL3_HEIGHT;
    private final int CITY_LEVEL4_HEIGHT;
    private final int CITY_LEVEL5_HEIGHT;
    private final int CITY_LEVEL6_HEIGHT;
    private final int CITY_LEVEL7_HEIGHT;
    private final int CITY_MINHEIGHT;
    private final int CITY_MAXHEIGHT;
    private final float SCATTERED_CHANCE_MULTIPLIER;
    private final float BUILDING_CHANCE;
    private final int BUILDING_MINFLOORS;
    private final int BUILDING_MAXFLOORS;
    private final int BUILDING_MINFLOORS_CHANCE;
    private final int BUILDING_MAXFLOORS_CHANCE;
    private final int BUILDING_MINCELLARS;
    private final int BUILDING_MAXCELLARS;
    private final float BUILDING_DOORWAYCHANCE;
    private final float BUILDING_FRONTCHANCE;
    private final boolean MULTI_USE_CORNER;
    private final MultiBuildingStreetConflict MULTI_BUILDING_STREET_CONFLICT;
    private final boolean GENERATE_SPAWNERS;
    private final int PRIMARY_ROAD_SPACING_X;
    private final int PRIMARY_ROAD_SPACING_Z;
    private final float PRIMARY_ROAD_OPTIONAL_CHANCE;
    private final int PRIMARY_ROAD_FORCE_EVERY;
    private final int SECONDARY_ROAD_MIN_COUNT_X;
    private final int SECONDARY_ROAD_MAX_COUNT_X;
    private final int SECONDARY_ROAD_MIN_COUNT_Z;
    private final int SECONDARY_ROAD_MAX_COUNT_Z;
    private final int MINIMUM_ROAD_SEPARATION;
    private final int MINIMUM_ROAD_EDGE_DISTANCE;
    private final float TERTIARY_ROAD_CHANCE;
    private final int TERTIARY_ROAD_MIN_LENGTH;
    private final int TERTIARY_ROAD_MAX_LENGTH;
    private final float PLANNED_PRIMARY_BRIDGE_CHANCE;
    private final int PLANNED_PRIMARY_BRIDGE_MAX_LENGTH;
    private final float OPEN_LOT_PARK_CHANCE;
    private final boolean PARK_ELEVATION;
    private final boolean PARK_BORDER;
    private final int PARK_STREET_THRESHOLD;
    private final float FOUNTAIN_CHANCE;
    private final float CORRIDOR_CHANCE;
    private final float BRIDGE_CHANCE;
    private final boolean BRIDGE_SUPPORTS;
    private final boolean HIGHWAY_REQUIRES_TWO_CITIES;
    private final int HIGHWAY_LEVEL_FROM_CITIES_MODE;
    private final int HIGHWAY_DISTANCE_MASK;
    private final float HIGHWAY_MAINPERLIN_SCALE;
    private final float HIGHWAY_SECONDARYPERLIN_SCALE;
    private final float HIGHWAY_PERLIN_FACTOR;
    private final boolean HIGHWAY_SUPPORTS;
    private final boolean RAILWAYS_ENABLED;
    private final boolean RAILWAY_STATIONS_ENABLED;
    private final boolean RAILWAY_SURFACE_STATIONS_ENABLED;
    private final boolean RAILWAYS_CAN_END;
    private final float RAILWAY_DUNGEON_CHANCE;
    private final float RUIN_CHANCE;
    private final float RUIN_MINLEVEL_PERCENT;
    private final float RUIN_MAXLEVEL_PERCENT;
    private final boolean RUBBLELAYER;
    private final float RUBBLE_DIRT_SCALE;
    private final float RUBBLE_LEAVE_SCALE;
    private final float EXPLOSION_CHANCE;
    private final int EXPLOSION_MINRADIUS;
    private final int EXPLOSION_MAXRADIUS;
    private final int EXPLOSION_MINHEIGHT;
    private final int EXPLOSION_MAXHEIGHT;
    private final float MINI_EXPLOSION_CHANCE;
    private final int MINI_EXPLOSION_MINRADIUS;
    private final int MINI_EXPLOSION_MAXRADIUS;
    private final int MINI_EXPLOSION_MINHEIGHT;
    private final int MINI_EXPLOSION_MAXHEIGHT;
    private final boolean EXPLOSIONS_IN_CITIES_ONLY;
    private final int DEBRIS_TO_NEARBYCHUNK_FACTOR;
    private final float CHANCE_OF_RANDOM_LEAFBLOCKS;
    private final int THICKNESS_OF_RANDOM_LEAFBLOCKS;
    private final boolean AVOID_FOLIAGE;
    private final float LIGHTING_DENSITY;
    private final float LOOT_DENSITY;
    private final String SPAWN_BIOME;
    private final String SPAWN_CITY;
    private final boolean SPAWN_NOT_IN_BUILDING;
    private final boolean FORCE_SPAWN_IN_BUILDING;
    private final List<String> FORCE_SPAWN_BUILDINGS;
    private final List<String> FORCE_SPAWN_PARTS;
    private final int SPAWN_CHECK_RADIUS;
    private final int SPAWN_RADIUS_INCREASE;
    private final int SPAWN_CHECK_ATTEMPTS;
    private final boolean EDITMODE;
    private final boolean GENERATE_NETHER;

    /** The code defaults, unmodified. */
    public Preset(Identifier id) {
        this(new PresetDraft(id));
    }

    /** Settles {@code draft}. Every value is copied; nothing is shared with it afterwards. */
    public Preset(PresetDraft draft) {
        this.id = draft.getId();
        this.name = draft.getName();
        this.description = draft.getDescription();
        this.extraDescription = draft.getExtraDescription();
        this.warning = draft.getWarning();
        this.iconFile = draft.getIconFile();
        this.LANDSCAPE_TYPE = draft.LANDSCAPE_TYPE;
        this.GROUNDLEVEL = draft.GROUNDLEVEL;
        this.SEALEVEL = draft.SEALEVEL;
        this.LIQUID_BLOCK = draft.LIQUID_BLOCK;
        this.BASE_BLOCK = draft.BASE_BLOCK;
        this.BEDROCK_LAYER = draft.BEDROCK_LAYER;
        this.TERRAIN_FIX_LOWER_MIN_OFFSET = draft.TERRAIN_FIX_LOWER_MIN_OFFSET;
        this.TERRAIN_FIX_LOWER_MAX_OFFSET = draft.TERRAIN_FIX_LOWER_MAX_OFFSET;
        this.TERRAIN_FIX_UPPER_MIN_OFFSET = draft.TERRAIN_FIX_UPPER_MIN_OFFSET;
        this.TERRAIN_FIX_UPPER_MAX_OFFSET = draft.TERRAIN_FIX_UPPER_MAX_OFFSET;
        this.OCEAN_CORRECTION_BORDER = draft.OCEAN_CORRECTION_BORDER;
        this.AVOID_WATER = draft.AVOID_WATER;
        this.USE_AVG_HEIGHTMAP = draft.USE_AVG_HEIGHTMAP;
        this.CITY_CHANCE = draft.CITY_CHANCE;
        this.CITY_MINRADIUS = draft.CITY_MINRADIUS;
        this.CITY_MAXRADIUS = draft.CITY_MAXRADIUS;
        this.CITY_PERLIN_SCALE = draft.CITY_PERLIN_SCALE;
        this.CITY_PERLIN_OFFSET = draft.CITY_PERLIN_OFFSET;
        this.CITY_PERLIN_INNERSCALE = draft.CITY_PERLIN_INNERSCALE;
        this.CITY_THRESHOLD = draft.CITY_THRESHOLD;
        this.CITY_SPAWN_DISTANCE1 = draft.CITY_SPAWN_DISTANCE1;
        this.CITY_SPAWN_DISTANCE2 = draft.CITY_SPAWN_DISTANCE2;
        this.CITY_SPAWN_MULTIPLIER1 = draft.CITY_SPAWN_MULTIPLIER1;
        this.CITY_SPAWN_MULTIPLIER2 = draft.CITY_SPAWN_MULTIPLIER2;
        this.CITY_AVOID_VOID = draft.CITY_AVOID_VOID;
        this.CITY_LEVEL0_HEIGHT = draft.CITY_LEVEL0_HEIGHT;
        this.CITY_LEVEL1_HEIGHT = draft.CITY_LEVEL1_HEIGHT;
        this.CITY_LEVEL2_HEIGHT = draft.CITY_LEVEL2_HEIGHT;
        this.CITY_LEVEL3_HEIGHT = draft.CITY_LEVEL3_HEIGHT;
        this.CITY_LEVEL4_HEIGHT = draft.CITY_LEVEL4_HEIGHT;
        this.CITY_LEVEL5_HEIGHT = draft.CITY_LEVEL5_HEIGHT;
        this.CITY_LEVEL6_HEIGHT = draft.CITY_LEVEL6_HEIGHT;
        this.CITY_LEVEL7_HEIGHT = draft.CITY_LEVEL7_HEIGHT;
        this.CITY_MINHEIGHT = draft.CITY_MINHEIGHT;
        this.CITY_MAXHEIGHT = draft.CITY_MAXHEIGHT;
        this.SCATTERED_CHANCE_MULTIPLIER = draft.SCATTERED_CHANCE_MULTIPLIER;
        this.BUILDING_CHANCE = draft.BUILDING_CHANCE;
        this.BUILDING_MINFLOORS = draft.BUILDING_MINFLOORS;
        this.BUILDING_MAXFLOORS = draft.BUILDING_MAXFLOORS;
        this.BUILDING_MINFLOORS_CHANCE = draft.BUILDING_MINFLOORS_CHANCE;
        this.BUILDING_MAXFLOORS_CHANCE = draft.BUILDING_MAXFLOORS_CHANCE;
        this.BUILDING_MINCELLARS = draft.BUILDING_MINCELLARS;
        this.BUILDING_MAXCELLARS = draft.BUILDING_MAXCELLARS;
        this.BUILDING_DOORWAYCHANCE = draft.BUILDING_DOORWAYCHANCE;
        this.BUILDING_FRONTCHANCE = draft.BUILDING_FRONTCHANCE;
        this.MULTI_USE_CORNER = draft.MULTI_USE_CORNER;
        this.MULTI_BUILDING_STREET_CONFLICT = draft.MULTI_BUILDING_STREET_CONFLICT;
        this.GENERATE_SPAWNERS = draft.GENERATE_SPAWNERS;
        this.PRIMARY_ROAD_SPACING_X = draft.PRIMARY_ROAD_SPACING_X;
        this.PRIMARY_ROAD_SPACING_Z = draft.PRIMARY_ROAD_SPACING_Z;
        this.PRIMARY_ROAD_OPTIONAL_CHANCE = draft.PRIMARY_ROAD_OPTIONAL_CHANCE;
        this.PRIMARY_ROAD_FORCE_EVERY = draft.PRIMARY_ROAD_FORCE_EVERY;
        this.SECONDARY_ROAD_MIN_COUNT_X = draft.SECONDARY_ROAD_MIN_COUNT_X;
        this.SECONDARY_ROAD_MAX_COUNT_X = draft.SECONDARY_ROAD_MAX_COUNT_X;
        this.SECONDARY_ROAD_MIN_COUNT_Z = draft.SECONDARY_ROAD_MIN_COUNT_Z;
        this.SECONDARY_ROAD_MAX_COUNT_Z = draft.SECONDARY_ROAD_MAX_COUNT_Z;
        this.MINIMUM_ROAD_SEPARATION = draft.MINIMUM_ROAD_SEPARATION;
        this.MINIMUM_ROAD_EDGE_DISTANCE = draft.MINIMUM_ROAD_EDGE_DISTANCE;
        this.TERTIARY_ROAD_CHANCE = draft.TERTIARY_ROAD_CHANCE;
        this.TERTIARY_ROAD_MIN_LENGTH = draft.TERTIARY_ROAD_MIN_LENGTH;
        this.TERTIARY_ROAD_MAX_LENGTH = draft.TERTIARY_ROAD_MAX_LENGTH;
        this.PLANNED_PRIMARY_BRIDGE_CHANCE = draft.PLANNED_PRIMARY_BRIDGE_CHANCE;
        this.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH = draft.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH;
        this.OPEN_LOT_PARK_CHANCE = draft.OPEN_LOT_PARK_CHANCE;
        this.PARK_ELEVATION = draft.PARK_ELEVATION;
        this.PARK_BORDER = draft.PARK_BORDER;
        this.PARK_STREET_THRESHOLD = draft.PARK_STREET_THRESHOLD;
        this.FOUNTAIN_CHANCE = draft.FOUNTAIN_CHANCE;
        this.CORRIDOR_CHANCE = draft.CORRIDOR_CHANCE;
        this.BRIDGE_CHANCE = draft.BRIDGE_CHANCE;
        this.BRIDGE_SUPPORTS = draft.BRIDGE_SUPPORTS;
        this.HIGHWAY_REQUIRES_TWO_CITIES = draft.HIGHWAY_REQUIRES_TWO_CITIES;
        this.HIGHWAY_LEVEL_FROM_CITIES_MODE = draft.HIGHWAY_LEVEL_FROM_CITIES_MODE;
        this.HIGHWAY_DISTANCE_MASK = draft.HIGHWAY_DISTANCE_MASK;
        this.HIGHWAY_MAINPERLIN_SCALE = draft.HIGHWAY_MAINPERLIN_SCALE;
        this.HIGHWAY_SECONDARYPERLIN_SCALE = draft.HIGHWAY_SECONDARYPERLIN_SCALE;
        this.HIGHWAY_PERLIN_FACTOR = draft.HIGHWAY_PERLIN_FACTOR;
        this.HIGHWAY_SUPPORTS = draft.HIGHWAY_SUPPORTS;
        this.RAILWAYS_ENABLED = draft.RAILWAYS_ENABLED;
        this.RAILWAY_STATIONS_ENABLED = draft.RAILWAY_STATIONS_ENABLED;
        this.RAILWAY_SURFACE_STATIONS_ENABLED = draft.RAILWAY_SURFACE_STATIONS_ENABLED;
        this.RAILWAYS_CAN_END = draft.RAILWAYS_CAN_END;
        this.RAILWAY_DUNGEON_CHANCE = draft.RAILWAY_DUNGEON_CHANCE;
        this.RUIN_CHANCE = draft.RUIN_CHANCE;
        this.RUIN_MINLEVEL_PERCENT = draft.RUIN_MINLEVEL_PERCENT;
        this.RUIN_MAXLEVEL_PERCENT = draft.RUIN_MAXLEVEL_PERCENT;
        this.RUBBLELAYER = draft.RUBBLELAYER;
        this.RUBBLE_DIRT_SCALE = draft.RUBBLE_DIRT_SCALE;
        this.RUBBLE_LEAVE_SCALE = draft.RUBBLE_LEAVE_SCALE;
        this.EXPLOSION_CHANCE = draft.EXPLOSION_CHANCE;
        this.EXPLOSION_MINRADIUS = draft.EXPLOSION_MINRADIUS;
        this.EXPLOSION_MAXRADIUS = draft.EXPLOSION_MAXRADIUS;
        this.EXPLOSION_MINHEIGHT = draft.EXPLOSION_MINHEIGHT;
        this.EXPLOSION_MAXHEIGHT = draft.EXPLOSION_MAXHEIGHT;
        this.MINI_EXPLOSION_CHANCE = draft.MINI_EXPLOSION_CHANCE;
        this.MINI_EXPLOSION_MINRADIUS = draft.MINI_EXPLOSION_MINRADIUS;
        this.MINI_EXPLOSION_MAXRADIUS = draft.MINI_EXPLOSION_MAXRADIUS;
        this.MINI_EXPLOSION_MINHEIGHT = draft.MINI_EXPLOSION_MINHEIGHT;
        this.MINI_EXPLOSION_MAXHEIGHT = draft.MINI_EXPLOSION_MAXHEIGHT;
        this.EXPLOSIONS_IN_CITIES_ONLY = draft.EXPLOSIONS_IN_CITIES_ONLY;
        this.DEBRIS_TO_NEARBYCHUNK_FACTOR = draft.DEBRIS_TO_NEARBYCHUNK_FACTOR;
        this.CHANCE_OF_RANDOM_LEAFBLOCKS = draft.CHANCE_OF_RANDOM_LEAFBLOCKS;
        this.THICKNESS_OF_RANDOM_LEAFBLOCKS = draft.THICKNESS_OF_RANDOM_LEAFBLOCKS;
        this.AVOID_FOLIAGE = draft.AVOID_FOLIAGE;
        this.LIGHTING_DENSITY = draft.LIGHTING_DENSITY;
        this.LOOT_DENSITY = draft.LOOT_DENSITY;
        this.SPAWN_BIOME = draft.SPAWN_BIOME;
        this.SPAWN_CITY = draft.SPAWN_CITY;
        this.SPAWN_NOT_IN_BUILDING = draft.SPAWN_NOT_IN_BUILDING;
        this.FORCE_SPAWN_IN_BUILDING = draft.FORCE_SPAWN_IN_BUILDING;
        this.FORCE_SPAWN_BUILDINGS = draft.FORCE_SPAWN_BUILDINGS;
        this.FORCE_SPAWN_PARTS = draft.FORCE_SPAWN_PARTS;
        this.SPAWN_CHECK_RADIUS = draft.SPAWN_CHECK_RADIUS;
        this.SPAWN_RADIUS_INCREASE = draft.SPAWN_RADIUS_INCREASE;
        this.SPAWN_CHECK_ATTEMPTS = draft.SPAWN_CHECK_ATTEMPTS;
        this.EDITMODE = draft.EDITMODE;
        this.GENERATE_NETHER = draft.GENERATE_NETHER;
    }

    /**
     * A draft of these values, for an editor to change.
     * <p>
     * What {@code copy()} was, with the difference that the result is a different type: a copy of a
     * preset used to be a preset, so nothing distinguished "the resolved value worldgen reads" from
     * "the scratch copy the customization screen is editing" (issue #10).
     * <p>
     * The resolved liquid/base {@link BlockState} cache deliberately does not carry over. Those are
     * memoized from the {@code LIQUID_BLOCK}/{@code BASE_BLOCK} strings, which a draft can change,
     * and a copied cache would keep answering with the pre-edit block.
     */
    public PresetDraft toDraft() {
        PresetDraft draft = new PresetDraft(id);
        draft.setName(name);
        draft.setDescription(description);
        draft.setExtraDescription(extraDescription);
        draft.setWarning(warning);
        draft.setIconFile(iconFile);
        draft.LANDSCAPE_TYPE = LANDSCAPE_TYPE;
        draft.GROUNDLEVEL = GROUNDLEVEL;
        draft.SEALEVEL = SEALEVEL;
        draft.LIQUID_BLOCK = LIQUID_BLOCK;
        draft.BASE_BLOCK = BASE_BLOCK;
        draft.BEDROCK_LAYER = BEDROCK_LAYER;
        draft.TERRAIN_FIX_LOWER_MIN_OFFSET = TERRAIN_FIX_LOWER_MIN_OFFSET;
        draft.TERRAIN_FIX_LOWER_MAX_OFFSET = TERRAIN_FIX_LOWER_MAX_OFFSET;
        draft.TERRAIN_FIX_UPPER_MIN_OFFSET = TERRAIN_FIX_UPPER_MIN_OFFSET;
        draft.TERRAIN_FIX_UPPER_MAX_OFFSET = TERRAIN_FIX_UPPER_MAX_OFFSET;
        draft.OCEAN_CORRECTION_BORDER = OCEAN_CORRECTION_BORDER;
        draft.AVOID_WATER = AVOID_WATER;
        draft.USE_AVG_HEIGHTMAP = USE_AVG_HEIGHTMAP;
        draft.CITY_CHANCE = CITY_CHANCE;
        draft.CITY_MINRADIUS = CITY_MINRADIUS;
        draft.CITY_MAXRADIUS = CITY_MAXRADIUS;
        draft.CITY_PERLIN_SCALE = CITY_PERLIN_SCALE;
        draft.CITY_PERLIN_OFFSET = CITY_PERLIN_OFFSET;
        draft.CITY_PERLIN_INNERSCALE = CITY_PERLIN_INNERSCALE;
        draft.CITY_THRESHOLD = CITY_THRESHOLD;
        draft.CITY_SPAWN_DISTANCE1 = CITY_SPAWN_DISTANCE1;
        draft.CITY_SPAWN_DISTANCE2 = CITY_SPAWN_DISTANCE2;
        draft.CITY_SPAWN_MULTIPLIER1 = CITY_SPAWN_MULTIPLIER1;
        draft.CITY_SPAWN_MULTIPLIER2 = CITY_SPAWN_MULTIPLIER2;
        draft.CITY_AVOID_VOID = CITY_AVOID_VOID;
        draft.CITY_LEVEL0_HEIGHT = CITY_LEVEL0_HEIGHT;
        draft.CITY_LEVEL1_HEIGHT = CITY_LEVEL1_HEIGHT;
        draft.CITY_LEVEL2_HEIGHT = CITY_LEVEL2_HEIGHT;
        draft.CITY_LEVEL3_HEIGHT = CITY_LEVEL3_HEIGHT;
        draft.CITY_LEVEL4_HEIGHT = CITY_LEVEL4_HEIGHT;
        draft.CITY_LEVEL5_HEIGHT = CITY_LEVEL5_HEIGHT;
        draft.CITY_LEVEL6_HEIGHT = CITY_LEVEL6_HEIGHT;
        draft.CITY_LEVEL7_HEIGHT = CITY_LEVEL7_HEIGHT;
        draft.CITY_MINHEIGHT = CITY_MINHEIGHT;
        draft.CITY_MAXHEIGHT = CITY_MAXHEIGHT;
        draft.SCATTERED_CHANCE_MULTIPLIER = SCATTERED_CHANCE_MULTIPLIER;
        draft.BUILDING_CHANCE = BUILDING_CHANCE;
        draft.BUILDING_MINFLOORS = BUILDING_MINFLOORS;
        draft.BUILDING_MAXFLOORS = BUILDING_MAXFLOORS;
        draft.BUILDING_MINFLOORS_CHANCE = BUILDING_MINFLOORS_CHANCE;
        draft.BUILDING_MAXFLOORS_CHANCE = BUILDING_MAXFLOORS_CHANCE;
        draft.BUILDING_MINCELLARS = BUILDING_MINCELLARS;
        draft.BUILDING_MAXCELLARS = BUILDING_MAXCELLARS;
        draft.BUILDING_DOORWAYCHANCE = BUILDING_DOORWAYCHANCE;
        draft.BUILDING_FRONTCHANCE = BUILDING_FRONTCHANCE;
        draft.MULTI_USE_CORNER = MULTI_USE_CORNER;
        draft.MULTI_BUILDING_STREET_CONFLICT = MULTI_BUILDING_STREET_CONFLICT;
        draft.GENERATE_SPAWNERS = GENERATE_SPAWNERS;
        draft.PRIMARY_ROAD_SPACING_X = PRIMARY_ROAD_SPACING_X;
        draft.PRIMARY_ROAD_SPACING_Z = PRIMARY_ROAD_SPACING_Z;
        draft.PRIMARY_ROAD_OPTIONAL_CHANCE = PRIMARY_ROAD_OPTIONAL_CHANCE;
        draft.PRIMARY_ROAD_FORCE_EVERY = PRIMARY_ROAD_FORCE_EVERY;
        draft.SECONDARY_ROAD_MIN_COUNT_X = SECONDARY_ROAD_MIN_COUNT_X;
        draft.SECONDARY_ROAD_MAX_COUNT_X = SECONDARY_ROAD_MAX_COUNT_X;
        draft.SECONDARY_ROAD_MIN_COUNT_Z = SECONDARY_ROAD_MIN_COUNT_Z;
        draft.SECONDARY_ROAD_MAX_COUNT_Z = SECONDARY_ROAD_MAX_COUNT_Z;
        draft.MINIMUM_ROAD_SEPARATION = MINIMUM_ROAD_SEPARATION;
        draft.MINIMUM_ROAD_EDGE_DISTANCE = MINIMUM_ROAD_EDGE_DISTANCE;
        draft.TERTIARY_ROAD_CHANCE = TERTIARY_ROAD_CHANCE;
        draft.TERTIARY_ROAD_MIN_LENGTH = TERTIARY_ROAD_MIN_LENGTH;
        draft.TERTIARY_ROAD_MAX_LENGTH = TERTIARY_ROAD_MAX_LENGTH;
        draft.PLANNED_PRIMARY_BRIDGE_CHANCE = PLANNED_PRIMARY_BRIDGE_CHANCE;
        draft.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH = PLANNED_PRIMARY_BRIDGE_MAX_LENGTH;
        draft.OPEN_LOT_PARK_CHANCE = OPEN_LOT_PARK_CHANCE;
        draft.PARK_ELEVATION = PARK_ELEVATION;
        draft.PARK_BORDER = PARK_BORDER;
        draft.PARK_STREET_THRESHOLD = PARK_STREET_THRESHOLD;
        draft.FOUNTAIN_CHANCE = FOUNTAIN_CHANCE;
        draft.CORRIDOR_CHANCE = CORRIDOR_CHANCE;
        draft.BRIDGE_CHANCE = BRIDGE_CHANCE;
        draft.BRIDGE_SUPPORTS = BRIDGE_SUPPORTS;
        draft.HIGHWAY_REQUIRES_TWO_CITIES = HIGHWAY_REQUIRES_TWO_CITIES;
        draft.HIGHWAY_LEVEL_FROM_CITIES_MODE = HIGHWAY_LEVEL_FROM_CITIES_MODE;
        draft.HIGHWAY_DISTANCE_MASK = HIGHWAY_DISTANCE_MASK;
        draft.HIGHWAY_MAINPERLIN_SCALE = HIGHWAY_MAINPERLIN_SCALE;
        draft.HIGHWAY_SECONDARYPERLIN_SCALE = HIGHWAY_SECONDARYPERLIN_SCALE;
        draft.HIGHWAY_PERLIN_FACTOR = HIGHWAY_PERLIN_FACTOR;
        draft.HIGHWAY_SUPPORTS = HIGHWAY_SUPPORTS;
        draft.RAILWAYS_ENABLED = RAILWAYS_ENABLED;
        draft.RAILWAY_STATIONS_ENABLED = RAILWAY_STATIONS_ENABLED;
        draft.RAILWAY_SURFACE_STATIONS_ENABLED = RAILWAY_SURFACE_STATIONS_ENABLED;
        draft.RAILWAYS_CAN_END = RAILWAYS_CAN_END;
        draft.RAILWAY_DUNGEON_CHANCE = RAILWAY_DUNGEON_CHANCE;
        draft.RUIN_CHANCE = RUIN_CHANCE;
        draft.RUIN_MINLEVEL_PERCENT = RUIN_MINLEVEL_PERCENT;
        draft.RUIN_MAXLEVEL_PERCENT = RUIN_MAXLEVEL_PERCENT;
        draft.RUBBLELAYER = RUBBLELAYER;
        draft.RUBBLE_DIRT_SCALE = RUBBLE_DIRT_SCALE;
        draft.RUBBLE_LEAVE_SCALE = RUBBLE_LEAVE_SCALE;
        draft.EXPLOSION_CHANCE = EXPLOSION_CHANCE;
        draft.EXPLOSION_MINRADIUS = EXPLOSION_MINRADIUS;
        draft.EXPLOSION_MAXRADIUS = EXPLOSION_MAXRADIUS;
        draft.EXPLOSION_MINHEIGHT = EXPLOSION_MINHEIGHT;
        draft.EXPLOSION_MAXHEIGHT = EXPLOSION_MAXHEIGHT;
        draft.MINI_EXPLOSION_CHANCE = MINI_EXPLOSION_CHANCE;
        draft.MINI_EXPLOSION_MINRADIUS = MINI_EXPLOSION_MINRADIUS;
        draft.MINI_EXPLOSION_MAXRADIUS = MINI_EXPLOSION_MAXRADIUS;
        draft.MINI_EXPLOSION_MINHEIGHT = MINI_EXPLOSION_MINHEIGHT;
        draft.MINI_EXPLOSION_MAXHEIGHT = MINI_EXPLOSION_MAXHEIGHT;
        draft.EXPLOSIONS_IN_CITIES_ONLY = EXPLOSIONS_IN_CITIES_ONLY;
        draft.DEBRIS_TO_NEARBYCHUNK_FACTOR = DEBRIS_TO_NEARBYCHUNK_FACTOR;
        draft.CHANCE_OF_RANDOM_LEAFBLOCKS = CHANCE_OF_RANDOM_LEAFBLOCKS;
        draft.THICKNESS_OF_RANDOM_LEAFBLOCKS = THICKNESS_OF_RANDOM_LEAFBLOCKS;
        draft.AVOID_FOLIAGE = AVOID_FOLIAGE;
        draft.LIGHTING_DENSITY = LIGHTING_DENSITY;
        draft.LOOT_DENSITY = LOOT_DENSITY;
        draft.SPAWN_BIOME = SPAWN_BIOME;
        draft.SPAWN_CITY = SPAWN_CITY;
        draft.SPAWN_NOT_IN_BUILDING = SPAWN_NOT_IN_BUILDING;
        draft.FORCE_SPAWN_IN_BUILDING = FORCE_SPAWN_IN_BUILDING;
        draft.FORCE_SPAWN_BUILDINGS = FORCE_SPAWN_BUILDINGS;
        draft.FORCE_SPAWN_PARTS = FORCE_SPAWN_PARTS;
        draft.SPAWN_CHECK_RADIUS = SPAWN_CHECK_RADIUS;
        draft.SPAWN_RADIUS_INCREASE = SPAWN_RADIUS_INCREASE;
        draft.SPAWN_CHECK_ATTEMPTS = SPAWN_CHECK_ATTEMPTS;
        draft.EDITMODE = EDITMODE;
        draft.GENERATE_NETHER = GENERATE_NETHER;
        return draft;
    }

    public Identifier getId() {
        return id;
    }

    /**
     * The authored display name, or {@code ""} when nothing in the {@code extends} chain declared
     * one. Callers rendering a label want {@link #getDisplayName()}, which fills that gap in; this
     * raw form exists so {@link #toDefinition()} round-trips "no name was authored" as an empty string
     * rather than inventing the id as an authored value.
     */
    public String getName() {
        return name;
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


    public String getExtraDescription() {
        return extraDescription;
    }


    public String getWarning() {
        return warning;
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

    public PresetDefinition toDefinition() {
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
        MiscSettings misc =
                new MiscSettings(
                Optional.of(EDITMODE),
                Optional.of(GENERATE_NETHER));

        return new PresetDefinition(
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
                Optional.of(misc));
    }

    // ------------------------------------------------------------------------ accessors

    /*
     * One per field, named for the JSON key its codec reads it from - the name a datapack author
     * already uses for it. Step 1 of issue #10: the fields are still public here, so nothing changes
     * except that no caller depends on them being. Making them private is the next commit, and that
     * is the one where the compiler enumerates every writer.
     */
    public LandscapeType landscapeType() {
        return LANDSCAPE_TYPE;
    }

    public int groundLevel() {
        return GROUNDLEVEL;
    }

    public int seaLevel() {
        return SEALEVEL;
    }

    public String liquidBlock() {
        return LIQUID_BLOCK;
    }

    public String baseBlock() {
        return BASE_BLOCK;
    }

    public int bedrockLayer() {
        return BEDROCK_LAYER;
    }

    public int terrainFixLowerMinOffset() {
        return TERRAIN_FIX_LOWER_MIN_OFFSET;
    }

    public int terrainFixLowerMaxOffset() {
        return TERRAIN_FIX_LOWER_MAX_OFFSET;
    }

    public int terrainFixUpperMinOffset() {
        return TERRAIN_FIX_UPPER_MIN_OFFSET;
    }

    public int terrainFixUpperMaxOffset() {
        return TERRAIN_FIX_UPPER_MAX_OFFSET;
    }

    public int oceanCorrectionBorder() {
        return OCEAN_CORRECTION_BORDER;
    }

    public boolean avoidWater() {
        return AVOID_WATER;
    }

    public boolean useAvgHeightmap() {
        return USE_AVG_HEIGHTMAP;
    }

    public double cityChance() {
        return CITY_CHANCE;
    }

    public int cityMinRadius() {
        return CITY_MINRADIUS;
    }

    public int cityMaxRadius() {
        return CITY_MAXRADIUS;
    }

    public double cityPerlinScale() {
        return CITY_PERLIN_SCALE;
    }

    public double cityPerlinOffset() {
        return CITY_PERLIN_OFFSET;
    }

    public double cityPerlinInnerScale() {
        return CITY_PERLIN_INNERSCALE;
    }

    public float cityThreshold() {
        return CITY_THRESHOLD;
    }

    public int citySpawnDistance1() {
        return CITY_SPAWN_DISTANCE1;
    }

    public int citySpawnDistance2() {
        return CITY_SPAWN_DISTANCE2;
    }

    public double citySpawnMultiplier1() {
        return CITY_SPAWN_MULTIPLIER1;
    }

    public double citySpawnMultiplier2() {
        return CITY_SPAWN_MULTIPLIER2;
    }

    public boolean cityAvoidVoid() {
        return CITY_AVOID_VOID;
    }

    public int cityLevel0Height() {
        return CITY_LEVEL0_HEIGHT;
    }

    public int cityLevel1Height() {
        return CITY_LEVEL1_HEIGHT;
    }

    public int cityLevel2Height() {
        return CITY_LEVEL2_HEIGHT;
    }

    public int cityLevel3Height() {
        return CITY_LEVEL3_HEIGHT;
    }

    public int cityLevel4Height() {
        return CITY_LEVEL4_HEIGHT;
    }

    public int cityLevel5Height() {
        return CITY_LEVEL5_HEIGHT;
    }

    public int cityLevel6Height() {
        return CITY_LEVEL6_HEIGHT;
    }

    public int cityLevel7Height() {
        return CITY_LEVEL7_HEIGHT;
    }

    public int cityMinHeight() {
        return CITY_MINHEIGHT;
    }

    public int cityMaxHeight() {
        return CITY_MAXHEIGHT;
    }

    public float scatteredChanceMultiplier() {
        return SCATTERED_CHANCE_MULTIPLIER;
    }

    public float buildingChance() {
        return BUILDING_CHANCE;
    }

    public int buildingMinFloors() {
        return BUILDING_MINFLOORS;
    }

    public int buildingMaxFloors() {
        return BUILDING_MAXFLOORS;
    }

    public int buildingMinFloorsChance() {
        return BUILDING_MINFLOORS_CHANCE;
    }

    public int buildingMaxFloorsChance() {
        return BUILDING_MAXFLOORS_CHANCE;
    }

    public int buildingMinCellars() {
        return BUILDING_MINCELLARS;
    }

    public int buildingMaxCellars() {
        return BUILDING_MAXCELLARS;
    }

    public float buildingDoorwayChance() {
        return BUILDING_DOORWAYCHANCE;
    }

    public float buildingFrontChance() {
        return BUILDING_FRONTCHANCE;
    }

    public boolean multiUseCorner() {
        return MULTI_USE_CORNER;
    }

    public MultiBuildingStreetConflict multiBuildingStreetConflict() {
        return MULTI_BUILDING_STREET_CONFLICT;
    }

    public boolean generateSpawners() {
        return GENERATE_SPAWNERS;
    }

    public int primaryRoadSpacingX() {
        return PRIMARY_ROAD_SPACING_X;
    }

    public int primaryRoadSpacingZ() {
        return PRIMARY_ROAD_SPACING_Z;
    }

    public float primaryRoadOptionalChance() {
        return PRIMARY_ROAD_OPTIONAL_CHANCE;
    }

    public int primaryRoadForceEvery() {
        return PRIMARY_ROAD_FORCE_EVERY;
    }

    public int secondaryRoadMinCountX() {
        return SECONDARY_ROAD_MIN_COUNT_X;
    }

    public int secondaryRoadMaxCountX() {
        return SECONDARY_ROAD_MAX_COUNT_X;
    }

    public int secondaryRoadMinCountZ() {
        return SECONDARY_ROAD_MIN_COUNT_Z;
    }

    public int secondaryRoadMaxCountZ() {
        return SECONDARY_ROAD_MAX_COUNT_Z;
    }

    public int minimumRoadSeparation() {
        return MINIMUM_ROAD_SEPARATION;
    }

    public int minimumRoadEdgeDistance() {
        return MINIMUM_ROAD_EDGE_DISTANCE;
    }

    public float tertiaryRoadChance() {
        return TERTIARY_ROAD_CHANCE;
    }

    public int tertiaryRoadMinLength() {
        return TERTIARY_ROAD_MIN_LENGTH;
    }

    public int tertiaryRoadMaxLength() {
        return TERTIARY_ROAD_MAX_LENGTH;
    }

    public float plannedPrimaryBridgeChance() {
        return PLANNED_PRIMARY_BRIDGE_CHANCE;
    }

    public int plannedPrimaryBridgeMaxLength() {
        return PLANNED_PRIMARY_BRIDGE_MAX_LENGTH;
    }

    public float openLotParkChance() {
        return OPEN_LOT_PARK_CHANCE;
    }

    public boolean parkElevation() {
        return PARK_ELEVATION;
    }

    public boolean parkBorder() {
        return PARK_BORDER;
    }

    public int parkStreetThreshold() {
        return PARK_STREET_THRESHOLD;
    }

    public float fountainChance() {
        return FOUNTAIN_CHANCE;
    }

    public float corridorChance() {
        return CORRIDOR_CHANCE;
    }

    public float bridgeChance() {
        return BRIDGE_CHANCE;
    }

    public boolean bridgeSupports() {
        return BRIDGE_SUPPORTS;
    }

    public boolean highwayRequiresTwoCities() {
        return HIGHWAY_REQUIRES_TWO_CITIES;
    }

    public int highwayLevelFromCities() {
        return HIGHWAY_LEVEL_FROM_CITIES_MODE;
    }

    public int highwayDistanceMask() {
        return HIGHWAY_DISTANCE_MASK;
    }

    public float highwayMainPerlinScale() {
        return HIGHWAY_MAINPERLIN_SCALE;
    }

    public float highwaySecondaryPerlinScale() {
        return HIGHWAY_SECONDARYPERLIN_SCALE;
    }

    public float highwayPerlinFactor() {
        return HIGHWAY_PERLIN_FACTOR;
    }

    public boolean highwaySupports() {
        return HIGHWAY_SUPPORTS;
    }

    public boolean railwaysEnabled() {
        return RAILWAYS_ENABLED;
    }

    public boolean railwayStationsEnabled() {
        return RAILWAY_STATIONS_ENABLED;
    }

    public boolean railwaySurfaceStationsEnabled() {
        return RAILWAY_SURFACE_STATIONS_ENABLED;
    }

    public boolean railwaysCanEnd() {
        return RAILWAYS_CAN_END;
    }

    public float railwayDungeonChance() {
        return RAILWAY_DUNGEON_CHANCE;
    }

    public float ruinChance() {
        return RUIN_CHANCE;
    }

    public float ruinMinlevelPercent() {
        return RUIN_MINLEVEL_PERCENT;
    }

    public float ruinMaxlevelPercent() {
        return RUIN_MAXLEVEL_PERCENT;
    }

    public boolean rubbleLayer() {
        return RUBBLELAYER;
    }

    public float rubbleDirtScale() {
        return RUBBLE_DIRT_SCALE;
    }

    public float rubbleLeaveScale() {
        return RUBBLE_LEAVE_SCALE;
    }

    public float explosionChance() {
        return EXPLOSION_CHANCE;
    }

    public int explosionMinRadius() {
        return EXPLOSION_MINRADIUS;
    }

    public int explosionMaxRadius() {
        return EXPLOSION_MAXRADIUS;
    }

    public int explosionMinHeight() {
        return EXPLOSION_MINHEIGHT;
    }

    public int explosionMaxHeight() {
        return EXPLOSION_MAXHEIGHT;
    }

    public float miniExplosionChance() {
        return MINI_EXPLOSION_CHANCE;
    }

    public int miniExplosionMinRadius() {
        return MINI_EXPLOSION_MINRADIUS;
    }

    public int miniExplosionMaxRadius() {
        return MINI_EXPLOSION_MAXRADIUS;
    }

    public int miniExplosionMinHeight() {
        return MINI_EXPLOSION_MINHEIGHT;
    }

    public int miniExplosionMaxHeight() {
        return MINI_EXPLOSION_MAXHEIGHT;
    }

    public boolean explosionsInCitiesOnly() {
        return EXPLOSIONS_IN_CITIES_ONLY;
    }

    public int debrisToNearbyChunkFactor() {
        return DEBRIS_TO_NEARBYCHUNK_FACTOR;
    }

    public float randomLeafBlockChance() {
        return CHANCE_OF_RANDOM_LEAFBLOCKS;
    }

    public int randomLeafBlockThickness() {
        return THICKNESS_OF_RANDOM_LEAFBLOCKS;
    }

    public boolean avoidFoliage() {
        return AVOID_FOLIAGE;
    }

    public float lightingDensity() {
        return LIGHTING_DENSITY;
    }

    public float lootDensity() {
        return LOOT_DENSITY;
    }

    public String spawnBiome() {
        return SPAWN_BIOME;
    }

    public String spawnCity() {
        return SPAWN_CITY;
    }

    public boolean spawnNotInBuilding() {
        return SPAWN_NOT_IN_BUILDING;
    }

    public boolean forceSpawnInBuilding() {
        return FORCE_SPAWN_IN_BUILDING;
    }

    public List<String> forceSpawnBuildings() {
        return FORCE_SPAWN_BUILDINGS;
    }

    public List<String> forceSpawnParts() {
        return FORCE_SPAWN_PARTS;
    }

    public int spawnCheckRadius() {
        return SPAWN_CHECK_RADIUS;
    }

    public int spawnRadiusIncrease() {
        return SPAWN_RADIUS_INCREASE;
    }

    public int spawnCheckAttempts() {
        return SPAWN_CHECK_ATTEMPTS;
    }

    public boolean editMode() {
        return EDITMODE;
    }

    public boolean generateNether() {
        return GENERATE_NETHER;
    }
}

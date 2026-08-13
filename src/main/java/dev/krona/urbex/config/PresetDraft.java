package dev.krona.urbex.config;

import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * A preset being written: by the {@code extends} chain as it resolves, or by the customization
 * editor as a player drags a slider.
 *
 * <p>The other half of {@link Preset}, which is a preset being read. Both carry the same values
 * under the same names, and both answer the same accessors - so a GUI binding or an assertion reads
 * identically against either - but only this one can be written to (issue #10).</p>
 *
 * <p>That is the whole of the change. Worldgen was handed a {@code Preset} with 119 public mutable
 * fields and nothing but convention stopped it writing to one; {@code copy()} existed because the
 * editor needed a scratch copy, and the discipline that kept the shared one read-only was a comment.
 * Now the type says which is which, and the compiler is what enforces it - which is the same move
 * issue #126 made for chunk plans, after an audit that searched for assignments missed a field being
 * decremented on every call.</p>
 *
 * <p>The fields are public and mutable on purpose. This is a builder that eleven
 * {@code *Settings.apply} methods and 119 GUI setter lambdas write to one field at a time, and a
 * wither per field would be 119 allocations per keystroke to express the same thing less clearly.</p>
 */
public final class PresetDraft {

    private final Identifier id;

    /*
     * Metadata, private, unlike the settings below. The settings are public because the GUI binds a
     * getter/setter lambda pair to each by name, and SettingsCompletenessTest reflects over exactly
     * that set to prove every editable field has a descriptor - a public field here would be a field
     * that test demands a slider for.
     */
    private String name = "";
    private String description = "Default generation, common cities, explosions";
    private String extraDescription = "";
    private String warning = "";
    private String iconFile = "";

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
    public boolean EDITMODE = false;
    public boolean GENERATE_NETHER = false;

    public PresetDraft(Identifier id) {
        this.id = id;
    }

    /** The preset this draft is of. Unchanged by editing: a customized preset keeps its base id. */
    public Identifier getId() {
        return id;
    }

    /** Settles this draft into the value worldgen reads. */
    public Preset resolve() {
        return new Preset(this);
    }

    /** A draft of the same values, so an editor can stage changes without touching this one. */
    public PresetDraft copy() {
        PresetDraft draft = new PresetDraft(id);
        draft.name = name;
        draft.description = description;
        draft.extraDescription = extraDescription;
        draft.warning = warning;
        draft.iconFile = iconFile;
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
        draft.CITY_STYLE_THRESHOLD = CITY_STYLE_THRESHOLD;
        draft.CITY_STYLE_ALTERNATIVE = CITY_STYLE_ALTERNATIVE;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getIconFile() {
        return iconFile;
    }

    public void setIconFile(String iconFile) {
        this.iconFile = iconFile;
    }

    // ------------------------------------------------------------------------ accessors

    /*
     * The same accessors {@link Preset} answers, so one GUI getter lambda - or one assertion -
     * reads the same against a draft and against what it resolves to.
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

    public float cityStyleThreshold() {
        return CITY_STYLE_THRESHOLD;
    }

    public String cityStyleAlternative() {
        return CITY_STYLE_ALTERNATIVE;
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

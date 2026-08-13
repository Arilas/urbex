package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.EffectiveRoad;
import dev.krona.urbex.plan.RoadCell;
import dev.krona.urbex.plan.RoadDirection;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.*;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.lost.cityassets.*;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.WorldSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

import static dev.krona.urbex.worldgen.CityGenerator.FLOORHEIGHT;

public class ChunkPlan {

    public final ChunkCoord coord;
    public final PlanningContext provider;
    public final Preset profile;
    public final int groundLevel;
    public final int waterLevel;

    // Final, and no longer volatile: CityGenerator.generate() used to clear this when it found a
    // blacklisted structure in the chunk, after the info was already in the shared cache and its
    // neighbours had derived from the old value. Suppression is local to that chunk's rendering now,
    // so what is published here is what the seed and the coordinate say and nothing rewrites it
    // (issue #126).
    public final boolean isCity;
    public final boolean hasBuilding;
    public final MultiPos multiBuildingPos;
    public final MultiBuilding multiBuilding;
    public final Building buildingType;

    public final BuildingPart fountainType;
    public final BuildingPart parkType;
    public final BuildingPart bridgeType;
    public final BuildingPart stairType;
    public final BuildingPart frontType;
    private final float stairPriority;      // A random number that indicates if this chunk should get a stair if there are competing stairs around it. The highest wins
    public final BuildingPart railDungeon;    // Dungeon next to rails. Will only generate if there are actually rails next to it
    public final StreetType streetType;
    private final RoadType effectiveRoad;   // The planned road this chunk renders, NONE for most chunks

    private final int floors;
    public final int cellars;
    public final BuildingPart[] floorTypes;
    public final BuildingPart[] floorTypes2;

    public final boolean[] connectionAtX;
    public final boolean[] connectionAtZ;
    public final float ruinHeight;      // The height (as a percentage between 0 and 1) at which we focus the ruin layer. Set to -1 if this building is not ruined

    public final int highwayXLevel;     // 0 or 1 if there is a highway at this chunk
    public final int highwayZLevel;     // 0 or 1 if there is a highway at this chunk
    public final int cityLevel;         // The first floor of buildings starts at groundLevel + cityLevel * 6

    public final boolean xBridge;       // A boolean indicating that this chunk is a candidate for holding a bridge (no guarantee)
    public final boolean zBridge;       // A boolean indicating that this chunk is a candidate for holding a bridge (no guarantee)

    public final boolean xRailCorridor; // A boolean indicating that this chunk is a candidate for holding a corridor (no guarantee)
    public final boolean zRailCorridor; // A boolean indicating that this chunk is a candidate for holding a corridor (no guarantee)

    public final Block doorBlock;

    // Transient info that is calculated on demand.
    //
    // A ChunkPlan lives in the dimension's cache and is read by every chunk that neighbours it,
    // so these are filled in from whichever thread got here first while other threads are reading.
    // They are all volatile, and every "…Calculated" flag is written *after* the value it guards,
    // so a reader that sees the flag set is guaranteed to see the value. Two threads racing on the
    // same field both compute and both write; the result is the same either way, because it is
    // derived from the (already fixed) info graph. This is the racy-single-check idiom, not
    // double-checked locking - there is no lock, on purpose: hasXBridge() walks and writes into its
    // neighbours, so any per-instance lock would be a lock-ordering deadlock waiting to happen.
    private volatile ChunkPlan xmin = null;   // @todo remove
    private volatile ChunkPlan xmax = null;   // @todo remove
    private volatile ChunkPlan zmin = null;   // @todo remove
    private volatile ChunkPlan zmax = null;   // @todo remove
    private volatile DamageArea damageArea = null;
    private Palette palette = null;             // written once, in the constructor
    private volatile CompiledPalette compiledPalette = null;
    private volatile Boolean isOcean = null;
    private volatile WorldStyle chunkWorldStyle = null;

    private volatile boolean xBridgeTypeCalculated = false;
    private volatile boolean zBridgeTypeCalculated = false;
    private volatile BuildingPart xBridgeType = null;
    private volatile BuildingPart zBridgeType = null;

    private volatile boolean plannedBridgeCalculated = false;
    private volatile PrimaryBridgePlanner.BridgeSpan plannedBridge;

    private volatile boolean streetSlopeCalculated = false;
    private volatile Direction streetSlopeDirection;

    private volatile boolean stairsCalculated = false;
    private volatile Direction stairDirection;
    private volatile boolean actualStairsCalculated = false;
    private volatile Direction actualStairDirection;

    private volatile MinMax desiredTerrainCorrectionHeights = null;
    private volatile MinMax desiredMaxHeight1 = null;

    // No per-generation runtime state here, and specifically no post-generation callbacks: those
    // belong to the ChunkGenContext that queued them (see PostTodoQueue). A ChunkPlan is a
    // cached, coordinate-addressed planning value shared by every generation that reads this chunk,
    // so anything on it that belonged to one generation could be evicted, drained by the wrong
    // region, or lost to a concurrent clear (issue #127).

    public static class ConditionTodo {
        private final String condition;
        private final String part;
        private final String building;

        public ConditionTodo(String condition, String part, ChunkPlan info) {
            this.part = part == null ? ConditionContext.NO_PART : part;
            this.condition = condition;
            if (info.hasBuilding) {
                this.building = info.getBuildingType();
            } else {
                this.building = ConditionContext.NO_PART;
            }
        }

        public String getCondition() {
            return condition;
        }

        public String getPart() {
            return part;
        }

        public String getBuilding() {
            return building;
        }
    }

    public BlockPos getCenter(int y) {
        return new BlockPos((coord.chunkX() << 4) + 8, y, (coord.chunkZ() << 4) + 8);
    }

    public BlockPos getRelativePos(int rx, int y, int rz) {
        return new BlockPos((coord.chunkX() << 4) + rx, y, (coord.chunkZ() << 4) + rz);
    }

    public CompiledPalette getCompiledPalette() {
        CompiledPalette cached = compiledPalette;
        if (cached != null) {
            return cached;
        }
        // Built into a local and published in one write: the half-built palette (without the
        // building's own entries merged in) must never be visible to another chunk's thread.
        CompiledPalette built = new CompiledPalette(palette);
        if (hasBuilding) {
            Palette buildingPalette = buildingType.getLocalPalette();
            if (buildingPalette != null) {
                built = new CompiledPalette(built, buildingPalette);
            }
        }
        compiledPalette = built;
        return built;
    }

    public DamageArea getDamageArea() {
        if (damageArea == null) {
            damageArea = new DamageArea(coord.chunkX(), coord.chunkZ(), provider, this);
        }
        return damageArea;
    }

    /**
     * The world style governing this chunk: the dominant nearby city's, so a chunk on a city's edge
     * takes that city's look rather than a coin flip, and a world that mixes datapacks does not
     * produce half-and-half cities.
     * <p>
     * Memoised because a {@link ChunkPlan} is per-chunk and long-lived while the lookup behind
     * it walks the city neighbourhood - and because {@code CityGenerator.transformBlockState} reads
     * the rotatable tag off it for every block of every rotated part. Racy single-check like the
     * other lazy fields here: the value is a pure function of the coordinate, so a lost race just
     * recomputes it.
     */
    public WorldStyle worldStyle() {
        WorldStyle known = chunkWorldStyle;
        if (known == null) {
            known = provider.worldStyles().atChunk(provider, coord);
            chunkWorldStyle = known;
        }
        return known;
    }

    public Style getOutsideStyle() {
        return provider.assets().styles().get(worldStyle().getOutsideStyle());
    }

    private void createPalette(RandomSource rand) {
        Style style;
        if (!isCity) {
            style = getOutsideStyle();
        } else {
            String name = getCityStyle().getStyle();
            style = provider.assets().styles().getOrThrow(name);
        }
        palette = style.getRandomPalette(rand);
    }

    public ChunkPlan getXmin() {
        if (xmin == null) {
            xmin = getChunkPlan(coord.west(), provider);
        }
        return xmin;
    }

    public ChunkPlan getXmax() {
        if (xmax == null) {
            xmax = getChunkPlan(coord.east(), provider);
        }
        return xmax;
    }

    public ChunkPlan getZmin() {
        if (zmin == null) {
            zmin = getChunkPlan(coord.north(), provider);
        }
        return zmin;
    }

    public ChunkPlan getZmax() {
        if (zmax == null) {
            zmax = getChunkPlan(coord.south(), provider);
        }
        return zmax;
    }

    public int getMaxHeight() {
        if (hasBuilding) {
            return getCityGroundLevel() + floors * FLOORHEIGHT;
        } else {
            int m = getMaxHighwayLevel();
            if (m >= 0) {
                return groundLevel + m * FLOORHEIGHT;
            } else {
                return getCityGroundLevel();
            }
        }
    }

    public int getCityGroundLevel() {
        return groundLevel + cityLevel * FLOORHEIGHT;
    }

    /**
     * Get the city ground level but lower the level outside cities
     */
    public int getCityGroundLevelOutsideLower() {
        if (isCity) {
            return groundLevel + cityLevel * FLOORHEIGHT;
        } else {
            return groundLevel + cityLevel * FLOORHEIGHT - 1;
        }
    }

    public boolean isValidFloor(int l) {
        return (l + cellars) >= 0 && (l + cellars) < floorTypes.length;
    }

    public BuildingPart getFloor(int l) {
        return floorTypes[l + cellars];
    }

    public BuildingPart getFloorPart2(int l) {
        return floorTypes2[l + cellars];
    }

    public Building getBuilding() {
        return buildingType;
    }

    public CityStyle getCityStyle() {
        return getChunkCandidate(coord, provider).cityStyle();
    }

    // Version for usage inside the gui
    public static boolean hasBuildingGui(int chunkX, int chunkZ, PlanningContext provider, ChunkCandidate candidate) {
//        Random rand = getBuildingRandom(chunkX, chunkZ, provider.seed());
//        rand.nextFloat();       // Compatibility?

        return candidate.couldHaveBuilding();
    }

    public static ChunkCandidate getChunkCandidateGui(ChunkCoord key, PlanningContext provider) {
//        ChunkCandidate cached = CITY_INFO_MAP.get(key);
//        if (cached != null) {
//            return cached;
//        }
        int chunkX = key.chunkX();
        int chunkZ = key.chunkZ();
        Preset profile = provider.preset();

        boolean isCity = isCityRaw(key, provider, profile);
        int cityLevel = getCityLevelGui(key, provider);
        RandomSource rand = getBuildingRandom(chunkX, chunkZ, provider.seed(), Rng.Purpose.BUILDING);
        boolean couldHaveBuilding = isCity && rand.nextFloat() < profile.buildingChance();
        // The preview resolves neither a multi-building section nor a style, exactly as before -
        // those three stay null here rather than being computed for a screen that does not use them.
        return new ChunkCandidate(isCity, couldHaveBuilding, null, cityLevel, null, null, null);
    }

    /**
     * Not synchronized: the candidate of a chunk are a pure function of the seed and the
     * coordinate, so two threads racing on the same coordinate compute the same answer and one of
     * them simply loses the putIfAbsent below. Locking here would be a deadlock waiting to happen -
     * this method reaches its neighbours' city styles, which reach back here.
     */
    public static ChunkCandidate getChunkCandidate(ChunkCoord coord, PlanningContext provider) {
        ChunkCandidate cached = provider.caches().candidate.get(coord);
        if (cached != null) {
            return cached;
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        Preset profile = provider.preset();

        // Computed into locals and constructed once at the end. This used to assemble a mutable
        // object field by field and hand the half-built thing to its own helpers; the record cannot
        // be published half-built, which is the point (issue #126).
        boolean isCity = isCityRaw(coord, provider, profile);

        MultiSection section = isCity ? multiBuildingSection(coord, provider, profile) : MultiSection.NONE;
        MultiPos multiPos = section.pos();
        MultiBuilding multiBuilding = section.building();

        int cityLevel;
        if (multiPos.isSingle()) {
            cityLevel = getCityLevel(coord, provider);
        } else {
            cityLevel = profile.multiUseCorner() ? getTopLeftCityLevel(multiPos, coord, provider)
                    : getAverageCityLevel(multiPos, coord, provider);
        }
        RandomSource rand = getBuildingRandom(chunkX, chunkZ, provider.seed(), Rng.Purpose.BUILDING);
        boolean couldHaveBuilding = ChunkContentResolver.couldHaveBuilding(profile,
                isCity, multiPos, cityLevel, rand,
                chunkFacts(coord, provider, profile));

        CityStyle cityStyle;
        // If this is a street we find other chunks connected to this and pick the cityStyle
        // that represents the majority. This is to prevent streets from switching style randomly if two
        // different styled cities mix
        if (isCity && !couldHaveBuilding) {
            // Counted by Identifier, not by the id's String form. A tie is the ordinary case at a
            // style boundary (ten votes: 3x3 neighbours plus the centre twice), and
            // Counter.getMostOccuring() breaks ties on a stated rule rather than HashMap bucket
            // order. That rule is Identifier's own order - path, then namespace - which is what
            // MultiChunk's city-style sort already uses; tie-breaking on toString() instead would
            // order the same asset kind namespace-first here and path-first there, and the two
            // disagree as soon as a second namespace ships a city style.
            Counter<Identifier> counter = new Counter<>();
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    ChunkCoord key = coord.offset(cx, cz);
                    cityStyle = City.getCityStyle(key, provider, profile);
                    counter.add(cityStyle.getId());
                    if (cx == 0 && cz == 0) {
                        counter.add(cityStyle.getId());   // Add this chunk again for a bias
                    }
                }
            }
            cityStyle = provider.assets().cityStyles().get(counter.getMostOccuring(Comparator.naturalOrder()));
        } else {
            cityStyle = City.getCityStyle(coord, provider, profile);
        }
        Building buildingType;
        if (multiPos.isMulti() && !multiPos.isTopLeft()) {
            if (multiBuilding != null) {
                String b = multiBuilding.getBuilding(multiPos.x(), multiPos.z());
                buildingType = provider.assets().buildings().getOrThrow(b);
            } else {
                // @todo is this even possible?
                buildingType = getTopLeftCityInfo(multiPos, coord, provider).buildingType();
                if (buildingType == null) {
                    throw new RuntimeException("Topleft building type is not set!");
                }
            }
        } else {
            PredefinedBuilding predefinedBuilding = City.getPredefinedBuildingAtTopLeft(provider, coord);
            if (multiPos.isTopLeft()) {
                String b = multiBuilding.getBuilding(0, 0);
                buildingType = provider.assets().buildings().getOrThrow(b);
            } else {
                String name = cityStyle.getRandomBuilding(rand, coord);
                if (predefinedBuilding != null) {
                    name = predefinedBuilding.building();
                }
                if (name == null) {
                    throw new RuntimeException("Invalid building for multibuilding!");
                }
                buildingType = provider.assets().buildings().getOrThrow(name);
            }
        }

        ChunkCandidate candidate = new ChunkCandidate(isCity, couldHaveBuilding,
                multiPos, cityLevel, cityStyle, multiBuilding, buildingType);
        ChunkCandidate raced = provider.caches().candidate.putIfAbsent(coord, candidate);
        return raced != null ? raced : candidate;
    }

    /**
     * Don't use the cache as we're busy building the cache.
     */
    public static boolean isCityRaw(ChunkCoord coord, PlanningContext provider, Preset profile) {
        if (isVoidChunk(coord, provider)) {
            // If we have a void chunk then no city here
            return false;
        }

        float cityFactor = City.getCityFactor(coord, provider, profile);
        return cityFactor > profile.cityThreshold();
    }

    public static boolean isCity(ChunkCoord coord, PlanningContext provider) {
        return getChunkCandidate(coord, provider).isCity();
    }

    /**
     * The road a chunk actually renders: the raw {@link dev.krona.urbex.plan.RoadField} clipped to
     * the city mask. A road needs its own chunk to be raw city and at least one chunk it connects to
     * to be raw city as well, which is what removes the isolated one-chunk stubs a city mask's
     * protrusions would otherwise leave behind.
     *
     * <p>Static and raw-city-only on purpose. This is consulted while the chunk candidate are
     * still being computed, so it may not read anything that depends on a building decision - its
     * own or a neighbour's - or the decision graph stops being acyclic.
     */
    public static RoadType effectiveRoadType(ChunkCoord coord, PlanningContext provider, Preset profile) {
        // The city test comes first, and it is not a matter of taste. Every chunk in the world builds
        // a ChunkPlan, and the great majority of them are wilderness; asking the road field first
        // would build the block layout five times over - once for this chunk and once per neighbour
        // probe, each one sorting a candidate list with a comparator that hashes twice per comparison
        // - only for the clip below to throw the answer away. EffectiveRoad.resolve returns NONE for
        // a non-city chunk whatever the field said, so hoisting the test cannot change the answer.
        if (!isCityRaw(coord, provider, profile)) {
            return RoadType.NONE;
        }
        RoadCell cell = provider.roadField().at(coord.chunkX(), coord.chunkZ());
        boolean connectedCityNeighbour = false;
        for (RoadDirection direction : RoadDirection.values()) {
            if (cell.connects(direction)) {
                ChunkCoord adjacent = coord.offset(direction.stepX(), direction.stepZ());
                if (isCityRaw(adjacent, provider, profile)) {
                    connectedCityNeighbour = true;
                    break;
                }
            }
        }
        // isCity is true: the early return above is the only way past this point.
        return EffectiveRoad.resolve(cell.type(), true, connectedCityNeighbour, false);
    }

    /**
     * The world lookups {@link ChunkContentResolver#couldHaveBuilding} may need, each still behind a
     * supplier so the decision keeps its short-circuiting: consulting {@link Highway} or
     * {@link Railway} for a chunk whose building roll already failed would be new work, and
     * {@code Railway}'s chunk types are mutable state.
     */
    private static ChunkContentResolver.ChunkFacts chunkFacts(ChunkCoord coord, PlanningContext provider, Preset profile) {
        return new ChunkContentResolver.ChunkFacts(
                () -> City.getPredefinedBuildingAtTopLeft(provider, coord) != null,
                () -> City.getPredefinedStreetAt(provider, coord) != null,
                () -> City.getCityStyle(coord, provider, profile),
                () -> effectiveRoadType(coord, provider, profile),
                () -> hasHighway(coord, provider, profile),
                () -> Math.max(Highway.getXHighwayLevel(coord, provider, profile), Highway.getZHighwayLevel(coord, provider, profile)),
                () -> hasRailway(coord, provider, profile),
                () -> Railway.getRailChunkType(coord, provider, profile));
    }

    /**
     * Which multi-building section a chunk belongs to, if any. Returned rather than written into a
     * half-built {@link ChunkCandidate} - see that type for why (issue #126).
     *
     * @param pos      {@link MultiPos#SINGLE} when this chunk is not part of a multi-building
     * @param building null whenever {@code pos} is {@code SINGLE}
     */
    private record MultiSection(MultiPos pos, MultiBuilding building) {
        static final MultiSection NONE = new MultiSection(MultiPos.SINGLE, null);
    }

    private static MultiSection multiBuildingSection(ChunkCoord coord, PlanningContext provider, Preset profile) {
        // If a chunk is occupied according to City then there is a predefined building or street here.
        // Try to look for it
        if (City.isChunkOccupied(provider, coord)) {
            PredefinedIndex.BuildingAt predefinedBuilding = City.getPredefinedBuilding(provider, coord);
            if (predefinedBuilding != null) {
                if (predefinedBuilding.building().multi()) {
                    MultiBuilding building = provider.assets().multiBuildings().getOrThrow(predefinedBuilding.building().building());
                    return new MultiSection(new MultiPos(predefinedBuilding.offsetX(), predefinedBuilding.offsetZ(),
                            building.getDimX(), building.getDimZ()), building);
                }
            }
            return MultiSection.NONE;
        }

        MultiChunk multiChunk = MultiChunk.getOrCreate(provider, coord);
        MultiChunk.MB multiBuilding = multiChunk.getMultiBuilding(coord);
        if (multiBuilding == null) {
            return MultiSection.NONE;
        }

        MultiBuilding building = provider.assets().multiBuildings().getOrThrow(multiBuilding.name());
        return new MultiSection(new MultiPos(multiBuilding.offsetX(), multiBuilding.offsetZ(),
                building.getDimX(), building.getDimZ()), building);
    }

    private ChunkPlan calculateTopLeft() {
        if (multiBuildingPos.isTopLeft()) {
            return this;
        }
        ChunkCoord key = coord.offset(-multiBuildingPos.x(), -multiBuildingPos.z());
        return getChunkPlan(key, provider);
    }

    private static int getAverageCityLevel(MultiPos mp, ChunkCoord coord, PlanningContext provider) {
        int level = 0;
        int topX = coord.chunkX() - mp.x();
        int topZ = coord.chunkZ() - mp.z();
        for (int x = 0; x < mp.w(); x++) {
            for (int z = 0; z < mp.h(); z++) {
                ChunkCoord key = new ChunkCoord(provider.dimension(), topX + x, topZ + z);
                level += getCityLevel(key, provider);
            }
        }
        return level / (mp.w() * mp.h());
    }

    private static int getTopLeftCityLevel(MultiPos mp, ChunkCoord coord, PlanningContext provider) {
        int topX = coord.chunkX() - mp.x();
        int topZ = coord.chunkZ() - mp.z();
        ChunkCoord key = new ChunkCoord(provider.dimension(), topX, topZ);
        return getCityLevel(key, provider);
    }

    /**
     * The candidate of this multi-building's top-left chunk. Only reached from the one branch
     * that needs the top-left's building type, and only when {@code mp} is not itself the top left -
     * the caller has no half-built object to hand back for that case any more, and does not need one.
     */
    private static ChunkCandidate getTopLeftCityInfo(MultiPos mp, ChunkCoord coord, PlanningContext provider) {
        ChunkCoord key = coord.offset(-mp.x(), -mp.z());
        return getChunkCandidate(key, provider);
    }

    public static boolean hasHighway(ChunkCoord coord, PlanningContext provider, Preset profile) {
        return Highway.getXHighwayLevel(coord, provider, profile) >= 0 || Highway.getZHighwayLevel(coord, provider, profile) >= 0;
    }

    public static boolean hasRailway(ChunkCoord coord, PlanningContext provider, Preset profile) {
        return Railway.getRailChunkType(coord, provider, profile).getType() != RailChunkType.NONE;
    }

    public static boolean hasRailwayAtSurface(ChunkCoord coord, PlanningContext provider, Preset profile) {
        RailChunkType type = Railway.getRailChunkType(coord, provider, profile).getType();
        return type.isSurface() || type.isStation();
    }

    public Railway.RailChunkInfo getRailInfo() {
        return Railway.getRailChunkType(coord, provider, profile);
    }

    // Return true if a highway at this level would be a tunnel
    public boolean isTunnel(int level) {
        if (isCity) {
            // We need a tunnel if the city goes above this level
            return cityLevel > level;
        }

        // Get the (possbily cached) heightmap for this chunk
        ChunkHeightmap heightmap = provider.heightmap(coord);
        // The height at which the highway would be + a threshold of 3
        int highwayHeight = groundLevel + level * FLOORHEIGHT + 3;
        // If there are many places in the chunk above this height we will need a tunnel
        return heightmap.getHeight() > highwayHeight;
    }

    /**
     * Not synchronized, and deliberately not computeIfAbsent: constructing a ChunkPlan reads its
     * neighbours', so populating inside the map's bin lock deadlocks. Racing threads both build one
     * and one of them is thrown away - identical, because it is a pure function of seed + coord.
     */
    public static ChunkPlan getChunkPlan(ChunkCoord key, PlanningContext provider) {
        ChunkPlan info = provider.caches().chunkPlan.get(key);
        if (info != null) {
            return info;
        }
        info = new ChunkPlan(key, provider, null);
        ChunkPlan raced = provider.caches().chunkPlan.putIfAbsent(key, info);
        return raced != null ? raced : info;
    }

    /**
     * The building an editing command asked for, in place of the one the seed chose. Applied by the
     * constructor rather than written over a finished object, which is what lets every field it
     * touches be {@code final}.
     */
    public record BuildingOverride(Building building, int cellars, int floors, int groundLevel) {}

    /**
     * A ChunkPlan for {@code key} carrying {@code override}'s building instead of the one the seed
     * chose, and deliberately never published to the cache.
     * <p>
     * {@code /urbex createbuilding} used to take the cached instance and rewrite its building,
     * floors, cellars and ground level in place through a {@code setBuildingType} method. That left
     * the shared plan for the chunk describing a building the seed never chose - for every later
     * generation and every neighbour that read it, until the entry happened to be evicted - and it
     * was the last writer of a published plan, so seven fields had to stay non-final for it. The
     * override is a constructor argument now: nothing outside this class can reach a published
     * ChunkPlan and change it, and the compiler is what says so (issue #126).
     * <p>
     * The floors it builds are drawn from this chunk's ordinary layout stream rather than the
     * separate one {@code setBuildingType} used, so a hand-placed building picks the parts a building
     * of that shape would have picked here. Nothing reads back what the command draws, so the change
     * is only visible as which parts the preview stacks.
     */
    public static ChunkPlan detachedForEditing(ChunkCoord key, PlanningContext provider,
                                                  BuildingOverride override) {
        return new ChunkPlan(key, provider, override);
    }

    private ChunkPlan(ChunkCoord key, PlanningContext provider, @Nullable BuildingOverride override) {
        this.provider = provider;
        this.coord = key;

        profile = provider.preset();

        ChunkCandidate candidate = getChunkCandidate(key, provider);

        cityLevel = candidate.cityLevel();
        // Every override use below is a ternary whose null branch is exactly what this constructor
        // did before, so the ordinary path draws from `rand` in the same order and the same number of
        // times. Only an instance from detachedForEditing carries one (issue #126).
        buildingType = override != null ? override.building() : candidate.buildingType();
        multiBuilding = candidate.multiBuilding();
        multiBuildingPos = candidate.multiPos();

        RandomSource rand = getBuildingRandom(coord.chunkX(), coord.chunkZ(), provider.seed(), Rng.Purpose.BUILDING_LAYOUT);

        CityStyle cs = candidate.cityStyle();

        isCity = candidate.isCity();
        effectiveRoad = effectiveRoadType(key, provider, profile);

        ChunkContent content = ChunkContentResolver.resolve(profile, provider.seed(), rand,
                isCity, candidate.couldHaveBuilding(), effectiveRoad, multiBuildingPos, coord,
                neighbour -> getChunkCandidate(neighbour, provider).buildingType().getPrefersLonely(),
                candidate.buildingType().getName());
        hasBuilding = override != null || content.hasBuilding();

        groundLevel = override != null ? override.groundLevel() : profile.groundLevel();
        int wl = profile.seaLevel();
        waterLevel = wl == -1 ? provider.shape().seaLevel() : wl;
        WorldSettings.RailwayAvoidance avoidance = provider.worldStyles().primary().getWorldSettings().railwayAvoidance();

        // In a multi building we copy all information from the top-left chunk
        if (multiBuildingPos.isMulti() && !multiBuildingPos.isTopLeft()) {
            ChunkPlan topleft = calculateTopLeft();
            highwayXLevel = topleft.highwayXLevel;
            highwayZLevel = topleft.highwayZLevel;
            streetType = topleft.streetType;
            fountainType = topleft.fountainType;
            parkType = topleft.parkType;
            floors = override != null ? override.floors() : topleft.floors;
            cellars = override != null ? override.cellars() : topleft.cellars;
            doorBlock = topleft.doorBlock;
            bridgeType = topleft.bridgeType;
            stairType = topleft.stairType;
            stairPriority = topleft.stairPriority;
            palette = topleft.palette;
            compiledPalette = topleft.getCompiledPalette();
            ruinHeight = topleft.ruinHeight;
        } else {
            PredefinedBuilding predefinedBuilding = City.getPredefinedBuildingAtTopLeft(provider, key);
            highwayXLevel = Highway.getXHighwayLevel(key, provider, profile);
            highwayZLevel = Highway.getZHighwayLevel(key, provider, profile);

            streetType = content.streetType();
            float fountainChance = cs.getFountainChance() != null ? cs.getFountainChance() : profile.fountainChance();
            if (rand.nextFloat() < fountainChance) {
                fountainType = provider.assets().parts().getOrWarn(cs.getRandomFountain(rand, this.coord));
            } else {
                fountainType = null;
            }
            // The selection draw is unconditional so the layout stream never depends on the outcome;
            // only whether the chosen part is kept follows the open-lot park chance. A road, a
            // building or anything outside a city keeps nothing: the park surface is an open lot's,
            // and a part with no lot under it would sit on the carriageway.
            BuildingPart park = provider.assets().parts().getOrWarn(cs.getRandomPark(rand, this.coord));
            parkType = content.parkPart() ? park : null;
            float cityFactor = City.getCityFactor(coord, provider, profile);

            int maxfloors = getMaxfloors(cs);
            int f = profile.buildingMinFloors() + rand.nextInt((int) (profile.buildingMinFloorsChance() + (cityFactor + .1f) * (profile.buildingMaxFloorsChance() - profile.buildingMinFloorsChance())));
            f++;
            if (f > maxfloors) {
                f = maxfloors;
            }
            int minfloors = getMinfloors(cs);
            if (f < minfloors) {
                f = minfloors;
            }

            int max = provider.shape().maxY() - 1 - FLOORHEIGHT;
            while (getCityGroundLevel() + f * FLOORHEIGHT >= max) {
                f--;
            }
            floors = override != null ? override.floors() : f;

            int maxcellars = getMaxcellars(cs);
            int mincellars = Math.max(profile.buildingMinCellars(), buildingType.getMinCellars());
            int fb = mincellars + ((maxcellars <= 0) ? 0 : rand.nextInt(maxcellars + 1));
            boolean checkHighway = getMaxHighwayLevel() >= 0;
            boolean checkRailway = avoidance != WorldSettings.RailwayAvoidance.BLOCK_RAILWAY && getRailInfo() != Railway.RailChunkInfo.NOTHING;
            if (checkHighway || checkRailway) {
                // If we are above a highway or railway we make sure we can't have too many cellars
                int maxUnder;
                if (checkRailway && checkHighway) {
                    maxUnder = Math.max(getMaxHighwayLevel(), getRailInfo().getLevel());
                } else if (checkRailway) {
                    maxUnder = getRailInfo().getLevel();
                } else {
                    maxUnder = getMaxHighwayLevel();
                }

                int partlevel = provider.worldStyles().primary().getWorldSettings().railPartHeight6();
                fb = Math.min(cityLevel - maxUnder - partlevel, fb);
                if (fb < 0) {
                    fb = 0;
                }
            }
            if (fb > maxcellars) {
                fb = maxcellars;
            }
            cellars = override != null ? override.cellars() : fb;

            doorBlock = getRandomDoor(rand);
            bridgeType = provider.assets().parts().getOrThrow(cs.getRandomBridge(rand, this.coord));
            stairType = provider.assets().parts().getOrWarn(cs.getRandomStair(rand, this.coord));
            stairPriority = rand.nextFloat();
            createPalette(rand);
            // Preserve the legacy building stream slot formerly used by buildingWithoutLootChance.
            rand.nextFloat();
            float r = rand.nextFloat();
            if (rand.nextFloat() < profile.ruinChance() && (predefinedBuilding == null || !predefinedBuilding.preventRuins())) {
                ruinHeight = profile.ruinMinlevelPercent() + (profile.ruinMaxlevelPercent() - profile.ruinMinlevelPercent()) * r;
            } else {
                ruinHeight = -1;
            }
        }

        // The railway/building collision used to be resolved here, by writing NOTHING over the
        // published railInfo entry for this chunk. That is a planner constructor editing another
        // planning cache: rail planning reads its neighbours' entries and MultiChunk reads them when
        // accepting a multi-building, so what they saw depended on whether this chunk's ChunkPlan
        // had been built yet (issue #126). It is a pure query now - Railway.buildingBlocksRail -
        // asked at generation time, and the rail is suppressed where it is drawn instead of being
        // deleted from the plan. See that method for the precedence and what it costs.

        floorTypes = new BuildingPart[floors + cellars + 1];
        floorTypes2 = new BuildingPart[floors + cellars + 1];

        connectionAtX = new boolean[floors + cellars + 1];
        connectionAtZ = new boolean[floors + cellars + 1];
        String belowPart = ConditionContext.NO_PART;
        Building building = (Building) getBuilding();
        for (int i = 0; i <= floors + cellars; i++) {
            // The current part is NO_PART, deliberately, and not "the part we are about to pick":
            // this context is the input to choosing parts[i], so at this moment the floor has no
            // part. A parts[] entry's own "inpart" therefore never matches, which is correct rather
            // than broken - "the part below" is what a parts[] entry can usefully condition on, and
            // that is "belowpart", passed here and carrying the previous floor's parts[] pick.
            ConditionContext conditionContext = new ConditionContext(cityLevel + i - cellars, i - cellars, cellars, floors, ConditionContext.NO_PART, belowPart, building.getName(), coord) {
                @Override
                public boolean isBuilding() {
                    return true;
                }

                @Override
                public Identifier getBiome() {
                    // provider.biome() asks the biome source directly, where the old
                    // getWorld().getBiome() went via BiomeManager and its seeded sub-quart fuzzy
                    // offset - so the two can disagree right at a quart boundary. Forced: a cached
                    // ChunkPlan is reached from its neighbours' generation and has no region to
                    // ask, and the dimension's own level would go looking for unloaded chunks.
                    Holder<Biome> biome = provider.biome(getCenter(0));
                    return biome.unwrap().map(ResourceKey::identifier, b -> provider.registryAccess().lookup(Registries.BIOME).orElseThrow().getKey(b));
                }
            };
            String part = building.getRandomPart(rand, conditionContext);
            if (part == null) {
                throw new RuntimeException("Misconfiguration! Floor were generated for a building where no part condition matches!");
            }
            floorTypes[i] = provider.assets().parts().getOrThrow(part);

            // parts2[] is the second part of *this* floor, so it does have a current part - the
            // parts[] pick just made - while "the part below" is still the previous floor's.
            // getRandomPart2 derives its own context from this one (ConditionContext.withPart)
            // rather than being handed one, which is what stops belowPart being poisoned the way it
            // used to be when this loop advanced it before building a second context by hand.
            String part2 = building.getRandomPart2(rand, conditionContext, part);
            floorTypes2[i] = provider.assets().parts().get(part2);    // null is legal
            // Last in the body: what still reads this local is the *next* iteration's parts[]
            // context, at the top of the loop, which must see the floor below rather than this one.
            belowPart = part;
            connectionAtX[i] = isCity(coord.west(), provider) && (rand.nextFloat() < profile.buildingDoorwayChance());
            connectionAtZ[i] = isCity(coord.north(), provider) && (rand.nextFloat() < profile.buildingDoorwayChance());
        }

        float corridorChance = cs.getCorridorChance() != null ? cs.getCorridorChance() : profile.corridorChance();
        if (hasBuilding && cellars > 0) {
            xRailCorridor = false;
            zRailCorridor = false;
        } else {
            xRailCorridor = rand.nextFloat() < corridorChance;
            zRailCorridor = rand.nextFloat() < corridorChance;
        }

        if (isCity) {
            xBridge = false;
            zBridge = false;
        } else {
            xBridge = rand.nextFloat() < profile.bridgeChance();
            zBridge = rand.nextFloat() < profile.bridgeChance();
        }

        if (rand.nextFloat() < profile.railwayDungeonChance()) {
            if (!hasBuilding || (Railway.RAILWAY_LEVEL_OFFSET < (cityLevel - cellars))) {
                railDungeon = provider.assets().parts().getOrWarn(getCityStyle().getRandomRailDungeon(rand, this.coord));
            } else {
                railDungeon = null;
            }
        } else {
            railDungeon = null;
        }

        float frontChance = cs.getFrontChance() != null ? cs.getFrontChance() : profile.buildingFrontChance();
        if (rand.nextFloat() < frontChance) {
            frontType = provider.assets().parts().getOrWarn(getCityStyle().getRandomFront(rand, this.coord));
        } else {
            frontType = null;
        }
    }

    private int getMaxcellars(CityStyle cs) {
        int maxcellars = profile.buildingMaxCellars() + cityLevel;
        if (buildingType.getMaxCellars() != -1 && buildingType.getOverrideFloors()) {
            maxcellars = buildingType.getMaxCellars();
            return maxcellars;
        }
        if (buildingType.getMinCellars() != -1 && buildingType.getOverrideFloors()) {
            maxcellars = buildingType.getMinCellars();
            return maxcellars;
        }
        if (buildingType.getMaxCellars() != -1) {
            maxcellars = Math.min(maxcellars, buildingType.getMaxCellars());
        }
        if (buildingType.getMinCellars() != -1) {
            maxcellars = Math.max(maxcellars, buildingType.getMinCellars());
        }
        if (cs.getMaxCellarCount() != null) {
            maxcellars = Math.min(maxcellars, cs.getMaxCellarCount());
        }
        if (cs.getMinCellarCount() != null) {
            maxcellars = Math.max(maxcellars, cs.getMinCellarCount());
        }
        return maxcellars;
    }

    private int getMinfloors(CityStyle cs) {
        int minfloors = profile.buildingMinFloors() + 1;    // +1 because this doesn't count the top
        if (buildingType.getMinFloors() != -1 && buildingType.getOverrideFloors()) {
            minfloors = buildingType.getMinFloors();
            return minfloors;
        }
        if (buildingType.getMinFloors() != -1) {
            minfloors = Math.max(minfloors, buildingType.getMinFloors());
        }
        if (cs.getMinFloorCount() != null) {
            minfloors = Math.max(minfloors, cs.getMinFloorCount());
        }
        return minfloors;
    }

    private int getMaxfloors(CityStyle cs) {
        int maxfloors = profile.buildingMaxFloors();
        if (buildingType.getMaxFloors() != -1 && buildingType.getOverrideFloors()) {
            maxfloors = buildingType.getMaxFloors();
            return maxfloors;
        }
        if (buildingType.getMaxFloors() != -1) {
            maxfloors = Math.min(maxfloors, buildingType.getMaxFloors());
        }
        if (cs.getMaxFloorCount() != null) {
            maxfloors = Math.min(maxfloors, cs.getMaxFloorCount());
        }
        return maxfloors;
    }

    public Boolean getAllowDoors() {
        return buildingType.getAllowDoors();
    }

    public Boolean getAllowFillers() {

        return buildingType.getAllowFillers();
    }

    public Boolean getOverrideFloors() {
        return buildingType.getOverrideFloors();
    }


    public int getHighwayXLevel() {
        return Highway.getXHighwayLevel(coord, provider, profile);
    }

    public int getHighwayZLevel() {
        return Highway.getZHighwayLevel(coord, provider, profile);
    }


    /**
     * Return true if this is a void chunk (only for floating island worldtype). This does
     * not use the cache so it is safe to use when the cache is building
     */
    public static boolean isVoidChunk(ChunkCoord coord, PlanningContext provider) {
        if (provider.preset().isFloating()) {
            return provider.heightmap(coord).getHeight() <= 0;
        } else {
            return false;
        }
    }


    /**
     * This function does not use the cache. So safe to use when the cache is building
     * This function uses its own cache.
     */
    public static int getCityLevel(ChunkCoord key, PlanningContext provider) {
        // Unconditional. This used to be gated on provider.getWorld() != null, "In LC preview we
        // don't want to use the cache as the config isn't loaded yet" - a guard from when the
        // preview shared the dimension's caches. It has held its own DimensionCaches, built from
        // its own seed and dropped with it, since #125; and the value cached here is a pure function
        // of the seed and the preset, both of which are fixed for one preview.
        Integer cached = provider.caches().cityLevel.get(key);
        if (cached != null) {
            return cached;
        }
        int result;
        if (provider.preset().isFloating()) {
            result = getCityLevelFloating(key, provider);
        } else if (provider.preset().isCavern()) {
            result =  getCityLevelCavern(key, provider);
        } else {
            result = getCityLevelNormal(key, provider, provider.preset());
        }
        Integer raced = provider.caches().cityLevel.putIfAbsent(key, result);
        if (raced != null) {
            return raced;
        }
        return result;
    }

    public static int getCityLevelGui(ChunkCoord key, PlanningContext provider) {
        int result;
        if (provider.preset().isFloating()) {
            result = getCityLevelFloating(key, provider);
        } else if (provider.preset().isCavern()) {
            result =  getCityLevelCavern(key, provider);
        } else {
            result = getCityLevelNormal(key, provider, provider.preset());
        }
        return result;
    }

    private static int getCityLevelCavern(ChunkCoord coord, PlanningContext provider) {
        // @todo for now
        return getCityLevelFloating(coord, provider);
    }


    private static int getCityLevelNormal(ChunkCoord coord, PlanningContext provider, Preset profile) {
        ChunkHeightmap heightmap = provider.heightmap(coord);
        int height = heightmap.getHeight();
        if (profile.useAvgHeightmap() && Config.heightSampleSize() > 2) {
            int sampleSize = Config.heightSampleSize();
            int constX = coord.chunkX() < 0 ? -1 : 1;
            int constZ = coord.chunkZ() < 0 ? -1 : 1;
            int chunkBaseX =  (coord.chunkX() / sampleSize) * sampleSize + (sampleSize / 2 * constX);
            int chunkBaseZ =  (coord.chunkZ() / sampleSize) * sampleSize + (sampleSize / 2 * constZ);
            int chunkLeft = ((coord.chunkX() / sampleSize) - 1) * sampleSize + (sampleSize / 2 * constX);
            int chunkRight = ((coord.chunkX() / sampleSize) + 1) * sampleSize + (sampleSize / 2 * constX);
            int chunkUp = ((coord.chunkZ() / sampleSize) - 1) * sampleSize + (sampleSize / 2 * constZ);
            int chunkDown = ((coord.chunkZ() / sampleSize) + 1) * sampleSize + (sampleSize / 2 * constZ);
            ChunkCoord left = new ChunkCoord(provider.dimension(), chunkLeft, chunkBaseZ);
            ChunkCoord right = new ChunkCoord(provider.dimension(), chunkRight, chunkBaseZ);
            ChunkCoord up = new ChunkCoord(provider.dimension(), chunkBaseX, chunkUp);
            ChunkCoord down = new ChunkCoord(provider.dimension(), chunkBaseX, chunkDown);
            int avgHeightmap = height;
            int counter = 1;
            if (isCityRaw(left, provider, profile)) {
                avgHeightmap += provider.heightmap(left).getHeight();
                counter++;
            }
            if (isCityRaw(right, provider, profile)) {
                avgHeightmap += provider.heightmap(right).getHeight();
                counter++;
            }
            if (isCityRaw(up, provider, profile)) {
                avgHeightmap += provider.heightmap(up).getHeight();
                counter++;
            }
            if (isCityRaw(down, provider, profile)) {
                avgHeightmap += provider.heightmap(down).getHeight();
                counter++;
            }
            avgHeightmap /= counter;
            return getLevelBasedOnHeight(avgHeightmap, profile);
        }
        return getLevelBasedOnHeight(height, profile);
    }

    private static int getCityLevelFloating(ChunkCoord coord, PlanningContext provider) {
        int h = provider.heightmap(coord).getHeight();
        return getLevelBasedOnHeight(h, provider.preset());
    }

    private static int getLevelBasedOnHeight(int height, Preset profile) {
        if (height < profile.cityLevel0Height()) {
            return 0;
        } else if (height < profile.cityLevel1Height()) {
            return 1;
        } else if (height < profile.cityLevel2Height()) {
            return 2;
        } else if (height < profile.cityLevel3Height()) {
            return 3;
        } else if (height < profile.cityLevel4Height()) {
            return 4;
        } else if (height < profile.cityLevel5Height()) {
            return 5;
        } else if (height < profile.cityLevel6Height()) {
            return 6;
        } else if (height < profile.cityLevel7Height()) {
            return 7;
        } else {
            return 8;
        }
    }

    private Block getRandomDoor(RandomSource rand) {
        return switch (rand.nextInt(7)) {
            case 0 -> Blocks.BIRCH_DOOR;
            case 1 -> Blocks.ACACIA_DOOR;
            case 2 -> Blocks.DARK_OAK_DOOR;
            case 3 -> Blocks.SPRUCE_DOOR;
            case 4 -> Blocks.OAK_DOOR;
            case 5 -> Blocks.JUNGLE_DOOR;
            case 6 -> Blocks.IRON_DOOR;
            default -> Blocks.OAK_DOOR;
        };
    }

    /**
     * The road this chunk renders, or {@link RoadType#NONE}. Settled in the constructor, from the
     * same {@link #effectiveRoadType} the content decision used, so the two can never disagree.
     */
    public RoadType getEffectiveRoadType() {
        return effectiveRoad;
    }

    public boolean isPrimaryRoad() {
        return effectiveRoad == RoadType.PRIMARY;
    }

    /** A chunk that renders a planned road of some class, rather than a lot, a park or a building. */
    private boolean isPlannedRoadSection() {
        return isCity && !hasBuilding && effectiveRoad != RoadType.NONE;
    }

    /** A planned road below primary: the only roads allowed to slope. */
    private boolean isMinorRoadSection() {
        return isPlannedRoadSection() && effectiveRoad != RoadType.PRIMARY;
    }

    public boolean isStreetOrParkSection() {
        return isCity && !hasBuilding;
    }

    /**
     * The higher edge of a full-chunk street slope, or {@code null} when this chunk keeps its
     * ordinary flat street part.
     *
     * <p>Slopes are deliberately narrow in scope. Only a minor road can slope - a primary carries a
     * centre line and a width that the single stair asset does not reproduce - and only where the
     * route through the chunk is unambiguous: exactly one neighbour one city level up, the same
     * minor road continuing straight behind the transition and straight on past it, and no
     * same-level branch off either end. Everything else stays flat, so bends and junctions never
     * turn into a ramp nobody can read.
     */
    @Nullable
    public Direction getStreetSlopeDirection() {
        if (streetSlopeCalculated) {
            return streetSlopeDirection;
        }
        Direction direction = computeStreetSlopeDirection();
        // Value first, then the flag: a reader that sees the flag set must see the value.
        streetSlopeDirection = direction;
        streetSlopeCalculated = true;
        return direction;
    }

    @Nullable
    private Direction computeStreetSlopeDirection() {
        if (!isMinorRoadSection()) {
            return null;
        }
        Direction slopeDirection = null;
        for (Direction direction : Direction.VALUES) {
            ChunkPlan adjacent = direction.get(this);
            if (adjacent.isMinorRoadSection() && adjacent.cityLevel == cityLevel + 1) {
                if (slopeDirection != null) {
                    // Two ways up out of one chunk: which one the ramp should face is not decided.
                    return null;
                }
                slopeDirection = direction;
            }
        }
        if (slopeDirection == null) {
            return null;
        }

        ChunkPlan upper = slopeDirection.get(this);
        ChunkPlan approach = slopeDirection.getOpposite().get(this);
        if (!approach.isMinorRoadSection() || approach.cityLevel != cityLevel) {
            return null;
        }
        ChunkPlan departure = slopeDirection.get(upper);
        if (!departure.isMinorRoadSection() || departure.cityLevel != upper.cityLevel) {
            return null;
        }

        for (Direction direction : Direction.VALUES) {
            if (direction != slopeDirection && direction != slopeDirection.getOpposite()) {
                ChunkPlan side = direction.get(this);
                if (side.isPlannedRoadSection() && side.cityLevel == cityLevel) {
                    return null;
                }
                ChunkPlan upperSide = direction.get(upper);
                if (upperSide.isPlannedRoadSection() && upperSide.cityLevel == upper.cityLevel) {
                    return null;
                }
            }
        }
        return slopeDirection;
    }

    public boolean isElevatedParkSection() {
        if (!isStreetOrParkSection() || (streetType != StreetType.PARK)) {
            return false;
        }
        int threshold = getCityStyle().getParkStreetThreshold() != null ? getCityStyle().getParkStreetThreshold() : profile.parkStreetThreshold();
        int counter = 0;
        counter += getXmin().isStreetOrParkSection() ? 1 : 0;
        counter += getXmax().isStreetOrParkSection() ? 1 : 0;
        counter += getZmin().isStreetOrParkSection() ? 1 : 0;
        counter += getZmax().isStreetOrParkSection() ? 1 : 0;
        counter += getXmin().getZmin().isStreetOrParkSection() ? 1 : 0;
        counter += getXmin().getZmax().isStreetOrParkSection() ? 1 : 0;
        counter += getXmax().getZmin().isStreetOrParkSection() ? 1 : 0;
        counter += getXmax().getZmax().isStreetOrParkSection() ? 1 : 0;
        return counter >= threshold;
    }

    private Direction getStairDirection() {
        if (stairsCalculated) {
            return stairDirection;
        }
        Direction direction = null;
        // A sloped chunk already carries the whole level change across its full width. The narrow
        // stair decoration on top of it would be a second, contradictory way up.
        if (getStreetSlopeDirection() == null && streetType != StreetType.PARK && !hasBuilding && isCity) {
            if (cityLevel == getXmin().cityLevel - 1 && !getXmin().hasBuilding && getXmin().isCity) {
                direction = Direction.XMIN;
            } else if (cityLevel == getXmax().cityLevel - 1 && !getXmax().hasBuilding && getXmax().isCity) {
                direction = Direction.XMAX;
            } else if (cityLevel == getZmin().cityLevel - 1 && !getZmin().hasBuilding && getZmin().isCity) {
                direction = Direction.ZMIN;
            } else if (cityLevel == getZmax().cityLevel - 1 && !getZmax().hasBuilding && getZmax().isCity) {
                direction = Direction.ZMAX;
            }
        }
        // Value first, then the flag: a reader that sees the flag set must see the value.
        stairDirection = direction;
        stairsCalculated = true;
        return direction;
    }

    // This returns the actual stair direction. It keeps track if there are stair chunks around
    // it those have higher stair priority
    public Direction getActualStairDirection() {
        if (actualStairsCalculated) {
            return actualStairDirection;
        }
        Direction direction = getStairDirection();
        if (direction != null) {
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    if (cx != 0 || cz != 0) {
                        ChunkCoord key = coord.offset(cx, cz);
                        ChunkPlan adjacent = getChunkPlan(key, provider);
                        if (adjacent.getStairDirection() != null && adjacent.stairPriority > stairPriority) {
                            direction = null;
                            break;
                        }
                    }
                }
            }
        }
        actualStairDirection = direction;
        actualStairsCalculated = true;
        return direction;
    }


    /**
     * The planned primary bridge claiming this chunk, or {@code null}. Memoized because the border
     * pass asks for it once per edge column, and because the ordinary bridge scan below must agree
     * with the deck that actually renders.
     */
    @Nullable
    public PrimaryBridgePlanner.BridgeSpan getPlannedBridge() {
        if (plannedBridgeCalculated) {
            return plannedBridge;
        }
        PrimaryBridgePlanner.BridgeSpan result = PrimaryBridgePlanner.spanAt(coord, provider).orElse(null);
        // Value first, then the flag.
        plannedBridge = result;
        plannedBridgeCalculated = true;
        return result;
    }

    public BuildingPart hasBridge(PlanningContext provider, Orientation orientation) {
        return switch (orientation) {
            case X -> hasXBridge(provider);
            case Z -> hasZBridge(provider);
        };
    }

    public boolean hasBridge(PlanningContext provider) {
        if (hasXBridge(provider) != null) {
            return true;
        }
        if (hasZBridge(provider) != null) {
            return true;
        }
        return false;
    }

    // To prevent adjacent bridges of the same direction we give the bridges at even chunk Z coordinates higher priority
    public BuildingPart hasXBridge(PlanningContext provider) {
        if (xBridgeTypeCalculated) {
            return xBridgeType;
        }
        BuildingPart result = computeXBridge(provider);
        // Value first, then the flag. The old code set the flag up front and filled the value in
        // afterwards, which is fine under a lock and a lie without one.
        xBridgeType = result;
        xBridgeTypeCalculated = true;
        return result;
    }

    private BuildingPart computeXBridge(PlanningContext provider) {
        PrimaryBridgePlanner.BridgeSpan planned = getPlannedBridge();
        if (planned != null) {
            // A planned span settles this chunk for both orientations. Falling through to the
            // opportunistic scan below when the span runs the other way would let an ordinary bridge
            // claim the chunk first and cancel the planned one.
            return planned.orientation() == Orientation.X
                    ? PrimaryBridgePlanner.deckPart(planned, coord, provider) : null;
        }
        if (!xBridge) {
            return null;
        }
        if (!isSuitableForBridge(provider, this)) {
            return null;
        }
        if (coord.chunkZ() % 2 != 0 && (getZmin().hasXBridge(provider) != null || getZmax().hasXBridge(provider) != null)) {
            return null;
        }
        BuildingPart bt = bridgeType;
        ChunkPlan i = getXmin();
        while ((!i.isCity) && i.xBridge && isSuitableForBridge(provider, i)) {
            if (coord.chunkZ() % 2 != 0 && (i.getZmin().hasXBridge(provider) != null || i.getZmax().hasXBridge(provider) != null)) {
                return null;
            }
            bt = i.bridgeType;
            i = i.getXmin();
        }
        if ((!i.isCity) || i.hasBuilding || i.cityLevel > 0) {  // @todo support bridges at higher levels?
            return null;
        }

        ChunkPlan minimum = i;

        i = getXmax();
        while ((!i.isCity) && i.xBridge && isSuitableForBridge(provider, i)) {
            if (coord.chunkZ() % 2 != 0 && (i.getZmin().hasXBridge(provider) != null || i.getZmax().hasXBridge(provider) != null)) {
                return null;
            }
            i = i.getXmax();
        }
        if ((!i.isCity) || i.hasBuilding || i.cityLevel > 0) {
            return null;
        }
        // Here we can automatically mark the rest of the bridge as ok. Saves on calculation
        i = i.getXmin();
        ChunkCoord minCoord = minimum.coord;
        while (!i.coord.equals(minCoord)) {
            i.xBridgeType = bt;
            i.xBridgeTypeCalculated = true;
            i.zBridgeType = null;
            i.zBridgeTypeCalculated = true;
            i = i.getXmin();
        }

        return bt;
    }

    // To prevent adjacent bridges of the same direction we give the bridges at even chunk X coordinates higher priority
    public BuildingPart hasZBridge(PlanningContext provider) {
        if (zBridgeTypeCalculated) {
            return zBridgeType;
        }
        BuildingPart result = computeZBridge(provider);
        zBridgeType = result;
        zBridgeTypeCalculated = true;
        return result;
    }

    private BuildingPart computeZBridge(PlanningContext provider) {
        PrimaryBridgePlanner.BridgeSpan planned = getPlannedBridge();
        if (planned != null) {
            return planned.orientation() == Orientation.Z
                    ? PrimaryBridgePlanner.deckPart(planned, coord, provider) : null;
        }
        if (!zBridge) {
            return null;
        }
        if (!isSuitableForBridge(provider, this)) {
            return null;
        }
        if (hasXBridge(provider) != null) {
            return null;
        }

        if (coord.chunkX() % 2 != 0 && (getXmin().hasZBridge(provider) != null || getXmax().hasZBridge(provider) != null)) {
            return null;
        }

        BuildingPart bt = bridgeType;
        ChunkPlan i = getZmin();
        while ((!i.isCity) && i.zBridge && isSuitableForBridge(provider, i)) {
            if (i.hasXBridge(provider) != null) {
                return null;
            }
            if (coord.chunkX() % 2 != 0 && (i.getXmin().hasZBridge(provider) != null || i.getXmax().hasZBridge(provider) != null)) {
                return null;
            }

            bt = i.bridgeType;
            i = i.getZmin();
        }

        ChunkPlan minimum = i;

        if ((!i.isCity) || i.hasBuilding || i.cityLevel > 0) {
            return null;
        }
        i = getZmax();
        while ((!i.isCity) && i.zBridge && isSuitableForBridge(provider, i)) {
            if (i.hasXBridge(provider) != null) {
                return null;
            }
            if (coord.chunkX() % 2 != 0 && (i.getXmin().hasZBridge(provider) != null || i.getXmax().hasZBridge(provider) != null)) {
                return null;
            }
            i = i.getZmax();
        }
        if ((!i.isCity) || i.hasBuilding || i.cityLevel > 0) {
            return null;
        }
        // Here we can automatically mark the rest of the bridge as ok. Saves on calculation
        i = i.getZmin();
        ChunkCoord minCoord = minimum.coord;
        while (!i.coord.equals(minCoord)) {
            i.zBridgeType = bt;
            i.zBridgeTypeCalculated = true;
            i.xBridgeType = null;
            i.xBridgeTypeCalculated = true;
            i = i.getZmin();
        }

        return bt;
    }

    public boolean isOcean() {
        if (isOcean != null) {
            return isOcean;
        }
        Holder<Biome> mainBiome = BiomeInfo.getBiomeInfo(provider, coord).getMainBiome();
        isOcean = mainBiome.is(BiomeTags.IS_OCEAN) || mainBiome.is(BiomeTags.IS_DEEP_OCEAN);
        return isOcean;
    }


    private boolean isSuitableForBridge(PlanningContext provider, ChunkPlan i) {
        if (i.getPlannedBridge() != null) {
            // A planned span owns this chunk. An opportunistic bridge must not run through it -
            // it would stamp its own part over the planned deck on its way past.
            return false;
        }
        return i.cityLevel < cityLevel || CityGenerator.isWaterBiome(provider, i.coord);
    }


    public boolean hasXCorridor() {
        if (!xRailCorridor) {
            return false;
        }
        ChunkPlan i = getXmin();
        while (i.canRailGoThrough() && i.xRailCorridor) {
            i = i.getXmin();
        }
        if ((!i.hasBuilding) || i.cellars == 0) {
            return false;
        }
        i = getXmax();
        while (i.canRailGoThrough() && i.xRailCorridor) {
            i = i.getXmax();
        }
        return !((!i.hasBuilding) || i.cellars == 0);
    }

    public boolean hasZCorridor() {
        if (!zRailCorridor) {
            return false;
        }
        ChunkPlan i = getZmin();
        while (i.canRailGoThrough() && i.zRailCorridor) {
            i = i.getZmin();
        }
        if ((!i.hasBuilding) || i.cellars == 0) {
            return false;
        }
        i = getZmax();
        while (i.canRailGoThrough() && i.zRailCorridor) {
            i = i.getZmax();
        }
        return !((!i.hasBuilding) || i.cellars == 0);
    }

    // Return true if it is possible for a rail section to go through here
    public boolean canRailGoThrough() {
        if (!isCity) {
            // There is no city here so no passing possible
            return false;
        }
        if (!hasBuilding) {
            // There is no building here but we have a city so we can pass
            return true;
        }
        // Otherwise we can only pass if this building has no floors below ground
        return cellars == 0;
    }

    // Return true if it is possible for a water corridor to go through here
    public boolean canWaterCorridorGoThrough() {
        if (!isCity) {
            // There is no city here so no passing possible
            return false;
        }
        if (!hasBuilding) {
            // There is no building here but we have a city so we can pass
            return true;
        }
        // Otherwise we can only pass if this building has at most one floor below ground
        return cellars <= 1;
    }

    // Return true if the road from a neighbouring chunk can extend into this chunk
    public boolean doesRoadExtendTo() {
        boolean b = isCity && !hasBuilding;
        if (b) {
            return !isElevatedParkSection();
        }
        return false;
    }

    // Return true if there can be a road connection between the two given chunks
    public static boolean hasRoadConnection(ChunkPlan i1, ChunkPlan i2) {
        if (!i1.doesRoadExtendTo()) {
            return false;
        }
        if (!i2.doesRoadExtendTo()) {
            return false;
        }
        if (i1.cityLevel == i2.cityLevel) {
            return true;
        }
        // A one-level difference only connects where a slope actually bridges it. Reading the slope
        // rather than merely allowing a difference of one is what keeps the upper road drawing
        // through to its edge exactly over the ramp, and ending in a kerb everywhere else.
        Direction slope1 = i1.getStreetSlopeDirection();
        if (slope1 != null && slope1.get(i1).coord.equals(i2.coord)) {
            return true;
        }
        Direction slope2 = i2.getStreetSlopeDirection();
        return slope2 != null && slope2.get(i2).coord.equals(i1.coord);
    }

    /**
     * A stream for one of the per-chunk building decisions.
     * <p>
     * The purpose is the caller's because three independent decisions are made at this one
     * coordinate - whether a building is here at all, which parts its floors use, and whether a
     * lonely neighbour suppresses it - and each of them reads draw 1. Sharing a purpose made the
     * building chance and the loneliness roll literally the same number.
     */
    public static RandomSource getBuildingRandom(int chunkX, int chunkZ, long seed, Rng.Purpose purpose) {
        return Rng.at(seed, chunkX, chunkZ, purpose);
    }

    // Convert a local building level to a global one (where cityLevel == 0)
    public int localToGlobal(int l) {
        return l + cityLevel;
    }

    public int globalToLocal(int l) {
        return l - cityLevel;
    }

    public boolean hasConnectionAt(int level, Orientation orientation) {
        return switch (orientation) {
            case X -> hasConnectionAtX(level);
            case Z -> hasConnectionAtZ(level);
        };
    }

    // Call this from the street reference with the (potential building) as 'adj'
    // 'streetLevel' is the cityLevel at the position of the street
    public boolean hasFrontPartFrom(ChunkPlan adj) {
        ChunkPlan.StreetType st = streetType;
        boolean elevated = isElevatedParkSection();
        if (elevated) {
            st = ChunkPlan.StreetType.PARK;
        }

        if (adj.hasBuilding && adj.frontType != null && st == ChunkPlan.StreetType.NORMAL && cityLevel < adj.cityLevel + adj.getNumFloors()) {
            RailChunkType type = getRailInfo().getType();
            if (type == RailChunkType.STATION_UNDERGROUND) {
                return false;
            }
            if (type == RailChunkType.GOING_DOWN_ONE_FROM_SURFACE) {
                return false;
            }
            if (getMaxHighwayLevel() >= 0) {
                return false;
            }

            int local = adj.globalToLocal(cityLevel);
            if (adj.isValidFloor(local) && adj.getFloor(local).getMetaBoolean(BuildingPart.META_DONTCONNECT)) {
                return false;
            }
        } else {
            return false;
        }
        return true;
    }


    // This checks if there can be a connection at minX
    public boolean hasConnectionAtX(int level) {
        if (!isCity) {
            return false;
        }
        if (multiBuildingPos.isRightSide()) {
            return false;
        }
        if (level < 0 || level >= connectionAtX.length) {
            return false;
        }
        if (level < floorTypes.length && floorTypes[level].getMetaBoolean(BuildingPart.META_DONTCONNECT)) {
            return false;       // No connection supported
        }
        if (getXmin().hasFrontPartFrom(this)) {
            return true;
        }
        return connectionAtX[level];
    }

    // This checks if there can be a connection at minX
    public boolean hasConnectionAtXFromStreet(int level) {
        if (!isCity) {
            return false;
        }
        if (multiBuildingPos.isRightSide()) {
            return false;
        }
        if (level < 0 || level >= connectionAtX.length) {
            return false;
        }
        if (hasFrontPartFrom(getXmin())) {
            return true;
        }
        return connectionAtX[level];
    }

    // This checks if there can be a connection at minZ
    public boolean hasConnectionAtZ(int level) {
        if (!isCity) {
            return false;
        }
        if (multiBuildingPos.isBottomSide()) {
            return false;
        }
        if (level < 0 || level >= connectionAtZ.length) {
            return false;
        }
        if (level < floorTypes.length && floorTypes[level].getMetaBoolean(BuildingPart.META_DONTCONNECT)) {
            return false;       // No connection supported
        }
        if (getZmin().hasFrontPartFrom(this)) {
            return true;
        }
        return connectionAtZ[level];
    }

    // This checks if there can be a connection at minZ
    public boolean hasConnectionAtZFromStreet(int level) {
        if (!isCity) {
            return false;
        }
        if (multiBuildingPos.isBottomSide()) {
            return false;
        }
        if (level < 0 || level >= connectionAtZ.length) {
            return false;
        }
        if (hasFrontPartFrom(getZmin())) {
            return true;
        }
        return connectionAtZ[level];
    }

    /**
     * Calculate the bottom height of a building chunk.
     * Return Integer.MIN_VALUE if the building is degenerate (no floors, no cellars).
     */
    public int getBuildingBottomHeight() {
        int min = provider.shape().minY() + 2;
        int max = provider.shape().maxY() - 1 - FLOORHEIGHT;

        // Locals. This used to decrement the published cellars and floors as it walked, so the answer
        // depended on how many times it had been asked: the first call shrank the building and every
        // later one measured the shrunken version, and a TimedCache eviction reset the count by
        // rebuilding the object. It is the "building queries such as bottom-height calculation mutate
        // floors/cellars during consumption" defect in issue #126, and the only reason the fields it
        // touched could not be final.
        int remainingCellars = cellars;
        int lowestLevel = getCityGroundLevel() - remainingCellars * FLOORHEIGHT;

        // Fix lowest level so it goes above minimum build height
        while (lowestLevel <= min) {
            lowestLevel += FLOORHEIGHT;
            remainingCellars--;
            if (remainingCellars < 0) {
                return Integer.MIN_VALUE;     // Bail out, this is a degenerate case
            }
        }

        // Contributes nothing but the degenerate bail-out: the height returned is the cellar walk's.
        int remainingFloors = floors;
        while (getCityGroundLevel() + remainingFloors * FLOORHEIGHT >= max) {
            remainingFloors--;
            if (remainingFloors < 0) {
                return Integer.MIN_VALUE;     // Bail out, this is a degenerate case
            }
        }
        return lowestLevel;
    }

    /**
     * Return the building part at a given y value. Return null if there is no building part at that level
     */
    public BuildingPart getFloorAtY(int lowestLevel, int y) {
        if (y < lowestLevel || y >= lowestLevel + (floors + cellars + 1) * FLOORHEIGHT) {
            return null;    // No building part at this level
        }
        int localY = (y - lowestLevel) / FLOORHEIGHT;
        if (localY < 0 || localY >= floorTypes.length) {
            return null;    // No building part at this level
        }
        return floorTypes[localY];
    }

    /**
     * Get the lowest height of a corner of four chunks (if it is a city chunk).
     * info: reference to the bottom-right chunk. The 0,0 position of this chunk is the reference.
     * Returns 100000 if the corner is not adjacent to any city chunk
     * Also returns 100000 if all corners are city or landscape chunks (as
     * this kind of corner should also have no effect on the landscape beyond those chunks)
     * This is the level 0 version which looks at current chunk corner only
     */
    public int getLowestCityHeightAtChunkCorner() {
        ChunkPlan info00 = getXmin().getZmin();
        ChunkPlan info01 = getXmin();
        ChunkPlan info10 = getZmin();
        if (isCity && info10.isCity && info00.isCity && info01.isCity) {
            return 100000;
        }
        if (!isCity && !info10.isCity && !info00.isCity && !info01.isCity) {
            return 100000;
        }
        // If we come here we have a mix of city and normal chunks
        int h = getCityHeightForChunk();
        h = Math.min(h, info01.getCityHeightForChunk());
        h = Math.min(h, info10.getCityHeightForChunk());
        h = Math.min(h, info00.getCityHeightForChunk());
        return h;
    }

    /*
     * This is used for correcting the terrain and indicates the desired
     * level to which adjacent terrains should interpolate
     */
    public int getCityHeightForChunk() {
        if (isCity) {
            return getCityGroundLevel();
        } else {
            if (isOcean()) {
                return groundLevel - profile.oceanCorrectionBorder();
            } else {
                return 100000;
            }
        }
    }

    /**
     * Given adjacent (city) chunks, calculate the desired height to interpolate the
     * landscape to (minimum/maximum). This is calculated for the reference position of this chunk (0,0 point)
     * This is the level 1 version which looks at adjacent heights only
     */
    private MinMax getDesiredMaxHeightL1() {
        if (desiredMaxHeight1 == null) {
            int h = getLowestCityHeightAtChunkCorner();

            int cx = coord.chunkX();
            int cz = coord.chunkZ();

            // @todo build limit
            if (h < provider.shape().maxBuildHeight()) {
                // The L0 height at this corner is fixed so we return that
                desiredMaxHeight1 = new MinMax(
                        h + CityGenerator.getRandomizedOffset(provider.seed(), cx, cz, profile.terrainFixLowerMinOffset(), profile.terrainFixLowerMaxOffset(), Rng.Purpose.TERRAIN_FIX_LOWER),
                        h + CityGenerator.getRandomizedOffset(provider.seed(), cx, cz, profile.terrainFixUpperMinOffset(), profile.terrainFixUpperMaxOffset(), Rng.Purpose.TERRAIN_FIX_UPPER));
                return desiredMaxHeight1;
            }

            MinMax minMax = new MinMax();

            getXmin().getZmin().updateMinMaxL1(minMax, 25 + CityGenerator.getHeightOffsetL1(provider.seed(), cx - 1, cz - 1));
            getXmin().updateMinMaxL1(minMax, 20 + CityGenerator.getHeightOffsetL1(provider.seed(), cx - 1, cz));
            getXmin().getZmax().updateMinMaxL1(minMax, 25 + CityGenerator.getHeightOffsetL1(provider.seed(), cx - 1, cz + 1));

            getZmin().updateMinMaxL1(minMax, 20 + CityGenerator.getHeightOffsetL1(provider.seed(), cx, cz - 1));
            getZmax().updateMinMaxL1(minMax, 20 + CityGenerator.getHeightOffsetL1(provider.seed(), cx, cz + 1));

            getXmax().getZmin().updateMinMaxL1(minMax, 25 + CityGenerator.getHeightOffsetL1(provider.seed(), cx + 1, cz - 1));
            getXmax().updateMinMaxL1(minMax, 20 + CityGenerator.getHeightOffsetL1(provider.seed(), cx + 1, cz));
            getXmax().getZmax().updateMinMaxL1(minMax, 25 + CityGenerator.getHeightOffsetL1(provider.seed(), cx + 1, cz + 1));

            desiredMaxHeight1 = minMax;
        }
        return desiredMaxHeight1;
    }

    public static class MinMax {
        public int min;
        public int max;

        public MinMax(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public MinMax(MinMax mm) {
            min = mm.min;
            max = mm.max;
        }

        public MinMax() {
            min = max = 100000;
        }
    }

    /**
     * Given adjacent (city) chunks, calculate the desired height to interpolate the
     * landscape too. This is calculated for the reference position of this chunk (0,0 point)
     * This is the level 2 version which looks at L1 heights of adjacent chunks
     */
    public MinMax getDesiredMaxHeightL2() {
        if (desiredTerrainCorrectionHeights == null) {
            MinMax mm = getDesiredMaxHeightL1();
            // @todo build limit
            if (mm.min < provider.shape().maxBuildHeight()) {
                // The L1 height at this corner is fixed so we return that
                desiredTerrainCorrectionHeights = new MinMax(mm);
                return desiredTerrainCorrectionHeights;
            }

            int cx = coord.chunkX();
            int cz = coord.chunkZ();

            MinMax minMax = new MinMax();

            getXmin().getZmin().updateMinMaxL2(minMax, 25 + CityGenerator.getHeightOffsetL2(provider.seed(), cx - 1, cz - 1));
            getXmin().updateMinMaxL2(minMax, 20 + CityGenerator.getHeightOffsetL2(provider.seed(), cx - 1, cz));
            getXmin().getZmax().updateMinMaxL2(minMax, 25 + CityGenerator.getHeightOffsetL2(provider.seed(), cx - 1, cz + 1));

            getZmin().updateMinMaxL2(minMax, 20 + CityGenerator.getHeightOffsetL2(provider.seed(), cx, cz - 1));
            getZmax().updateMinMaxL2(minMax, 20 + CityGenerator.getHeightOffsetL2(provider.seed(), cx, cz + 1));

            getXmax().getZmin().updateMinMaxL2(minMax, 25 + CityGenerator.getHeightOffsetL2(provider.seed(), cx + 1, cz - 1));
            getXmax().updateMinMaxL2(minMax, 20 + CityGenerator.getHeightOffsetL2(provider.seed(), cx + 1, cz));
            getXmax().getZmax().updateMinMaxL2(minMax, 25 + CityGenerator.getHeightOffsetL2(provider.seed(), cx + 1, cz + 1));
            desiredTerrainCorrectionHeights = minMax;
        }
        return desiredTerrainCorrectionHeights;
    }

    public void updateMinMaxL2(MinMax minMax, int offs) {
        MinMax h = getDesiredMaxHeightL1();
        if ((h.min - offs) < minMax.min) {
            minMax.min = h.min - offs;
        }
        if ((h.max + offs) < minMax.max) {
            minMax.max = h.max + offs;
        }
    }


    private void updateMinMaxL1(MinMax minMax, int offs) {
        int h = getLowestCityHeightAtChunkCorner();
        if ((h - offs) < minMax.min) {
            minMax.min = h - offs;
        }
        if ((h + offs) < minMax.max) {
            minMax.max = h + offs;
        }
    }


    /**
     * How a chunk with no building renders: a planned road is {@link #NORMAL} paving, an open lot is
     * the {@link #PARK} grass surface. Nothing else decides between them - in particular the open-lot
     * park chance does not, it only furnishes a lot that is already grass. There is no third surface:
     * the old {@code FULL} type was reachable only from a coin flip the road field replaced.
     */
    public enum StreetType {
        NORMAL,
        PARK
    }


    public boolean isCity() {
        return this.isCity;
    }

    /**
     * Feeds only {@link ConditionTodo}'s "building" field, which an {@code inbuilding} condition is
     * matched against - so this is the fully-qualified id, the same string a condition file writes.
     */
    public String getBuildingType() {
        return hasBuilding ? buildingType.getName() : null;
    }

    public int getCityLevel() {
        return cityLevel;
    }

    public int getNumFloors() {
        return floors;
    }

    public int getNumCellars() {
        return cellars;
    }

    public float getDamage(int chunkY) {
        return getDamageArea().getDamage((coord.chunkX() << 4) + 8, (chunkY * 16) + 8, (coord.chunkZ() << 4) + 8);
    }

    public Collection<Explosion> getExplosions() {
        return new ArrayList<>(getDamageArea().getExplosions());
    }

    public int getMaxHighwayLevel() {
        return Math.max(getHighwayXLevel(), getHighwayZLevel());
    }

}

package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.worldgen.gen.Terrain;
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
    final float stairPriority;      // A random number that indicates if this chunk should get a stair if there are competing stairs around it. The highest wins
    public final BuildingPart railDungeon;    // Dungeon next to rails. Will only generate if there are actually rails next to it
    public final StreetType streetType;
    private final RoadType effectiveRoad;   // The planned road this chunk renders, NONE for most chunks

    final int floors;
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
    private volatile WorldStyle chunkWorldStyle = null;

    /** This chunk's bridge decisions and the state memoising them; see {@link BridgeDecisions}. */
    final BridgeDecisions bridges = new BridgeDecisions(this);

    /** Which way this chunk's street slopes and where its stairs face; see {@link SlopeDecisions}. */
    final SlopeDecisions slopes = new SlopeDecisions(this);

    /** How high this chunk's city sits and what terrain correction it asks for; see {@link HeightDecisions}. */
    final HeightDecisions heights = new HeightDecisions(this);
    /** What this chunk joins on to; see {@link ConnectionGraph}. */
    final ConnectionGraph graph = new ConnectionGraph(this);

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
        //
        // Both steps go through the world's PaletteCache now. This field stays as the per-chunk
        // memo in front of it - a field read beats two map lookups on a path every part of every
        // building takes - but a chunk that is the first to want this combination no longer
        // deep-copies three maps to get it (issue #53).
        PaletteCache cache = provider.caches().palettes;
        CompiledPalette built = cache.of(palette);
        if (hasBuilding) {
            built = cache.with(built, buildingType.getLocalPalette());
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

        boolean isCity = CityField.isCityRaw(key, provider, profile);
        int cityLevel = CityField.cityLevelUncached(key, provider);
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
        boolean isCity = CityField.isCityRaw(coord, provider, profile);

        MultiSection section = isCity ? multiBuildingSection(coord, provider, profile) : MultiSection.NONE;
        MultiPos multiPos = section.pos();
        MultiBuilding multiBuilding = section.building();

        int cityLevel;
        if (multiPos.isSingle()) {
            cityLevel = CityField.getCityLevel(coord, provider);
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
        if (!CityField.isCityRaw(coord, provider, profile)) {
            return RoadType.NONE;
        }
        RoadCell cell = provider.roadField().at(coord.chunkX(), coord.chunkZ());
        boolean connectedCityNeighbour = false;
        for (RoadDirection direction : RoadDirection.values()) {
            if (cell.connects(direction)) {
                ChunkCoord adjacent = coord.offset(direction.stepX(), direction.stepZ());
                if (CityField.isCityRaw(adjacent, provider, profile)) {
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
                level += CityField.getCityLevel(key, provider);
            }
        }
        return level / (mp.w() * mp.h());
    }

    private static int getTopLeftCityLevel(MultiPos mp, ChunkCoord coord, PlanningContext provider) {
        int topX = coord.chunkX() - mp.x();
        int topZ = coord.chunkZ() - mp.z();
        ChunkCoord key = new ChunkCoord(provider.dimension(), topX, topZ);
        return CityField.getCityLevel(key, provider);
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
    boolean isPlannedRoadSection() {
        return isCity && !hasBuilding && effectiveRoad != RoadType.NONE;
    }

    /** A planned road below primary: the only roads allowed to slope. */
    boolean isMinorRoadSection() {
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
        return slopes.streetSlope();
    }

    public Direction getActualStairDirection() {
        return slopes.actualStair();
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

    /**
     * The planned primary bridge claiming this chunk, or {@code null}. Memoized because the border
     * pass asks for it once per edge column, and because the ordinary bridge scan below must agree
     * with the deck that actually renders.
     */
    @Nullable
    public PrimaryBridgePlanner.BridgeSpan getPlannedBridge() {
        return bridges.planned();
    }

    public BuildingPart hasBridge(PlanningContext provider, Orientation orientation) {
        return bridges.at(provider, orientation);
    }

    public boolean hasBridge(PlanningContext provider) {
        return bridges.any(provider);
    }

    public BuildingPart hasXBridge(PlanningContext provider) {
        return bridges.x(provider);
    }

    public BuildingPart hasZBridge(PlanningContext provider) {
        return bridges.z(provider);
    }

    public boolean isOcean() {
        return bridges.isOcean();
    }

    public boolean hasXCorridor() {
        return graph.xCorridor();
    }

    public boolean hasZCorridor() {
        return graph.zCorridor();
    }

    public boolean canRailGoThrough() {
        return graph.canRailGoThrough();
    }

    public boolean canWaterCorridorGoThrough() {
        return graph.canWaterCorridorGoThrough();
    }

    public boolean doesRoadExtendTo() {
        return graph.roadExtendsOut();
    }

    public static boolean hasRoadConnection(ChunkPlan i1, ChunkPlan i2) {
        return ConnectionGraph.hasRoadConnection(i1, i2);
    }

    public static RandomSource getBuildingRandom(int chunkX, int chunkZ, long seed, Rng.Purpose purpose) {
        return Rng.at(seed, chunkX, chunkZ, purpose);
    }

    public int localToGlobal(int l) {
        return l + cityLevel;
    }

    public int globalToLocal(int l) {
        return l - cityLevel;
    }

    public boolean hasConnectionAt(int level, Orientation orientation) {
        return graph.at(level, orientation);
    }

    public boolean hasFrontPartFrom(ChunkPlan adj) {
        return graph.frontPartFrom(adj);
    }

    public boolean hasConnectionAtX(int level) {
        return graph.atX(level);
    }

    public boolean hasConnectionAtXFromStreet(int level) {
        return graph.atXFromStreet(level);
    }

    public boolean hasConnectionAtZ(int level) {
        return graph.atZ(level);
    }

    public boolean hasConnectionAtZFromStreet(int level) {
        return graph.atZFromStreet(level);
    }

    /**
     * Calculate the bottom height of a building chunk.
     * Return Integer.MIN_VALUE if the building is degenerate (no floors, no cellars).
     */
    public int getBuildingBottomHeight() {
        return heights.buildingBottom();
    }

    public BuildingPart getFloorAtY(int lowestLevel, int y) {
        return heights.floorAtY(lowestLevel, y);
    }

    public int getLowestCityHeightAtChunkCorner() {
        return heights.lowestCityHeightAtCorner();
    }

    public int getCityHeightForChunk() {
        return heights.cityHeightForChunk();
    }

    public MinMax getDesiredMaxHeightL2() {
        return heights.desiredMaxHeightL2();
    }

    public void updateMinMaxL2(MinMax minMax, int offs) {
        heights.updateMinMaxL2(minMax, offs);
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

package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.EffectiveRoad;
import dev.krona.urbex.plan.RoadCell;
import dev.krona.urbex.plan.RoadDirection;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.*;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.CityGenerator;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.gen.Terrain;
import dev.krona.urbex.worldgen.lost.cityassets.*;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.WorldSettings;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import static dev.krona.urbex.worldgen.CityGenerator.FLOORHEIGHT;

/**
 * What content a chunk is a candidate for, before anything is built.
 *
 * <p>The first pass of planning: is this chunk city, does a road or a highway or a railway run
 * through it, is it one section of a multi-chunk building, and which building would go here. The
 * answer is a {@link ChunkCandidate}, cached per coordinate, and it is deliberately upstream of
 * everything a {@link ChunkPlan} decides - a candidate may not read a building decision, its own or
 * a neighbour's, or the decision graph stops being acyclic.</p>
 *
 * <p>Split out of {@link ChunkPlan} (issue #11), where the resolution of a chunk's candidate sat
 * alongside everything that consumes it.</p>
 */
public final class ChunkCandidates {

    private ChunkCandidates() {
    }

    public static boolean hasBuildingGui(int chunkX, int chunkZ, PlanningContext provider, ChunkCandidate candidate) {
//        Random rand = ChunkPlan.getBuildingRandom(chunkX, chunkZ, provider.seed());
//        rand.nextFloat();       // Compatibility?

        return candidate.couldHaveBuilding();
    }

    public static ChunkCandidate candidateUncached(ChunkCoord key, PlanningContext provider) {
//        ChunkCandidate cached = CITY_INFO_MAP.get(key);
//        if (cached != null) {
//            return cached;
//        }
        int chunkX = key.chunkX();
        int chunkZ = key.chunkZ();
        Preset profile = provider.preset();

        boolean isCity = CityField.isCityRaw(key, provider, profile);
        int cityLevel = CityField.cityLevelUncached(key, provider);
        RandomSource rand = ChunkPlan.getBuildingRandom(chunkX, chunkZ, provider.seed(), Rng.Purpose.BUILDING);
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
    public static ChunkCandidate candidate(ChunkCoord coord, PlanningContext provider) {
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
        RandomSource rand = ChunkPlan.getBuildingRandom(chunkX, chunkZ, provider.seed(), Rng.Purpose.BUILDING);
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
        return candidate(coord, provider).isCity();
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
        return candidate(key, provider);
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
}

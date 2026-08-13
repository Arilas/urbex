package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.Hash;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.plan.grid.GridPurpose;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Owns the order in which candidate content claims a city chunk. Stated once, here, rather than
 * being implicit in {@link ChunkPlan}'s control flow.
 *
 * <h2>The order</h2>
 * {@link #couldHaveBuilding} walks a chain of full stops. The first one that matches decides, and
 * nothing after it is consulted:
 * <ol>
 *   <li>a <b>predefined building</b> at this chunk - building, no further tests;</li>
 *   <li>a <b>predefined street</b> at this chunk - no building. Note this beats a multi-building
 *       section: a predefined street wins over a multi, not the other way round;</li>
 *   <li>a chunk belonging to an <b>accepted multi-building</b> section - building. A multi is only
 *       ever accepted over a road its conflict policy tolerates, so this beating the road below is
 *       not a road being overrun; see {@link MultiChunk};</li>
 *   <li>an <b>effective planned road</b> - no building. The road field clipped to the city mask, so
 *       a road that a city does not actually contain claims nothing;</li>
 *   <li>the <b>ordinary building-chance roll</b> - no building when the roll fails;</li>
 *   <li><b>highway headroom</b> - above a highway, a building only if the city level clears it;</li>
 *   <li><b>railway headroom</b> - never above an underground station, otherwise only if the city
 *       level clears the rails;</li>
 *   <li>the <b>general case</b> - building. The chain's fallback is a building, not a street: a
 *       chunk becomes a street by failing the roll or tripping a constraint, never by falling off
 *       the end.</li>
 * </ol>
 *
 * <h2>The overrides</h2>
 * One thing is <em>not</em> a step in that chain. It is applied afterwards and overrules
 * whatever it decided:
 * <ul>
 *   <li>the <b>lonely-building veto</b> (pass two): a neighbour whose building type prefers to stand
 *       alone can take this chunk's building away.</li>
 * </ul>
 *
 * <h2>Why the order runs in two passes</h2>
 * The order cannot be evaluated in one go, and the split is not cosmetic:
 * <ul>
 *   <li>{@link #couldHaveBuilding} is the <em>candidate</em> verdict. It runs during the
 *       {@link ChunkCandidate} pass, on the {@link dev.krona.urbex.varia.Rng.Purpose#BUILDING}
 *       stream, because its answer is cached per chunk and read by the neighbours (the street
 *       city-style majority vote, and the preview).</li>
 *   <li>{@link #resolve} is the <em>final</em> verdict. It runs while a {@link ChunkPlan} is
 *       being constructed, on the {@link dev.krona.urbex.varia.Rng.Purpose#BUILDING_LAYOUT} stream,
 *       because the lonely-building veto reads the four neighbours' {@link ChunkCandidate} -
 *       so it cannot itself live in the pass those neighbours are computed by without recursing
 *       forever.</li>
 * </ul>
 *
 * <h2>Draw discipline</h2>
 * Both methods draw from an addressed {@link RandomSource} whose sequence is part of every world
 * ever generated. Two rules follow, and both are load-bearing for anyone adding a branch here:
 * <ul>
 *   <li>{@link #couldHaveBuilding} takes its single draw <em>first</em>, before any branch, so a new
 *       stop can be inserted anywhere in the chain without moving the stream.</li>
 *   <li>{@link #resolve} settles the street type from an <em>addressed</em> hash rather than from the
 *       layout stream, so it costs no draw and cannot shift one. It is still settled unconditionally -
 *       even for a chunk that ends up holding a building, whose street type is then never rendered -
 *       because a neighbour reads the value too. The one case that settles nothing is a non-top-left
 *       multi-building chunk, which inherits its rendering from the top-left chunk.</li>
 * </ul>
 */
public final class ChunkContentResolver {

    private ChunkContentResolver() {
    }

    /**
     * How strongly a neighbouring chunk's building type prefers to stand alone. Narrow on purpose:
     * the real implementation reaches into the neighbours' {@link ChunkCandidate}, and this
     * keeps {@link #resolve} a pure function of values a test can supply.
     */
    @FunctionalInterface
    public interface PrefersLonely {
        float at(ChunkCoord neighbour);
    }

    /**
     * The world facts pass one consults, each behind its own supplier.
     *
     * <p>Every one of these is lazy on purpose, and that laziness is behaviour, not style. The chain
     * short-circuits: a chunk whose building-chance roll fails never asks whether it sits on a
     * highway. Evaluating these eagerly would consult {@link Highway} and {@link Railway} for chunks
     * that never do so today - {@code Railway}'s chunk types are mutable state, and the highway
     * extent scan is expensive. It also keeps the resolver free of {@code PlanningContext}: the
     * decision is a pure function of these values, so a test can supply them directly.
     *
     * @param hasPredefinedBuilding        a predefined building starts at this chunk's top-left
     * @param hasPredefinedStreet          a predefined street occupies this chunk
     * @param cityStyle                    this chunk's style, which may override the building chance
     * @param effectiveRoad                the planned road this chunk renders, already clipped to the
     *                                     city mask. Lazy like the rest, and that matters most here:
     *                                     the road field is the most expensive of these lookups and a
     *                                     chunk claimed by a predefined building or a multi never
     *                                     needs it
     * @param hasHighway                   a highway runs through this chunk at any level
     * @param maxHighwayLevel              the higher of this chunk's two highway levels
     * @param hasRailway                   a railway runs through this chunk
     * @param railInfo                     this chunk's railway type and level
     */
    public record ChunkFacts(BooleanSupplier hasPredefinedBuilding,
                             BooleanSupplier hasPredefinedStreet,
                             Supplier<CityStyle> cityStyle,
                             Supplier<RoadType> effectiveRoad,
                             BooleanSupplier hasHighway,
                             IntSupplier maxHighwayLevel,
                             BooleanSupplier hasRailway,
                             Supplier<Railway.RailChunkInfo> railInfo) {
    }

    /**
     * Pass one: can this chunk hold a building at all? Cached in {@link ChunkCandidate} and
     * read by neighbouring chunks, so it must not depend on any neighbour's own verdict.
     *
     * <p>Note the short-circuit on {@code isCity}: a chunk outside a city takes no draw at all.
     */
    public static boolean couldHaveBuilding(Preset profile, boolean isCity, MultiPos section,
                                            int cityLevel, RandomSource rand, ChunkFacts facts) {
        return isCity && checkBuildingPossibility(profile, section, cityLevel, rand, facts);
    }

    private static boolean checkBuildingPossibility(Preset profile, MultiPos section, int cityLevel, RandomSource rand, ChunkFacts facts) {
        boolean b;
        float bc = rand.nextFloat();

        if (facts.hasPredefinedBuilding().getAsBoolean()) {
            return true;    // We don't need other tests
        }
        if (facts.hasPredefinedStreet().getAsBoolean()) {
            return false;   // No building here
        }

        CityStyle style = facts.cityStyle().get();
        float buildingChance = profile.BUILDING_CHANCE;
        if (style.getBuildingChance() != null) {
            buildingChance = style.getBuildingChance();
        }

        if (section.isMulti()) {
            // Part of multi-building. We have checked everything above
            b = true;
        } else if (facts.effectiveRoad().get() != RoadType.NONE) {
            // A planned road runs through this chunk, so nothing is built on it
            b = false;
        } else if (bc >= buildingChance) {
            // Random says we should have no building here
            b = false;
        } else if (facts.hasHighway().getAsBoolean()) {
            // We are above a highway. Check if we have room for a building
            int maxh = facts.maxHighwayLevel().getAsInt();
            b = cityLevel > maxh + 1;       // Allow a building if it is higher than the maximum highway + one
            // Later we will take care to make sure we don't have too many cellars
            // Note that for easy of coding we still disallow multi-buildings above highways
        } else if (facts.hasRailway().getAsBoolean()) {
            // We are above a railway. Check if we have room for a building
            Railway.RailChunkInfo info = facts.railInfo().get();
            if (info.getType() == RailChunkType.STATION_UNDERGROUND) {
                b = false;  // No building directly above the underground station
            } else {
                int maxh = info.getLevel();
                b = cityLevel > maxh + 1;       // Allow a building if it is higher than the maximum railway + one
                // Later we will take care to make sure we don't have too many cellars
                // Note that for easy of coding we still disallow multi-buildings above railways
            }
        } else {
            // General case
            b = true;
        }
        return b;
    }

    /**
     * Pass two: settle what actually occupies the chunk.
     *
     * @param profile               the profile active for this chunk, for the open-lot park chance
     * @param seed                  the world seed, which addresses the open-lot park roll
     * @param rand                  the chunk's {@code BUILDING_LAYOUT} stream, positioned at its start
     * @param isCity                whether this chunk is city at all
     * @param couldHaveBuilding     the pass-one verdict from {@link #couldHaveBuilding}
     * @param effectiveRoad         the planned road this chunk renders, already clipped to the city mask
     * @param section               this chunk's position in a multi-building, or {@link MultiPos#SINGLE}
     * @param coord                 this chunk, used to address the four neighbours of the lonely veto
     * @param prefersLonely         the neighbours' lonely preference, consulted lazily and in order
     * @param candidateBuildingName the building asset picked in pass one, returned only if it stands
     */
    public static ChunkContent resolve(Preset profile, long seed, RandomSource rand,
                                       boolean isCity, boolean couldHaveBuilding, RoadType effectiveRoad,
                                       MultiPos section, ChunkCoord coord,
                                       PrefersLonely prefersLonely,
                                       @Nullable String candidateBuildingName) {
        boolean b = couldHaveBuilding;
        if (b && section.isSingle()) {
            if (rand.nextFloat() < prefersLonely.at(coord.west())) {
                b = false;
            } else if (rand.nextFloat() < prefersLonely.at(coord.east())) {
                b = false;
            } else if (rand.nextFloat() < prefersLonely.at(coord.north())) {
                b = false;
            } else if (rand.nextFloat() < prefersLonely.at(coord.south())) {
                b = false;
            }
        }

        // A city chunk with no road and no building is an open lot. Note this reads the settled
        // verdict, not the pass-one candidate: a building the lonely veto took away leaves an open
        // lot behind exactly like a failed roll does.
        boolean openLot = isCity && !b && effectiveRoad == RoadType.NONE;

        // Every open lot is grass, unconditionally - that is the point of the whole road field. A lot
        // is what is left over between the planned roads, and rendering some of them as street parts
        // would scatter road fragments through the middle of city blocks, which is the artefact the
        // hierarchical roads exist to remove. OPEN_LOT_PARK_CHANCE decides only whether the lot is
        // furnished with a park part; it never reaches the surface underneath.
        //
        // The roll has its own GridPurpose key rather than sharing an address with a neighbouring
        // decision, which would make "is this lot furnished" a monotone function of that unrelated
        // decision.
        boolean parkPart = openLot
                && Hash.unit(Hash.at(seed, coord.chunkX(), coord.chunkZ(), GridPurpose.OPEN_LOT_PARK.key()))
                        < profile.OPEN_LOT_PARK_CHANCE;

        // Settled whether or not a building claimed the chunk: see the draw discipline note above.
        // A non-top-left multi-building chunk copies the top-left's street type instead.
        boolean inheritsFromTopLeft = section.isMulti() && !section.isTopLeft();
        ChunkPlan.StreetType streetType = null;
        if (!inheritsFromTopLeft) {
            streetType = openLot ? ChunkPlan.StreetType.PARK : ChunkPlan.StreetType.NORMAL;
        }

        return new ChunkContent(b, streetType, b ? candidateBuildingName : null, openLot, parkPart);
    }
}

package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.regassets.data.CitySphereSettings;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedStreet;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;

/**
 * Owns the order in which candidate content claims a city chunk. Stated once, here, rather than
 * being implicit in {@link BuildingInfo}'s control flow.
 *
 * <p>Precedence, strongest first: hard exclusions and sphere/infrastructure constraints, predefined
 * buildings and multi-buildings, predefined streets, accepted random multi-buildings, ordinary
 * single-building chance with the lonely-building veto, and finally a non-building fallback.
 *
 * <h2>Why the order runs in two passes</h2>
 * The order cannot be evaluated in one go, and the split is not cosmetic:
 * <ul>
 *   <li>{@link #couldHaveBuilding} is the <em>candidate</em> verdict. It runs during the
 *       {@link ChunkCharacteristics} pass, on the {@link dev.krona.urbex.varia.Rng.Purpose#BUILDING}
 *       stream, because its answer is cached per chunk and read by the neighbours (the street
 *       city-style majority vote, and the preview).</li>
 *   <li>{@link #resolve} is the <em>final</em> verdict. It runs while a {@link BuildingInfo} is
 *       being constructed, on the {@link dev.krona.urbex.varia.Rng.Purpose#BUILDING_LAYOUT} stream,
 *       because the lonely-building veto reads the four neighbours' candidate verdicts - so it
 *       cannot itself live in the pass those neighbours are computed by without recursing forever.
 *       </li>
 * </ul>
 *
 * <h2>Draw discipline</h2>
 * Both methods draw from an addressed {@link RandomSource} whose sequence is part of every world
 * ever generated. Two rules follow, and both are load-bearing for anyone adding a branch here:
 * <ul>
 *   <li>{@code couldHaveBuilding} takes its single draw <em>first</em>, before any branch, so a new
 *       exclusion can be inserted anywhere in the chain without moving the stream.</li>
 *   <li>{@code resolve} rolls the street type <em>unconditionally</em> - even for a chunk that ends
 *       up holding a building, whose street type is then never rendered. Skipping the roll for
 *       building chunks would shift every later draw on the layout stream. The one case that
 *       genuinely does not roll is a non-top-left multi-building chunk, which inherits its rendering
 *       from the top-left chunk and never reaches the roll today.</li>
 * </ul>
 */
public final class ChunkContentResolver {

    private ChunkContentResolver() {
    }

    /**
     * How strongly a neighbouring chunk's building type prefers to stand alone. Narrow on purpose:
     * the real implementation reaches into the neighbours' {@link ChunkCharacteristics}, and this
     * keeps {@link #resolve} a pure function of values a test can supply.
     */
    @FunctionalInterface
    public interface PrefersLonely {
        float at(ChunkCoord neighbour);
    }

    /**
     * Pass one: can this chunk hold a building at all? Cached in {@link ChunkCharacteristics} and
     * read by neighbouring chunks, so it must not depend on any neighbour's own verdict.
     */
    public static boolean couldHaveBuilding(ChunkCoord coord, IDimensionInfo provider, UrbexProfile profile,
                                            boolean isCity, MultiPos section, int cityLevel, RandomSource rand) {
        boolean couldHaveBuilding = isCity && checkBuildingPossibility(coord, provider, profile, section, cityLevel, rand);
        if ((profile.isSpace() || profile.isSpheres()) && section.isSingle()) {
            // Minimize cities at the edge of the city in an orb
            float dist = CitySphere.getRelativeDistanceToCityCenter(coord, provider);
            if (dist > .7f) {
                couldHaveBuilding = false;
            }
        }
        return couldHaveBuilding;
    }

    private static boolean checkBuildingPossibility(ChunkCoord coord, IDimensionInfo provider, UrbexProfile profile, MultiPos section, int cityLevel, RandomSource rand) {
        boolean b;
        float bc = rand.nextFloat();

        PredefinedBuilding predefinedBuilding = City.getPredefinedBuildingAtTopLeft(provider.getWorld(), coord);
        if (predefinedBuilding != null) {
            return true;    // We don't need other tests
        }
        PredefinedStreet predefinedStreet = City.getPredefinedStreet(provider.getWorld(), coord);
        if (predefinedStreet != null) {
            return false;   // No building here
        }

        CityStyle style = City.getCityStyle(coord, provider, profile);
        float buildingChance = profile.BUILDING_CHANCE;
        if (style.getBuildingChance() != null) {
            buildingChance = style.getBuildingChance();
        }

        if (section.isMulti()) {
            // Part of multi-building. We have checked everything above
            b = true;
        } else if (bc >= buildingChance) {
            // Random says we should have no building here
            b = false;
        } else if (BuildingInfo.hasHighway(coord, provider, profile)) {
            // We are above a highway. Check if we have room for a building
            int maxh = Math.max(Highway.getXHighwayLevel(coord, provider, profile), Highway.getZHighwayLevel(coord, provider, profile));
            b = cityLevel > maxh + 1;       // Allow a building if it is higher than the maximum highway + one
            // Later we will take care to make sure we don't have too many cellars
            // Note that for easy of coding we still disallow multi-buildings above highways
        } else if (BuildingInfo.hasRailway(coord, provider, profile)) {
            // We are above a railway. Check if we have room for a building
            Railway.RailChunkInfo info = Railway.getRailChunkType(coord, provider, profile);
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
     * @param profile               the profile active for this chunk, for the park chance fallback
     * @param cityStyle             this chunk's style, which may override the park chance
     * @param rand                  the chunk's {@code BUILDING_LAYOUT} stream, positioned at its start
     * @param couldHaveBuilding     the pass-one verdict from {@link #couldHaveBuilding}
     * @param section               this chunk's position in a multi-building, or {@link MultiPos#SINGLE}
     * @param coord                 this chunk, used to address the four neighbours of the lonely veto
     * @param prefersLonely         the neighbours' lonely preference, consulted lazily and in order
     * @param sphereCenterType      the city sphere centre override, or null when this chunk is not one
     * @param candidateBuildingName the building asset picked in pass one, returned only if it stands
     */
    public static ChunkContent resolve(UrbexProfile profile, CityStyle cityStyle, RandomSource rand,
                                       boolean couldHaveBuilding, MultiPos section, ChunkCoord coord,
                                       PrefersLonely prefersLonely,
                                       @Nullable CitySphereSettings.CitySphereCenterType sphereCenterType,
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

        if (sphereCenterType != null) {
            switch (sphereCenterType) {
                case DEFAULT, NORMAL -> {
                }
                case STREET -> b = false;
                case BUILDING -> b = true;
            }
        }

        // Rolled whether or not a building claimed the chunk: see the draw discipline note above.
        // A non-top-left multi-building chunk copies the top-left's street type instead of rolling,
        // so it must not touch the stream here either.
        boolean inheritsFromTopLeft = section.isMulti() && !section.isTopLeft();
        BuildingInfo.StreetType streetType = null;
        if (!inheritsFromTopLeft) {
            float parkChance = cityStyle.getParkChance() != null ? cityStyle.getParkChance() : profile.PARK_CHANCE;
            if (rand.nextDouble() < parkChance) {
                streetType = BuildingInfo.StreetType.PARK;
            } else {
                streetType = BuildingInfo.StreetType.randomNonPark(rand);
            }
        }

        return new ChunkContent(b, streetType, b ? candidateBuildingName : null, false);
    }
}

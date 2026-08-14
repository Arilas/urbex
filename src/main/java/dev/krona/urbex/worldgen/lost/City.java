package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.WorldStyleField;
import dev.krona.urbex.worldgen.lost.cityassets.*;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelection;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedStreet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

public class City {

    /**
     * Where the predefined content of the world {@code provider} generates is.
     * <p>
     * Compiled with the rest of the assets and finished before any chunk can ask (issue #129).
     * What this replaces is five {@code static} maps on this class, each filled lazily from
     * whichever worker thread arrived first, latched by its own {@code volatile boolean}, and
     * guarded on {@code provider.getWorld() != null} so a world-creation preview could not latch
     * "ready" over an empty map (issue #67) - plus a {@code cleanPredefinedCache()} three unrelated
     * call sites had to remember, one of them the preview, clearing maps live worldgen was reading.
     */
    private static PredefinedIndex predefined(PlanningContext provider) {
        return provider.assets().predefined();
    }

    public static PredefinedCity getPredefinedCity(PlanningContext provider, ChunkCoord coord) {
        return predefined(provider).cityAt(coord);
    }

    public static PredefinedBuilding getPredefinedBuildingAtTopLeft(PlanningContext provider, ChunkCoord coord) {
        return predefined(provider).buildingAt(coord);
    }

    public static PredefinedIndex.BuildingAt getPredefinedBuilding(PlanningContext provider, ChunkCoord coord) {
        return predefined(provider).buildingCovering(coord);
    }

    public static PredefinedStreet getPredefinedStreet(PlanningContext provider, ChunkCoord coord) {
        return predefined(provider).streetCovering(coord);
    }

    // Return true if a chunk is occupied (by a predefined building or street)
    public static boolean isChunkOccupied(PlanningContext provider, ChunkCoord coord) {
        return predefined(provider).isOccupied(coord);
    }

    /**
     * The predefined street declared <em>at</em> {@code coord}, or null.
     * <p>
     * Separate from {@link #getPredefinedStreet}: this one answers about the chunk the street was
     * declared on, that one about any chunk the street covers.
     */
    public static PredefinedStreet getPredefinedStreetAt(PlanningContext provider, ChunkCoord coord) {
        return predefined(provider).streetAt(coord);
    }


    /**
     * The memo value for a coordinate that is not a city centre. A radius is always a real
     * number, so this cannot collide with one - which a negative sentinel could, since a
     * predefined city carries whatever radius its datapack declares.
     */
    private static final float NOT_A_CENTRE = Float.NaN;

    /**
     * Whether this coordinate rolled a city centre and, if it did, that city's radius - memoized,
     * because {@link #getCityFactor} asks this of every chunk within {@code cityMaxRadius}.
     *
     * <p>That scan is {@code (2 * ceil(maxRadius / 16) + 1)^2} coordinates per chunk planned:
     * 1089 of them at the shipped {@code onlycities} radius of 256. Both rolls are pure functions
     * of the seed and the coordinate - neither depends on which chunk is doing the asking - so
     * every chunk was re-rolling almost exactly the window its neighbour had just rolled, and
     * paying two {@link Rng#at} allocations per coordinate to do it. Against a 625-chunk
     * {@code onlycities} window that was 0.9 GiB, the largest remaining allocation site in
     * generation after the shape-update fix.</p>
     *
     * <p>One entry covers both rolls because nothing ever wants the radius of a coordinate that
     * is not a centre except {@link #getCityRadius}, which stays total by falling through to the
     * uncached roll for that case.</p>
     */
    private static float centreRadiusOrNone(ChunkCoord coord, PlanningContext provider) {
        Float cached = provider.caches().cityCentre.get(coord);
        if (cached != null) {
            return cached;
        }
        float result = isCityCentreUncached(coord, provider)
                ? cityRadiusUncached(coord, provider)
                : NOT_A_CENTRE;
        Float raced = provider.caches().cityCentre.putIfAbsent(coord, result);
        return raced != null ? raced : result;
    }

    public static boolean isCityCenter(ChunkCoord coord, PlanningContext provider) {
        return !Float.isNaN(centreRadiusOrNone(coord, provider));
    }

    private static boolean isCityCentreUncached(ChunkCoord coord, PlanningContext provider) {
        PredefinedCity city = getPredefinedCity(provider, coord);
        if (city != null) {
            return true;
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        RandomSource cityCenterRandom = Rng.at(provider.seed(), chunkX, chunkZ, Rng.Purpose.CITY_CENTER);
        return cityCenterRandom.nextDouble() < provider.preset().cityChance();
    }

    /**
     * Return the radius of the city with the given center
     */
    public static float getCityRadius(ChunkCoord coord, PlanningContext provider) {
        float cached = centreRadiusOrNone(coord, provider);
        if (!Float.isNaN(cached)) {
            return cached;
        }
        // Not a centre. Callers reach here only by asking for the radius of a coordinate that
        // never rolled one; answer exactly as before rather than inventing a value.
        return cityRadiusUncached(coord, provider);
    }

    private static float cityRadiusUncached(ChunkCoord coord, PlanningContext provider) {
        PredefinedCity city = getPredefinedCity(provider, coord);
        if (city != null) {
            return city.getRadius();
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        RandomSource cityRadiusRandom = Rng.at(provider.seed(), chunkX, chunkZ, Rng.Purpose.CITY_RADIUS);
        Preset profile = provider.preset();
        int cityRange = profile.cityMaxRadius() - profile.cityMinRadius();
        if (cityRange < 1) {
            cityRange = 1;
        }
        return profile.cityMinRadius() + cityRadiusRandom.nextInt(cityRange);
    }

    // Call this on a city center to get the stable style family of that city.
    static CityStyleSelection getCityStyleSelectionForCityCenter(
            ChunkCoord center, PlanningContext provider) {
        PredefinedCity predefined = getPredefinedCity(provider, center);
        if (predefined != null && predefined.getCityStyle() != null) {
            return CityStyleSelection.baseOnly(predefined.getCityStyle());
        }
        RandomSource random = Rng.at(provider.seed(), center.chunkX(), center.chunkZ(),
                Rng.Purpose.CITY_STYLE);
        return provider.worldStyles().atCityCenter(center)
                .getRandomCityStyle(provider, center, random);
    }

    /** Compatibility surface for the mix diagnostic; generation resolves the whole family. */
    public static String getCityStyleForCityCenter(ChunkCoord center, PlanningContext provider) {
        CityStyleSelection selection = getCityStyleSelectionForCityCenter(center, provider);
        return selection == null ? null : selection.citystyle();
    }

    static CityStyleSelection getCityStyleSelectionForPerlinRegion(
            ChunkCoord coord, PlanningContext provider) {
        ChunkCoord anchor = WorldStyleField.perlinRegionAnchor(coord);
        RandomSource familyRandom = Rng.at(provider.seed(), anchor.chunkX(), anchor.chunkZ(),
                Rng.Purpose.CITY_STYLE);
        return provider.worldStyles().atChunk(provider, anchor)
                .getRandomCityStyle(provider, anchor, familyRandom);
    }

    private static void addPredefinedCityStyleCandidates(
            ChunkCoord coord, PlanningContext provider, List<Pair<Float, String>> styles) {
        for (PredefinedCity city : predefined(provider).citiesCovering(coord)) {
            float radius = city.getRadius();
            float dx = (city.getChunkX() - coord.chunkX()) * 16.0f;
            float dz = (city.getChunkZ() - coord.chunkZ()) * 16.0f;
            float factor = (radius - (float) Math.sqrt(dx * dx + dz * dz)) / radius;
            ChunkCoord center = new ChunkCoord(city.getDimension(), city.getChunkX(), city.getChunkZ());
            CityStyleSelection selection = getCityStyleSelectionForCityCenter(center, provider);
            styles.add(Pair.of(factor, selection == null ? null : selection.styleAt(factor)));
        }
    }

    // Calculate the citystyle based on all surrounding cities
    public static CityStyle getCityStyle(ChunkCoord coord, PlanningContext provider, Preset profile) {
        // getOrCompute, not computeIfAbsent: this is reached from ChunkPlan
        // .getChunkCandidate, which calls it in a 3x3 loop over the neighbours, and computing
        // inside a ConcurrentHashMap bin lock deadlocks on that.
        return provider.caches().cityStyle.getOrCompute(coord, k -> getCityStyleInt(coord, provider, profile));
    }

    private static CityStyle getCityStyleInt(ChunkCoord coord, PlanningContext provider, Preset profile) {
        List<Pair<Float, String>> styles = new ArrayList<>();
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        // Not CITY_STYLE: the centre and Perlin helpers use that purpose at their scope anchors.
        // This addressed draw chooses among local overlap candidates and must not correlate with a
        // family draw merely because an observer happens to share an anchor coordinate.
        RandomSource cityStyleRandom = Rng.at(provider.seed(), chunkX, chunkZ, Rng.Purpose.CITY_STYLE_LOCAL);

        if (profile.cityChance() < 0) {
            addPredefinedCityStyleCandidates(coord, provider, styles);
            if (styles.isEmpty()) {
                CityRarityMap rarityMap = provider.caches().getCityRarityMap(provider.seed(),
                        profile.cityPerlinScale(), profile.cityPerlinOffset(), profile.cityPerlinInnerScale());
                float factor = rarityMap.getCityFactor(chunkX, chunkZ);
                CityStyleSelection selection = getCityStyleSelectionForPerlinRegion(coord, provider);
                styles.add(Pair.of(factor,
                        selection == null ? null : selection.styleAt(factor)));
            }
        } else {
            int offset = (profile.cityMaxRadius() + 15) / 16;
            for (int cx = chunkX - offset; cx <= chunkX + offset; cx++) {
                for (int cz = chunkZ - offset; cz <= chunkZ + offset; cz++) {
                    ChunkCoord c = new ChunkCoord(provider.dimension(), cx, cz);
                    if (isCityCenter(c, provider)) {
                        float radius = getCityRadius(c, provider);
                        float sqdist = (cx * 16 - (chunkX << 4)) * (cx * 16 - (chunkX << 4)) + (cz * 16 - (chunkZ << 4)) * (cz * 16 - (chunkZ << 4));
                        if (sqdist < radius * radius) {
                            float dist = (float) Math.sqrt(sqdist);
                            float factor = (radius - dist) / radius;
                            CityStyleSelection selection =
                                    getCityStyleSelectionForCityCenter(c, provider);
                            styles.add(Pair.of(factor,
                                    selection == null ? null : selection.styleAt(factor)));
                        }
                    }
                }
            }
        }

        String cityStyleName;
        if (styles.isEmpty()) {
            CityStyleSelection selection = provider.worldStyles().atChunk(provider, coord)
                    .getRandomCityStyle(provider, coord, cityStyleRandom);
            cityStyleName = selection == null ? null : selection.citystyle();
        } else {
            Pair<Float, String> fromList = Tools.getRandomFromList(cityStyleRandom, styles, Pair::getLeft);
            if (fromList == null) {
                cityStyleName = null;
            } else {
                cityStyleName = fromList.getRight();
            }
        }
        return provider.assets().cityStyles().get(cityStyleName);
    }

    /**
     * How strongly this chunk sits inside a city, memoized per coordinate.
     *
     * <p>The uncached body scans every coordinate within {@code cityMaxRadius} - 1089 of them at
     * the shipped {@code onlycities} radius - and it is asked several times for the same chunk:
     * once from {@code ChunkCandidates.candidate}, once from {@code ChunkPlan}, and up to four
     * more from {@code CityField.getCityLevelNormal}, whose averaging path calls
     * {@code isCityRaw} on the four neighbours it samples. Nothing in the scan depends on which
     * chunk is asking, so those repeats re-derived an identical answer.</p>
     *
     * <p>get-then-{@code putIfAbsent} rather than {@code getOrCompute} deliberately, for the
     * reason {@code getCityStyle} documents: the computation reaches other planning state, and
     * running it inside a {@code ConcurrentHashMap} bin lock is how that deadlocks. Two threads
     * racing on one coordinate both compute and agree, because this is a pure function of the
     * seed, the coordinate and the preset.</p>
     */
    public static float getCityFactor(ChunkCoord coord, PlanningContext provider, Preset profile) {
        Float cached = provider.caches().cityFactor.get(coord);
        if (cached != null) {
            return cached;
        }
        float result = cityFactorUncached(coord, provider, profile);
        Float raced = provider.caches().cityFactor.putIfAbsent(coord, result);
        return raced != null ? raced : result;
    }

    private static float cityFactorUncached(ChunkCoord coord, PlanningContext provider, Preset profile) {
        ResourceKey<Level> type = provider.dimension();
        // If we have a predefined building here we force a high city factor

        PredefinedBuilding predefinedBuilding = getPredefinedBuildingAtTopLeft(provider, coord);
        if (predefinedBuilding != null) {
            return 1.0f;
        }
        PredefinedStreet predefinedStreet = getPredefinedStreetAt(provider, coord);
        if (predefinedStreet != null) {
            return 1.0f;
        }

        predefinedBuilding = getPredefinedBuildingAtTopLeft(provider, coord.west());
        if (predefinedBuilding != null && predefinedBuilding.multi()) {
            return 1.0f;
        }
        predefinedBuilding = getPredefinedBuildingAtTopLeft(provider, coord.northWest());
        if (predefinedBuilding != null && predefinedBuilding.multi()) {
            return 1.0f;
        }
        predefinedBuilding = getPredefinedBuildingAtTopLeft(provider, coord.north());
        if (predefinedBuilding != null && predefinedBuilding.multi()) {
            return 1.0f;
        }

        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        float factor = 0;
        if (profile.cityChance() < 0) {
            CityRarityMap rarityMap = provider.caches().getCityRarityMap(provider.seed(),
                    profile.cityPerlinScale(), profile.cityPerlinOffset(), profile.cityPerlinInnerScale());
            factor = rarityMap.getCityFactor(chunkX, chunkZ);
        } else {
            int offset = (profile.cityMaxRadius() + 15) / 16;
            for (int cx = chunkX - offset; cx <= chunkX + offset; cx++) {
                for (int cz = chunkZ - offset; cz <= chunkZ + offset; cz++) {
                    ChunkCoord c = new ChunkCoord(type, cx, cz);
                    if (isCityCenter(c, provider)) {
                        float radius = getCityRadius(c, provider);
                        float sqdist = (cx * 16 - (chunkX << 4)) * (cx * 16 - (chunkX << 4)) + (cz * 16 - (chunkZ << 4)) * (cz * 16 - (chunkZ << 4));
                        if (sqdist < radius * radius) {
                            float dist = (float) Math.sqrt(sqdist);
                            factor += (radius - dist) / radius;
                        }
                    }
                }
            }
        }

        if (factor > 0.0001 && provider.registryAccess() != null) {
            // Check if the terrain is not too low or high for building. Gated on registry access
            // rather than a real WorldGenLevel so the world-creation preview (registry access
            // present, world null) applies this too - real worldgen always has both, so this is
            // unchanged there.
            ChunkHeightmap heightmap = provider.heightmap(coord);
            if (heightmap == null) {
                return 0;
            }
            if (heightmap.getHeight() < profile.cityMinHeight()) {
                return 0;
            }
            if (heightmap.getHeight() > profile.cityMaxHeight()) {
                return 0;
            }
        }

        if (factor > 0.0001 && provider.registryAccess() != null) {
            // The compiled styles are already on the planning context, resolved once when it was
            // built - Preset itself carries no worldStyle any more, so there is nothing left to
            // re-resolve here.
            //
            // primary(), not the chunk's style: this decides whether a city exists at all, so
            // attributing it to a nearby city would be circular.
            float multiplier = provider.worldStyles().primary().getCityChanceMultiplier(provider, coord);
            factor *= multiplier;
        }

        if (profile.citySpawnDistance2() > 0) {
            float dist = (float) Math.sqrt((chunkX << 4) * (chunkX << 4) + (chunkZ << 4) * (chunkZ << 4));
            double factorDist;
            if (dist <= profile.citySpawnDistance1()) {
                factorDist = profile.citySpawnMultiplier1();
            } else if (dist >= profile.citySpawnDistance2()) {
                factorDist = profile.citySpawnMultiplier2();
            } else {
                float f = (dist - profile.citySpawnDistance1()) / (profile.citySpawnDistance2() - profile.citySpawnDistance1());
                factorDist = profile.citySpawnMultiplier1() + f * (profile.citySpawnMultiplier2() - profile.citySpawnMultiplier1());
            }
            factor *= (float) factorDist;
        }

        return Math.min(Math.max(factor, 0), 1);
    }
}

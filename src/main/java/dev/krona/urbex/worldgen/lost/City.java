package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.lost.cityassets.*;
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


    public static boolean isCityCenter(ChunkCoord coord, PlanningContext provider) {
        PredefinedCity city = getPredefinedCity(provider, coord);
        if (city != null) {
            return true;
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        RandomSource cityCenterRandom = Rng.at(provider.seed(), chunkX, chunkZ, Rng.Purpose.CITY_CENTER);
        return cityCenterRandom.nextDouble() < provider.preset().CITY_CHANCE;
    }

    /**
     * Return the radius of the city with the given center
     */
    public static float getCityRadius(ChunkCoord coord, PlanningContext provider) {
        PredefinedCity city = getPredefinedCity(provider, coord);
        if (city != null) {
            return city.getRadius();
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        RandomSource cityRadiusRandom = Rng.at(provider.seed(), chunkX, chunkZ, Rng.Purpose.CITY_RADIUS);
        Preset profile = provider.preset();
        int cityRange = profile.CITY_MAXRADIUS - profile.CITY_MINRADIUS;
        if (cityRange < 1) {
            cityRange = 1;
        }
        return profile.CITY_MINRADIUS + cityRadiusRandom.nextInt(cityRange);
    }

    // Call this on a city center to get the style of that city
    public static String getCityStyleForCityCenter(ChunkCoord coord, PlanningContext provider) {
        PredefinedCity city = getPredefinedCity(provider, coord);
        if (city != null) {
            if (city.getCityStyle() != null) {
                return city.getCityStyle();
            }
            // Otherwise we chose a random city style
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        RandomSource cityStyleForCenterRandom = Rng.at(provider.seed(), chunkX, chunkZ, Rng.Purpose.CITY_STYLE);
        // The centre's own world style, drawn at the centre: this is what makes one city internally
        // coherent when a world mixes several datapacks, since every chunk of that city asks here.
        return provider.worldStyles().atCityCenter(coord)
                .getRandomCityStyle(provider, coord, cityStyleForCenterRandom);
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
        // Not CITY_STYLE: getCityStyleForCityCenter draws from that address for this same chunk,
        // and this method calls it, so one purpose would make the blend agree with the centre.
        RandomSource cityStyleRandom = Rng.at(provider.seed(), chunkX, chunkZ, Rng.Purpose.CITY_STYLE_LOCAL);

        if (profile.CITY_CHANCE < 0) {
            CityRarityMap rarityMap = provider.caches().getCityRarityMap(provider.seed(),
                    profile.CITY_PERLIN_SCALE, profile.CITY_PERLIN_OFFSET, profile.CITY_PERLIN_INNERSCALE);
            float factor = rarityMap.getCityFactor(chunkX, chunkZ);
            if (factor < profile.CITY_STYLE_THRESHOLD) {
                styles.add(Pair.of(factor, profile.CITY_STYLE_ALTERNATIVE));
            } else {
                styles.add(Pair.of(factor, getCityStyleForCityCenter(coord, provider)));
            }
        } else {
            int offset = (profile.CITY_MAXRADIUS + 15) / 16;
            for (int cx = chunkX - offset; cx <= chunkX + offset; cx++) {
                for (int cz = chunkZ - offset; cz <= chunkZ + offset; cz++) {
                    ChunkCoord c = new ChunkCoord(provider.dimension(), cx, cz);
                    if (isCityCenter(c, provider)) {
                        float radius = getCityRadius(c, provider);
                        float sqdist = (cx * 16 - (chunkX << 4)) * (cx * 16 - (chunkX << 4)) + (cz * 16 - (chunkZ << 4)) * (cz * 16 - (chunkZ << 4));
                        if (sqdist < radius * radius) {
                            float dist = (float) Math.sqrt(sqdist);
                            float factor = (radius - dist) / radius;
                            if (factor < profile.CITY_STYLE_THRESHOLD) {
                                styles.add(Pair.of(factor, profile.CITY_STYLE_ALTERNATIVE));
                            } else {
                                // The centre's style, not the observing chunk's: asking at
                                // `coord` gave every chunk of one city its own roll, so a
                                // single city had no coherent style (issue #37).
                                styles.add(Pair.of(factor, getCityStyleForCityCenter(c, provider)));
                            }
                        }
                    }
                }
            }
        }

        String cityStyleName;
        if (styles.isEmpty()) {
            cityStyleName = provider.worldStyles().atChunk(provider, coord)
                    .getRandomCityStyle(provider, coord, cityStyleRandom);
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

    public static float getCityFactor(ChunkCoord coord, PlanningContext provider, Preset profile) {
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
        if (profile.CITY_CHANCE < 0) {
            CityRarityMap rarityMap = provider.caches().getCityRarityMap(provider.seed(),
                    profile.CITY_PERLIN_SCALE, profile.CITY_PERLIN_OFFSET, profile.CITY_PERLIN_INNERSCALE);
            factor = rarityMap.getCityFactor(chunkX, chunkZ);
        } else {
            int offset = (profile.CITY_MAXRADIUS + 15) / 16;
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
            if (heightmap.getHeight() < profile.CITY_MINHEIGHT) {
                return 0;
            }
            if (heightmap.getHeight() > profile.CITY_MAXHEIGHT) {
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

        if (profile.CITY_SPAWN_DISTANCE2 > 0) {
            float dist = (float) Math.sqrt((chunkX << 4) * (chunkX << 4) + (chunkZ << 4) * (chunkZ << 4));
            double factorDist;
            if (dist <= profile.CITY_SPAWN_DISTANCE1) {
                factorDist = profile.CITY_SPAWN_MULTIPLIER1;
            } else if (dist >= profile.CITY_SPAWN_DISTANCE2) {
                factorDist = profile.CITY_SPAWN_MULTIPLIER2;
            } else {
                float f = (dist - profile.CITY_SPAWN_DISTANCE1) / (profile.CITY_SPAWN_DISTANCE2 - profile.CITY_SPAWN_DISTANCE1);
                factorDist = profile.CITY_SPAWN_MULTIPLIER1 + f * (profile.CITY_SPAWN_MULTIPLIER2 - profile.CITY_SPAWN_MULTIPLIER1);
            }
            factor *= (float) factorDist;
        }

        return Math.min(Math.max(factor, 0), 1);
    }
}

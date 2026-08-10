package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.TimedCache;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.cityassets.*;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedStreet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A city is defined as a big sphere. Buildings are where the radius is less then 70%
 */
public class City {

    record PreDefBuildingOffset(PredefinedBuilding building, int offsetX, int offsetZ) {}

    // These four are datapack-derived: identical for every dimension, and their contents already
    // carry the dimension they belong to. So they stay static - but they are built lazily from
    // several worker threads at once, so each one is a concurrent map published through its own
    // volatile guard. The guard is written last, so a thread that sees it set sees a full map.
    private static final Map<ChunkCoord, PredefinedCity> PREDEFINED_CITY_MAP = new ConcurrentHashMap<>();
    private static final Map<ChunkCoord, PredefinedBuilding> PREDEFINED_BUILDING_MAP = new ConcurrentHashMap<>();
    private static final Map<ChunkCoord, PredefinedStreet> PREDEFINED_STREET_MAP = new ConcurrentHashMap<>();
    private static final Map<ChunkCoord, PreDefBuildingOffset> OCCUPIED_CHUNKS_BUILDING = new ConcurrentHashMap<>();
    private static final Map<ChunkCoord, PredefinedStreet> OCCUPIED_CHUNKS_STREET = new ConcurrentHashMap<>();

    private static volatile boolean predefinedCityMapReady = false;
    private static volatile boolean predefinedBuildingMapReady = false;
    private static volatile boolean predefinedStreetMapReady = false;
    private static volatile boolean occupiedBuildingReady = false;
    private static volatile boolean occupiedStreetReady = false;

    /**
     * Drop the datapack-derived maps. Only the per-dimension caches used to need this; these
     * survive a dimension but not a datapack reload.
     */
    public static void cleanPredefinedCache() {
        predefinedCityMapReady = false;
        predefinedBuildingMapReady = false;
        predefinedStreetMapReady = false;
        occupiedBuildingReady = false;
        occupiedStreetReady = false;
        PREDEFINED_CITY_MAP.clear();
        PREDEFINED_BUILDING_MAP.clear();
        PREDEFINED_STREET_MAP.clear();
        OCCUPIED_CHUNKS_BUILDING.clear();
        OCCUPIED_CHUNKS_STREET.clear();
    }

    public static PredefinedCity getPredefinedCity(CommonLevelAccessor level, ChunkCoord coord) {
        AssetRegistries.loadPredefinedStuff(level);
        if (!predefinedCityMapReady) {
            for (PredefinedCity city : AssetRegistries.PREDEFINED_CITIES.getIterable()) {
                PREDEFINED_CITY_MAP.put(new ChunkCoord(city.getDimension(), city.getChunkX(), city.getChunkZ()), city);
            }
            predefinedCityMapReady = true;
        }
        if (PREDEFINED_CITY_MAP.isEmpty()) {
            return null;
        }
        return PREDEFINED_CITY_MAP.get(coord);
    }

    public static PredefinedBuilding getPredefinedBuildingAtTopLeft(CommonLevelAccessor level, ChunkCoord coord) {
        calculateMap(level);
        return PREDEFINED_BUILDING_MAP.get(coord);
    }

    public static PreDefBuildingOffset getPredefinedBuilding(IDimensionInfo provider, ChunkCoord coord) {
        calculateOccupied(provider);
        return OCCUPIED_CHUNKS_BUILDING.get(coord);
    }

    public static PredefinedStreet getPredefinedStreet(IDimensionInfo provider, ChunkCoord coord) {
        calculateOccupied(provider);
        return OCCUPIED_CHUNKS_STREET.get(coord);
    }

    // Return true if a chunk is occupied (by a predefined building or street)
    public static boolean isChunkOccupied(IDimensionInfo provider, ChunkCoord coord) {
        calculateOccupied(provider);
        return OCCUPIED_CHUNKS_BUILDING.containsKey(coord) || OCCUPIED_CHUNKS_STREET.containsKey(coord);
    }

    private static void calculateOccupied(IDimensionInfo provider) {
        if (!occupiedBuildingReady) {
            calculateMap(provider.getWorld());
            for (Map.Entry<ChunkCoord, PredefinedBuilding> entry : PREDEFINED_BUILDING_MAP.entrySet()) {
                PredefinedBuilding pb = entry.getValue();
                ChunkCoord root = entry.getKey();
                if (pb.multi()) {
                    MultiBuilding building = AssetRegistries.MULTI_BUILDINGS.getOrThrow(provider.getWorld(), pb.building());
                    // Add all occupied chunkcoords for the building to the occupied set
                    for (int x = 0 ; x < building.getDimX() ; x++) {
                        for (int z = 0 ; z < building.getDimZ() ; z++) {
                            OCCUPIED_CHUNKS_BUILDING.put(root.offset(x, z), new PreDefBuildingOffset(pb, x, z));
                        }
                    }
                } else {
                    OCCUPIED_CHUNKS_BUILDING.put(root, new PreDefBuildingOffset(pb, 0, 0));
                }
            }
            occupiedBuildingReady = true;
        }
        AssetRegistries.loadPredefinedStuff(provider.getWorld());
        if (!occupiedStreetReady) {
            for (PredefinedCity city : AssetRegistries.PREDEFINED_CITIES.getIterable()) {
                for (PredefinedStreet street : city.getPredefinedStreets()) {
                    OCCUPIED_CHUNKS_STREET.put(new ChunkCoord(city.getDimension(),
                            city.getChunkX() + street.relChunkX(), city.getChunkZ() + street.relChunkZ()), street);
                }
            }
            occupiedStreetReady = true;
        }
    }

    private static void calculateMap(CommonLevelAccessor level) {
        AssetRegistries.loadPredefinedStuff(level);
        if (!predefinedBuildingMapReady) {
            for (PredefinedCity city : AssetRegistries.PREDEFINED_CITIES.getIterable()) {
                for (PredefinedBuilding building : city.getPredefinedBuildings()) {
                    PREDEFINED_BUILDING_MAP.put(new ChunkCoord(city.getDimension(),
                            city.getChunkX() + building.relChunkX(), city.getChunkZ() + building.relChunkZ()), building);
                }
            }
            predefinedBuildingMapReady = true;
        }
    }

    public static PredefinedStreet getPredefinedStreet(CommonLevelAccessor level, ChunkCoord coord) {
        AssetRegistries.loadPredefinedStuff(level);
        if (!predefinedStreetMapReady) {
            for (PredefinedCity city : AssetRegistries.PREDEFINED_CITIES.getIterable()) {
                for (PredefinedStreet street : city.getPredefinedStreets()) {
                    PREDEFINED_STREET_MAP.put(new ChunkCoord(city.getDimension(),
                            city.getChunkX() + street.relChunkX(), city.getChunkZ() + street.relChunkZ()), street);
                }
            }
            predefinedStreetMapReady = true;
        }
        if (PREDEFINED_STREET_MAP.isEmpty()) {
            return null;
        }
        return PREDEFINED_STREET_MAP.get(coord);
    }


    public static boolean isCityCenter(ChunkCoord coord, IDimensionInfo provider) {
        PredefinedCity city = getPredefinedCity(provider.getWorld(), coord);
        if (city != null) {
            return true;
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        RandomSource cityCenterRandom = Rng.at(provider.getSeed(), chunkX, chunkZ, Rng.Purpose.CITY_CENTER);
        if ((provider.getProfile().isSpace() || provider.getProfile().isSpheres())) {
            // @todo config
            CitySphere sphere = CitySphere.getCitySphere(coord, provider);
            if (!sphere.isEnabled()) {
                // No sphere
                return cityCenterRandom.nextDouble() < provider.getOutsideProfile().CITY_CHANCE;
            }
            if (sphere.getCenter().chunkX() == chunkX && sphere.getCenter().chunkZ() == chunkZ) {
                // This chunk is the center of a city
                return cityCenterRandom.nextDouble() < provider.getProfile().CITY_CHANCE;
            }
            return false;
        } else {
            return cityCenterRandom.nextDouble() < provider.getProfile().CITY_CHANCE;
        }
    }

    /**
     * Return the radius of the city with the given center
     */
    public static float getCityRadius(ChunkCoord coord, IDimensionInfo provider) {
        PredefinedCity city = getPredefinedCity(provider.getWorld(), coord);
        if (city != null) {
            return city.getRadius();
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        RandomSource cityRadiusRandom = Rng.at(provider.getSeed(), chunkX, chunkZ, Rng.Purpose.CITY_RADIUS);
        UrbexProfile profile = provider.getProfile();
        int cityRange = profile.CITY_MAXRADIUS - profile.CITY_MINRADIUS;
        if (cityRange < 1) {
            cityRange = 1;
        }
        if (profile.isSpace() || profile.isSpheres()) {
            if (CitySphere.intersectsWithCitySphere(coord, provider)) {
                return profile.CITY_MINRADIUS + cityRadiusRandom.nextInt(cityRange);
            } else {
                return provider.getOutsideProfile().CITY_MINRADIUS + cityRadiusRandom.nextInt(provider.getOutsideProfile().CITY_MAXRADIUS - provider.getOutsideProfile().CITY_MINRADIUS);
            }
        } else {
            return profile.CITY_MINRADIUS + cityRadiusRandom.nextInt(cityRange);
        }
    }

    // Call this on a city center to get the style of that city
    public static String getCityStyleForCityCenter(ChunkCoord coord, IDimensionInfo provider) {
        PredefinedCity city = getPredefinedCity(provider.getWorld(), coord);
        if (city != null) {
            if (city.getCityStyle() != null) {
                return city.getCityStyle();
            }
            // Otherwise we chose a random city style
        }
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        RandomSource cityStyleForCenterRandom = Rng.at(provider.getSeed(), chunkX, chunkZ, Rng.Purpose.CITY_STYLE);
        return provider.getWorldStyle().getRandomCityStyle(provider, coord, cityStyleForCenterRandom);
    }

    // Calculate the citystyle based on all surrounding cities
    public static CityStyle getCityStyle(ChunkCoord coord, IDimensionInfo provider, UrbexProfile profile) {
        // getOrCompute, not computeIfAbsent: this is reached from BuildingInfo
        // .getChunkCharacteristics, which calls it in a 3x3 loop over the neighbours, and computing
        // inside a ConcurrentHashMap bin lock deadlocks on that.
        return provider.caches().cityStyle.getOrCompute(coord, k -> getCityStyleInt(coord, provider, profile));
    }

    private static CityStyle getCityStyleInt(ChunkCoord coord, IDimensionInfo provider, UrbexProfile profile) {
        List<Pair<Float, String>> styles = new ArrayList<>();
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        // Not CITY_STYLE: getCityStyleForCityCenter draws from that address for this same chunk,
        // and this method calls it, so one purpose would make the blend agree with the centre.
        RandomSource cityStyleRandom = Rng.at(provider.getSeed(), chunkX, chunkZ, Rng.Purpose.CITY_STYLE_LOCAL);

        if (profile.CITY_CHANCE < 0) {
            WorldGenLevel world = provider.getWorld();
            CityRarityMap rarityMap = provider.caches().getCityRarityMap(world.getSeed(),
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
                    ChunkCoord c = new ChunkCoord(provider.getType(), cx, cz);
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
            cityStyleName = provider.getWorldStyle().getRandomCityStyle(provider, coord, cityStyleRandom);
        } else {
            Pair<Float, String> fromList = Tools.getRandomFromList(cityStyleRandom, styles, Pair::getLeft);
            if (fromList == null) {
                cityStyleName = null;
            } else {
                cityStyleName = fromList.getRight();
            }
        }
        return AssetRegistries.CITYSTYLES.get(provider.getWorld(), cityStyleName);
    }

    public static float getCityFactor(ChunkCoord coord, IDimensionInfo provider, UrbexProfile profile) {
        ResourceKey<Level> type = provider.getType();
        // If we have a predefined building here we force a high city factor

        PredefinedBuilding predefinedBuilding = getPredefinedBuildingAtTopLeft(provider.getWorld(), coord);
        if (predefinedBuilding != null) {
            return 1.0f;
        }
        PredefinedStreet predefinedStreet = getPredefinedStreet(provider.getWorld(), coord);
        if (predefinedStreet != null) {
            return 1.0f;
        }

        predefinedBuilding = getPredefinedBuildingAtTopLeft(provider.getWorld(), coord.west());
        if (predefinedBuilding != null && predefinedBuilding.multi()) {
            return 1.0f;
        }
        predefinedBuilding = getPredefinedBuildingAtTopLeft(provider.getWorld(), coord.northWest());
        if (predefinedBuilding != null && predefinedBuilding.multi()) {
            return 1.0f;
        }
        predefinedBuilding = getPredefinedBuildingAtTopLeft(provider.getWorld(), coord.north());
        if (predefinedBuilding != null && predefinedBuilding.multi()) {
            return 1.0f;
        }

        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        float factor = 0;
        if (profile.CITY_CHANCE < 0) {
            CityRarityMap rarityMap = provider.caches().getCityRarityMap(provider.getSeed(),
                    profile.CITY_PERLIN_SCALE, profile.CITY_PERLIN_OFFSET, profile.CITY_PERLIN_INNERSCALE);
            factor = rarityMap.getCityFactor(chunkX, chunkZ);
        } else {
            int offset = (profile.CITY_MAXRADIUS + 15) / 16;
            for (int cx = chunkX - offset; cx <= chunkX + offset; cx++) {
                for (int cz = chunkZ - offset; cz <= chunkZ + offset; cz++) {
                    ChunkCoord c = new ChunkCoord(type, cx, cz);
                    UrbexProfile pro = BuildingInfo.getProfile(c, provider);
                    // Only count cities that are in the same 'profile' as this one
                    if (pro == profile) {
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
        }

        if (factor > 0.0001 && provider.registryAccess() != null) {
            // Check if the terrain is not too low or high for building. Gated on registry access
            // rather than a real WorldGenLevel so the world-creation preview (registry access
            // present, world null) applies this too - real worldgen always has both, so this is
            // unchanged there.
            ChunkHeightmap heightmap = provider.getHeightmap(coord);
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
            WorldStyle worldStyle = AssetRegistries.WORLDSTYLES.get(provider.registryAccess(), profile.getWorldStyle());
            float multiplier = worldStyle.getCityChanceMultiplier(provider, coord);
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

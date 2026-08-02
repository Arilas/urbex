package dev.krona.urbex.worldgen;

import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.PerlinNoiseGenerator14;
import dev.krona.urbex.varia.TimedCache;
import dev.krona.urbex.worldgen.lost.BiomeInfo;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.CityRarityMap;
import dev.krona.urbex.worldgen.lost.CitySphere;
import dev.krona.urbex.worldgen.lost.LostChunkCharacteristics;
import dev.krona.urbex.worldgen.lost.MultiChunk;
import dev.krona.urbex.worldgen.lost.Railway;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Every cache that used to be a static field on BuildingInfo, City, Highway, Railway, MultiChunk,
 * CitySphere or BiomeInfo. Owned by the dimension, so unloading a world drops them instead of
 * relying on someone remembering to call cleanCache() - and so two dimensions with different
 * profiles can no longer see each other's answers.
 * <p>
 * Every map here is concurrent, and every population site uses get / compute-outside / putIfAbsent
 * rather than computeIfAbsent. These caches are mutually recursive - building a chunk's
 * BuildingInfo reads its neighbours' characteristics, which read their city styles - and
 * ConcurrentHashMap.computeIfAbsent deadlocks on recursive population, even for distinct keys that
 * happen to land in the same bin. Racing threads may both compute; that is harmless, because every
 * one of these values is a pure function of the world seed and the coordinate.
 */
public final class DimensionCaches {

    public final TimedCache<ChunkCoord, BuildingInfo> buildingInfo = new TimedCache<>(Config.CACHE_CLEANUP_SECONDS::get);
    public final TimedCache<ChunkCoord, LostChunkCharacteristics> characteristics = new TimedCache<>(Config.CACHE_CLEANUP_SECONDS::get);
    public final TimedCache<ChunkCoord, Integer> cityLevel = new TimedCache<>(Config.CACHE_CLEANUP_SECONDS::get);
    public final TimedCache<ChunkCoord, CityStyle> cityStyle = new TimedCache<>(Config.CACHE_CLEANUP_SECONDS::get);
    public final TimedCache<ChunkCoord, MultiChunk> multiChunk = new TimedCache<>(Config.CACHE_CLEANUP_SECONDS::get);
    public final TimedCache<ChunkCoord, BiomeInfo> biomeInfo = new TimedCache<>(Config.CACHE_CLEANUP_SECONDS::get);
    public final ConcurrentHashMap<ChunkCoord, CitySphere> citySphere = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<ChunkCoord, Railway.RailChunkInfo> railInfo = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<ChunkCoord, Integer> xHighwayLevel = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<ChunkCoord, Integer> zHighwayLevel = new ConcurrentHashMap<>();
    public final TimedCache<ChunkCoord, ChunkHeightmap> heightmap = new TimedCache<>(Config.CACHE_CLEANUP_SECONDS::get);

    /**
     * The city-rarity map is per profile rather than per chunk: a city-sphere dimension asks for
     * one for its inside profile and one for its outside profile, which have different perlin
     * settings, so the key is the settings themselves.
     */
    public final ConcurrentHashMap<RaritySettings, CityRarityMap> cityRarity = new ConcurrentHashMap<>();

    /**
     * The two highway noise fields. Built here rather than lazily on first use: they are a pure
     * function of the world seed, so there is nothing to defer, and a lazily-populated static was
     * both a data race and a way for two dimensions to share one seed's highways.
     */
    public final PerlinNoiseGenerator14 highwayPerlinX;
    public final PerlinNoiseGenerator14 highwayPerlinZ;

    public DimensionCaches(long seed) {
        this.highwayPerlinX = new PerlinNoiseGenerator14(seed, 4);
        this.highwayPerlinZ = new PerlinNoiseGenerator14(seed, 4);
    }

    /** Identifies one city-rarity field. All four components come from the profile. */
    public record RaritySettings(long seed, double scale, double offset, double innerScale) {}

    public CityRarityMap getCityRarityMap(long seed, double scale, double offset, double innerScale) {
        RaritySettings key = new RaritySettings(seed, scale, offset, innerScale);
        CityRarityMap existing = cityRarity.get(key);
        if (existing != null) {
            return existing;
        }
        CityRarityMap computed = new CityRarityMap(seed, scale, offset, innerScale);
        CityRarityMap raced = cityRarity.putIfAbsent(key, computed);
        return raced != null ? raced : computed;
    }

    public void clear() {
        buildingInfo.clear();
        characteristics.clear();
        cityLevel.clear();
        cityStyle.clear();
        multiChunk.clear();
        biomeInfo.clear();
        citySphere.clear();
        railInfo.clear();
        xHighwayLevel.clear();
        zHighwayLevel.clear();
        heightmap.clear();
        cityRarity.clear();
    }
}

package dev.krona.urbex.worldgen;

import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.PerlinNoiseGenerator14;
import dev.krona.urbex.varia.TimedCache;
import dev.krona.urbex.worldgen.lost.BiomeInfo;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.gen.Scattered;
import dev.krona.urbex.worldgen.lost.CityRarityMap;
import dev.krona.urbex.worldgen.lost.ChunkCandidate;
import dev.krona.urbex.worldgen.lost.MultiChunk;
import dev.krona.urbex.worldgen.lost.Railway;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.cityassets.PaletteCache;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Every cache that used to be a static field on ChunkPlan, City, Highway, Railway, MultiChunk,
 * BiomeInfo. Owned by the dimension, so unloading a world drops them instead of
 * relying on someone remembering to call cleanCache() - and so two dimensions with different
 * profiles can no longer see each other's answers.
 * <p>
 * Every map here is concurrent, and every population site uses get / compute-outside / putIfAbsent
 * rather than computeIfAbsent. These caches are mutually recursive - building a chunk's
 * ChunkPlan reads its neighbours' candidate, which read their city styles - and
 * ConcurrentHashMap.computeIfAbsent deadlocks on recursive population, even for distinct keys that
 * happen to land in the same bin. Racing threads may both compute; that is harmless, because every
 * one of these values is a pure function of the world seed and the coordinate.
 */
public final class DimensionCaches {

    public final TimedCache<ChunkCoord, ChunkPlan> chunkPlan = new TimedCache<>(Config::cacheCleanupSeconds, "chunkPlan");
    public final TimedCache<ChunkCoord, ChunkCandidate> candidate = new TimedCache<>(Config::cacheCleanupSeconds, "candidate");
    public final TimedCache<ChunkCoord, Integer> cityLevel = new TimedCache<>(Config::cacheCleanupSeconds, "cityLevel");
    public final TimedCache<ChunkCoord, CityStyle> cityStyle = new TimedCache<>(Config::cacheCleanupSeconds, "cityStyle");
    /**
     * Which world style governs a chunk, when the world was created with several. Only ever
     * populated for a genuine mix: {@link WorldStyleField#atChunk} short-circuits before reaching
     * the cache when there is one style, so a single-style world does not allocate here at all.
     */
    public final TimedCache<ChunkCoord, WorldStyle> worldStyle = new TimedCache<>(Config::cacheCleanupSeconds, "worldStyle");
    public final TimedCache<ChunkCoord, MultiChunk> multiChunk = new TimedCache<>(Config::cacheCleanupSeconds, "multiChunk");
    public final TimedCache<ChunkCoord, BiomeInfo> biomeInfo = new TimedCache<>(Config::cacheCleanupSeconds, "biomeInfo");
    public final ConcurrentHashMap<ChunkCoord, Railway.RailChunkInfo> railInfo = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<ChunkCoord, Integer> xHighwayLevel = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<ChunkCoord, Integer> zHighwayLevel = new ConcurrentHashMap<>();
    public final TimedCache<ChunkCoord, ChunkHeightmap> heightmap = new TimedCache<>(Config::cacheCleanupSeconds, "heightmap");
    /** Keyed on the scatter area's anchor chunk, not a real chunk coordinate. */
    public final TimedCache<ChunkCoord, Scattered.AreaScan> scatterAreaScan = new TimedCache<>(Config::cacheCleanupSeconds, "scatterAreaScan");

    /**
     * The city-rarity map is per profile rather than per chunk so independently configured profiles
     * cannot poison each other's cached answer at the same coordinate.
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

    /**
     * The palettes this world has already compiled. Keyed on compiled assets rather than on
     * coordinates, so it is bounded by what the datapacks declare (issue #53).
     */
    public final PaletteCache palettes = new PaletteCache();


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
        chunkPlan.clear();
        candidate.clear();
        cityLevel.clear();
        cityStyle.clear();
        worldStyle.clear();
        multiChunk.clear();
        biomeInfo.clear();
        railInfo.clear();
        xHighwayLevel.clear();
        zHighwayLevel.clear();
        heightmap.clear();
        scatterAreaScan.clear();
        cityRarity.clear();
        palettes.clear();
    }
}

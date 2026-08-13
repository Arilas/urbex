package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.TimedCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * A loaded level's terrain, sampled from its chunk generator.
 *
 * <p>The heightmap half was on {@link CityGenerator} until now, which made the generator and the
 * dimension mutually constructing: {@code DefaultDimensionInfo} built a {@code CityGenerator} passing
 * {@code this}, and the generator read the level back out of it to sample a height. Sampling is not
 * generation - nothing here writes a block or even reads one - so it belongs on the level side of
 * that pair, and moving it there means the terrain port exists before the generator does (issue
 * #129).</p>
 */
public final class LevelTerrain implements TerrainSampler {

    private final WorldGenLevel level;
    private final Preset preset;
    private final TimedCache<ChunkCoord, ChunkHeightmap> cache;
    private final Registry<Biome> biomeRegistry;

    public LevelTerrain(WorldGenLevel level, Preset preset, DimensionCaches caches) {
        this.level = level;
        this.preset = preset;
        this.cache = caches.heightmap;
        this.biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
    }

    @Override
    public ChunkHeightmap heightmap(ChunkCoord chunk) {
        int heightSampleSize = Config.heightSampleSize();
        // The block this chunk shares a sampled height with, and the coordinate that height is taken
        // at. Both come from HeightSampleGrid so that the tiling is a partition and the sampled
        // coordinate is a function of the block rather than of whichever chunk asked first - see the
        // note there for what the old arithmetic did at the origin, and issue #126.
        int top = HeightSampleGrid.anchor(chunk.chunkX(), heightSampleSize);
        int left = HeightSampleGrid.anchor(chunk.chunkZ(), heightSampleSize);
        ChunkCoord sampler = new ChunkCoord(chunk.dimension(),
                HeightSampleGrid.sampler(top, heightSampleSize),
                HeightSampleGrid.sampler(left, heightSampleSize));
        // No lock. The heightmap is a pure function of the generator and the coordinate, so two
        // threads that race on the same chunk build two equal heightmaps and one of them is thrown
        // away; a third thread reading the cache sees whichever was published, and they agree.
        //
        // That holds only as long as nobody writes to what this returns - see TerrainSampler.
        ChunkHeightmap cached = cache.get(chunk);
        if (cached != null) {
            return cached;
        }
        ChunkHeightmap heightmap = new ChunkHeightmap(preset.landscapeType(), preset.groundLevel());
        heightmap.update(baseHeight((sampler.chunkX() << 4) + 8, (sampler.chunkZ() << 4) + 8));
        if (heightSampleSize > 1) {
            for (int i = 0; i < heightSampleSize; i++) {
                for (int j = 0; j < heightSampleSize; j++) {
                    ChunkCoord sampleKey = new ChunkCoord(chunk.dimension(), top + i, left + j);
                    cache.putIfAbsent(sampleKey, new ChunkHeightmap(heightmap));
                }
            }
        } else {
            cache.putIfAbsent(chunk, heightmap);
        }
        return heightmap;
    }

    @Override
    public void sampleAccurateHeight(ChunkHeightmap heightmap, int chunkX, int chunkZ) {
        int cx = chunkX << 4;
        int cz = chunkZ << 4;
        heightmap.accurateHeights(
                baseHeight(cx + 2, cz + 2),
                baseHeight(cx + 2, cz + 14),
                baseHeight(cx + 14, cz + 2),
                baseHeight(cx + 14, cz + 14));
    }

    /** The generator's own surface height at a block column. No chunk is loaded and no block read. */
    private int baseHeight(int x, int z) {
        ServerChunkCache chunkProvider = level.getLevel().getChunkSource();
        ChunkGenerator generator = chunkProvider.getGenerator();
        RandomState randomState = chunkProvider.randomState();
        return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
    }

    @Override
    public Holder<Biome> biome(BlockPos pos) {
        ChunkSource chunkProvider = level.getChunkSource();
        if (chunkProvider instanceof ServerChunkCache cache) {
            ChunkGenerator generator = cache.getGenerator();
            BiomeSource biomeProvider = generator.getBiomeSource();
            Climate.Sampler sampler = cache.randomState().sampler();
            return biomeProvider.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2, sampler);
        }
        return biomeRegistry.getOrThrow(Biomes.PLAINS);
    }

    @Override
    public RegistryAccess registryAccess() {
        return level.registryAccess();
    }
}

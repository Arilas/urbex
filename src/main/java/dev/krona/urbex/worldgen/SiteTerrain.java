package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.LandscapeType;
import dev.krona.urbex.varia.ChunkCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;

/**
 * The terrain a {@link SiteBinding site} plans against: flat, at the height the caller named.
 *
 * <p>A site is somewhere the dimension's own surface has nothing to say about. Asking the chunk
 * generator how high the ground is at a bunker three hundred blocks underground answers about the
 * hillside overhead, and every rule that reads it - which height band the city sits in, how far the
 * terrain has to be corrected to meet a street, whether this is a void chunk - then answers about
 * the hillside too. So the heightmap comes from {@link dev.krona.urbex.api.SiteField#groundY}
 * instead, and it is flat within a chunk: a cavity floor is a floor.</p>
 *
 * <p>Biomes are <em>not</em> replaced. A site is still somewhere in the world, and a datapack's
 * biome conditions - which building goes in a desert, which palette a swamp gets - should answer
 * about where the player actually is. They are delegated to the level's own sampler, which is also
 * what keeps registry-backed rules working.</p>
 */
public final class SiteTerrain implements TerrainSampler {

    private final TerrainSampler delegate;
    private final SiteBinding site;

    public SiteTerrain(TerrainSampler delegate, SiteBinding site) {
        this.delegate = delegate;
        this.site = site;
    }

    /**
     * A flat heightmap at the site's ground.
     *
     * <p>Built per call rather than cached. {@link ChunkHeightmap} is mutable and the contract on
     * {@link TerrainSampler#heightmap} says the returned instance is shared and must not be written
     * to - a rule the correction pass keeps by copying. Constructing one is two field writes, which
     * is cheaper than the cache lookup that would protect it.</p>
     */
    @Override
    public ChunkHeightmap heightmap(ChunkCoord coord) {
        return new ChunkHeightmap(LandscapeType.DEFAULT, site.groundY(coord.chunkX(), coord.chunkZ()));
    }

    /**
     * The four extra points a real sampler takes across the chunk all report the same flat height
     * here, so this folds that height in four times. Not a no-op, despite there being nothing to
     * sample: min and max are {@code 0} on an unsampled map, and a caller that asked for them would
     * get the number zero rather than the site's floor.
     */
    @Override
    public void sampleAccurateHeight(ChunkHeightmap heightmap, int chunkX, int chunkZ) {
        int ground = site.groundY(chunkX, chunkZ);
        heightmap.accurateHeights(ground, ground, ground, ground);
    }

    @Nullable
    @Override
    public Holder<Biome> biome(BlockPos pos) {
        return delegate.biome(pos);
    }

    @Nullable
    @Override
    public RegistryAccess registryAccess() {
        return delegate.registryAccess();
    }
}

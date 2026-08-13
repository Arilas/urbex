package dev.krona.urbex.worldgen;

import dev.krona.urbex.varia.ChunkCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;

/**
 * What planning asks about terrain nobody has placed yet.
 *
 * <p>Three questions, and they are the last thing the planning path wanted a
 * {@link net.minecraft.world.level.WorldGenLevel} for once {@link LevelShape} took the height bounds
 * away: how high the ground is at a chunk, what biome is at a position, and which registries to
 * resolve a biome id against. A real dimension answers them from its chunk generator; the
 * world-creation preview answers them from a bitmap. Neither answer is {@code null}, which is the
 * point - {@code NullDimensionInfo.getWorld()} returning {@code null} is what forced planning rules
 * to be written as {@code if (provider.getWorld() != null)} (issue #129).</p>
 *
 * <p><strong>Nothing here reads a block.</strong> Every implementation answers from the chunk
 * generator's own noise, so it is safe to ask about a chunk that has not been generated - which is
 * exactly what planning does, and what makes the answers order-independent. Reading or writing
 * actual blocks during generation goes through the region on the {@link ChunkGenContext}.</p>
 */
public interface TerrainSampler {

    /**
     * The heightmap for {@code coord}: a pure function of the chunk generator and the coordinate.
     *
     * <p><strong>Shared - do not write to what this returns.</strong> The instance is cached and
     * handed to every thread generating near that chunk, so a caller that needs to mutate one takes
     * a copy first ({@link ChunkHeightmap#ChunkHeightmap(ChunkHeightmap)} exists for this). Writing
     * to the published instance is a data race on a plain {@code int} and, worse, makes what a
     * neighbouring chunk reads depend on generation order (issue #24).</p>
     */
    ChunkHeightmap heightmap(ChunkCoord coord);

    /**
     * Fills in {@code heightmap}'s min/max by sampling four more points across the chunk.
     *
     * <p>Writes to the argument, so it must be a copy - see {@link #heightmap}. Split out of
     * {@code ChunkHeightmap.calculateAccurateHeight}, which took a level to reach the chunk
     * generator: sampling is this port's business, and folding the samples in is the heightmap's.
     * </p>
     */
    void sampleAccurateHeight(ChunkHeightmap heightmap, int chunkX, int chunkZ);

    /**
     * The biome at {@code pos}, or {@code null} when there are no registries to resolve one against.
     */
    @Nullable
    Holder<Biome> biome(BlockPos pos);

    /**
     * The registries biome-backed planning rules resolve against, or {@code null} if there are none.
     * <p>
     * Separate from "is there a level": the world-creation preview has registry access - so it can
     * evaluate registry-backed worldgen rules - without having a level at all.
     */
    @Nullable
    RegistryAccess registryAccess();
}

package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.LostCityProfile;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.LightPool;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Everything one chunk's generation needs, built at the start of that generation and discarded at
 * the end. Nothing here may be stored on a field of a longer-lived object: that is precisely the
 * mistake this type exists to prevent.
 */
public final class ChunkGenContext {

    public final ChunkDriver driver;
    public final WorldGenRegion region;
    public final ChunkAccess chunk;
    public final ChunkCoord coord;
    public final IDimensionInfo provider;
    public final LostCityProfile profile;
    public final BuildingInfo info;
    public final CompiledPalette palette;
    public final char street;
    public final NoiseBuffers buffers;
    /**
     * Scratch for {@link LostCityTerrainFeature#moveDown}'s top-of-column stash. Lives here
     * because the feature instance is shared across worldgen worker threads: as an instance
     * field there, two threads in moveDown at once swapped each other's terrain (issue #43).
     */
    public final BlockState[] moveDownBuffer = new BlockState[6];
    private final LightTodoQueue lightTodo;


    /** The world seed, for the position-addressed picks that resolve palette characters. */
    public final long seed;

    public ChunkGenContext(WorldGenRegion region, ChunkAccess chunk, ChunkCoord coord,
                           IDimensionInfo provider, LostCityProfile profile, BuildingInfo info) {
        this.region = region;
        this.chunk = chunk;
        this.coord = coord;
        this.provider = provider;
        this.profile = profile;
        this.info = info;
        this.palette = info.getCompiledPalette();
        this.street = info.getCityStyle().getStreetBlock();
        this.driver = new ChunkDriver();
        this.driver.setPrimer(region, chunk);
        this.buffers = new NoiseBuffers();
        this.seed = provider.getSeed();
        this.lightTodo = new LightTodoQueue(coord.chunkX(), coord.chunkZ());
    }

    void addLightTodo(BlockPos pos, @Nullable LightPool pool) {
        lightTodo.add(pos, pool);
    }

    List<LightTodoQueue.Todo> drainLightTodo() {
        return lightTodo.closeAndDrain();
    }

    /**
     * Resolve a weighted palette character at the block the driver is about to write.
     * <p>
     * Valid only where {@code driver.current} already points at the destination - inside a
     * {@code block()} or {@code add()} chain. Where it does not, use {@link #paletteAt}.
     */
    public net.minecraft.world.level.block.state.BlockState paletteHere(CompiledPalette p, char c) {
        return p.getAt(c, seed, driver.getX(), driver.getY(), driver.getZ());
    }

    /** Resolve a weighted palette character at a chunk-local position. */
    public net.minecraft.world.level.block.state.BlockState paletteAt(CompiledPalette p, char c, int x, int y, int z) {
        return p.getAt(c, seed, (coord.chunkX() << 4) + x, y, (coord.chunkZ() << 4) + z);
    }

    /**
     * An independent, seed-derived stream for {@code purpose} at this chunk.
     * <p>
     * A <em>new</em> stream every call. Obtain one per pass, before the loop that draws from it -
     * calling this inside a loop body hands back the same first value on every iteration.
     * <p>
     * Only for consumers drawn a <em>fixed</em> number of times per chunk. If the trip count of
     * the loop depends on what is already in the world, use {@link Rng#atPos} and friends: a
     * sequential stream would let that trip count perturb every later draw.
     */
    public RandomSource rng(Rng.Purpose purpose) {
        return Rng.at(seed, coord.chunkX(), coord.chunkZ(), purpose);
    }
}

package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.LostCityProfile;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;

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
     * The one stream every weighted palette character in this chunk draws from.
     * <p>
     * {@link CompiledPalette#get(char, RandomSource)} is called from dozens of places across a
     * single chunk - every floor of every building, every street slice, every filler column - and
     * the variety it produces is only interesting if consecutive draws differ. A fresh
     * {@link #rng} per call site would hand every one of them the same first value, so a building
     * would repeat itself floor for floor. One stream per chunk, addressed by seed and coordinate,
     * keeps that variety while staying independent of the order chunks are generated in.
     */
    public final RandomSource paletteRandom;

    private final long seed;

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
        this.paletteRandom = Rng.at(this.seed, coord.chunkX(), coord.chunkZ(), Rng.Purpose.PALETTE);
    }

    /**
     * An independent, seed-derived stream for {@code purpose} at this chunk.
     * <p>
     * A <em>new</em> stream every call. Obtain one per pass, before the loop that draws from it -
     * calling this inside a loop body hands back the same first value on every iteration.
     */
    public RandomSource rng(Rng.Purpose purpose) {
        return Rng.at(seed, coord.chunkX(), coord.chunkZ(), purpose);
    }
}

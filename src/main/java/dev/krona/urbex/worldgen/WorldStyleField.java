package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.setup.WorldStyleMix;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.lost.City;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CommonLevelAccessor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Which world style applies where, for a dimension created with a weighted mix of them.
 * <p>
 * A world style is not one scope. Its {@code citystyles} and {@code outsidestyle} describe a city;
 * its highway and railway {@code parts} describe a network that runs between cities for hundreds of
 * chunks. Mixing forces that distinction to become explicit, so this class offers one accessor per
 * scope and {@link PlanningContext} hands out the field rather than a single style - a call site has
 * to say which scope it means, and the compiler makes it.
 * <p>
 * <b>The single-style fast path is the point.</b> With one entry every accessor returns that style
 * without touching {@link Rng} at all, so a world that does not mix generates exactly what it
 * generated before this class existed. Both worldgen digests depend on that.
 * <p>
 * Every draw is addressed by {@code (seed, coordinate, WORLD_STYLE)} like the rest of generation,
 * so two chunks built in either order agree about which pack a city came from, and a city keeps its
 * style across a restart.
 */
public final class WorldStyleField {

    /**
     * The grid a perlin-rarity preset ({@code cityChance < 0}, e.g. {@code urbex:largecities})
     * draws on. Such a preset has no discrete city centres to attribute a chunk to, so without a
     * coarse grid every chunk of one continuous city blob would draw its own style. 16 chunks is a
     * 256-block patch: large enough to read as one district, small enough that a mix still shows.
     */
    private static final int PERLIN_REGION_CHUNKS = 16;

    /** One resolved, weighted style. */
    public record Weighted(float weight, WorldStyle style) {
    }

    private final long seed;
    private final List<Weighted> entries;
    private final WorldStyle primary;
    private final boolean single;

    public WorldStyleField(long seed, List<Weighted> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("A world style field needs at least one style");
        }
        this.seed = seed;
        this.entries = List.copyOf(entries);
        this.single = this.entries.size() == 1;
        Weighted best = this.entries.get(0);
        for (Weighted candidate : this.entries) {
            // Same rule as WorldStyleMix.primary: heaviest wins, ties break on the id string rather
            // than on list position, so the answer never depends on registry iteration order.
            if (candidate.weight() > best.weight()
                    || (candidate.weight() == best.weight()
                        && candidate.style().getName().compareTo(best.style().getName()) < 0)) {
                best = candidate;
            }
        }
        this.primary = best.style();
    }

    /**
     * Resolves every id in {@code mix} against the world style registry, once, at dimension
     * construction - the same point {@code DefaultDimensionInfo} used to resolve its single style.
     */
    public static WorldStyleField resolve(AssetSnapshot assets, long seed, WorldStyleMix mix) {
        List<Weighted> resolved = new ArrayList<>(mix.entries().size());
        for (WorldStyleMix.Entry entry : mix.entries()) {
            resolved.add(new Weighted(entry.weight(), assets.worldStyles().get(entry.style())));
        }
        return new WorldStyleField(seed, resolved);
    }

    /** A field over one already-resolved style, for the world-creation preview and for tests. */
    public static WorldStyleField single(long seed, WorldStyle style) {
        return new WorldStyleField(seed, List.of(new Weighted(1.0f, style)));
    }

    public boolean isSingle() {
        return single;
    }

    /**
     * The style world-spanning settings come from: highway and railway {@code parts}, the world
     * {@code settings}, {@code citybiomemultipliers}, and the multichunk and scatter grid sizes.
     * These cannot vary by location - a highway that changed datapack partway along its run would
     * not join up, and a per-area {@code areasize} would have to be read from an area it has not
     * defined yet.
     */
    @Nonnull
    public WorldStyle primary() {
        return primary;
    }

    /** Every style in the mix, for validation and diagnostics. */
    public List<WorldStyle> styles() {
        List<WorldStyle> all = new ArrayList<>(entries.size());
        for (Weighted entry : entries) {
            all.add(entry.style());
        }
        return List.copyOf(all);
    }

    /** The style of the city centred on this chunk. Its {@code citystyles} shape the whole city. */
    @Nonnull
    public WorldStyle atCityCenter(ChunkCoord center) {
        return draw(center.chunkX(), center.chunkZ());
    }

    /** The style a scatter area's structure is drawn from, addressed at the area's anchor chunk. */
    @Nonnull
    public WorldStyle atScatterArea(ChunkCoord anchor) {
        return draw(anchor.chunkX(), anchor.chunkZ());
    }

    /** The style a multichunk area's multi-building settings come from, at its anchor. */
    @Nonnull
    public WorldStyle atMultiArea(ChunkCoord anchor) {
        return draw(anchor.chunkX(), anchor.chunkZ());
    }

    /**
     * The style governing an ordinary chunk - its {@code outsidestyle}, its {@code rotatable} tag,
     * the palette it builds outside a city.
     * <p>
     * The dominant nearby city centre's style, so a chunk on a city's edge looks like that city
     * rather than like a coin flip. No centre in range gives {@link #primary()}. A perlin-rarity
     * preset has no centres at all and falls back to the coarse region grid; see
     * {@link #PERLIN_REGION_CHUNKS}.
     */
    @Nonnull
    public WorldStyle atChunk(PlanningContext provider, ChunkCoord coord) {
        if (single) {
            return primary;
        }
        // getOrCompute, not computeIfAbsent: this is reached from ChunkPlan while its
        // neighbours' candidate are being built, and a recursive computeIfAbsent deadlocks on
        // the bin lock even for distinct keys. Same rule as every other cache in DimensionCaches.
        return provider.caches().worldStyle.getOrCompute(coord, k -> atChunkInt(provider, coord));
    }

    private WorldStyle atChunkInt(PlanningContext provider, ChunkCoord coord) {
        Preset profile = provider.preset();
        int chunkX = coord.chunkX();
        int chunkZ = coord.chunkZ();
        if (profile.CITY_CHANCE < 0) {
            return draw(Math.floorDiv(chunkX, PERLIN_REGION_CHUNKS), Math.floorDiv(chunkZ, PERLIN_REGION_CHUNKS));
        }
        ChunkCoord best = null;
        float bestFactor = 0;
        int offset = (profile.CITY_MAXRADIUS + 15) / 16;
        for (int cx = chunkX - offset; cx <= chunkX + offset; cx++) {
            for (int cz = chunkZ - offset; cz <= chunkZ + offset; cz++) {
                ChunkCoord c = new ChunkCoord(provider.dimension(), cx, cz);
                if (!City.isCityCenter(c, provider)) {
                    continue;
                }
                float radius = City.getCityRadius(c, provider);
                float dx = cx * 16 - (chunkX << 4);
                float dz = cz * 16 - (chunkZ << 4);
                float sqdist = dx * dx + dz * dz;
                if (sqdist >= radius * radius) {
                    continue;
                }
                float factor = (radius - (float) Math.sqrt(sqdist)) / radius;
                // Ties break on the coordinate, not on scan order, so the answer does not depend on
                // which way the loops happen to run.
                if (best == null || factor > bestFactor
                        || (factor == bestFactor
                            && (cx < best.chunkX() || (cx == best.chunkX() && cz < best.chunkZ())))) {
                    best = c;
                    bestFactor = factor;
                }
            }
        }
        return best == null ? primary : atCityCenter(best);
    }

    private WorldStyle draw(int x, int z) {
        if (single) {
            return primary;
        }
        RandomSource random = Rng.at(seed, x, z, Rng.Purpose.WORLD_STYLE);
        Weighted picked = Tools.getRandomFromList(random, entries, Weighted::weight);
        return picked == null ? primary : picked.style();
    }
}

package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.RoadField;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;

/**
 * Everything a chunk is planned against, and nothing else.
 *
 * <p>Planning is a pure function of a coordinate and this record: two chunks planned in either order,
 * on any thread, in a server or in the world-creation preview, see the same answer. That is what
 * makes the plan cacheable and the digest stable, and it is a property of what planning is <em>able
 * to reach</em> rather than a discipline anyone maintains.</p>
 *
 * <p>What it replaces is {@code IDimensionInfo}: a thirteen-method interface that also handed out the
 * dimension's {@link net.minecraft.world.level.WorldGenLevel}, its {@link CityGenerator}, and two
 * different methods returning the same dimension key. The generator is the reason it could not be a
 * value - {@code DefaultDimensionInfo} constructed a {@code CityGenerator} passing {@code this}, and
 * the generator read the level back out of it - so the two could not be built independently and
 * neither could be built in a test without the other (issue #129).</p>
 *
 * <p>A {@code PlanningContext} is a composition-root value, not an argument every helper should take
 * wholesale. Planners and renderers keep accepting the collaborators they actually read; this is what
 * the composition root has to hand out to build them.</p>
 *
 * @param seed        the dimension's seed. Every RNG address in planning starts here.
 * @param dimension   which dimension this is - part of every {@link ChunkCoord}, so two dimensions
 *                    sharing a seed do not share cached plans.
 * @param preset      the resolved preset, immutable for the runtime epoch that published this.
 * @param assets      the compiled assets, one snapshot per world load.
 * @param worldStyles which world style governs where, when the world mixes several.
 * @param roadField   where the roads are: a pure function of seed, dimension id and grid settings.
 * @param caches      the per-dimension memo tables. Every entry is a pure function of the above.
 * @param shape       how deep and how high the dimension goes, and where its water sits.
 * @param terrain     the ground height and the biome, sampled without reading a block.
 */
public record PlanningContext(
        long seed,
        ResourceKey<Level> dimension,
        Preset preset,
        AssetSnapshot assets,
        WorldStyleField worldStyles,
        RoadField roadField,
        DimensionCaches caches,
        LevelShape shape,
        TerrainSampler terrain) {

    /**
     * The heightmap at {@code coord}. Shared and not to be written to - see
     * {@link TerrainSampler#heightmap}.
     */
    public ChunkHeightmap heightmap(ChunkCoord coord) {
        return terrain.heightmap(coord);
    }

    /** The heightmap at a chunk of this dimension. */
    public ChunkHeightmap heightmap(int chunkX, int chunkZ) {
        return terrain.heightmap(new ChunkCoord(dimension, chunkX, chunkZ));
    }

    /** The biome at {@code pos}, or null when there are no registries to resolve one against. */
    @Nullable
    public Holder<Biome> biome(BlockPos pos) {
        return terrain.biome(pos);
    }

    /**
     * The registries biome-backed planning rules resolve against, or null if there are none.
     * <p>
     * The preview has these without having a level, which is why several rules are gated on this
     * rather than on anything about a server.
     */
    @Nullable
    public RegistryAccess registryAccess() {
        return terrain.registryAccess();
    }

    /** A coordinate in this dimension. */
    public ChunkCoord coord(int chunkX, int chunkZ) {
        return new ChunkCoord(dimension, chunkX, chunkZ);
    }
}

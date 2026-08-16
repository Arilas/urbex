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
 * @param shape       how deep and how high the dimension goes, and where its water sits. Clamped to
 *                    the window when this context belongs to a {@code site}.
 * @param terrain     the ground height and the biome, sampled without reading a block.
 * @param site        null for a level planning for itself, which is every chunk Urbex generates on
 *                    its own behalf. Non-null when another mod asked for this patch of city and
 *                    answers three of planning's inputs itself; see {@link SiteBinding}.
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
        TerrainSampler terrain,
        @Nullable SiteBinding site) {

    /**
     * A context for a level planning on its own behalf.
     *
     * <p>Every caller that existed before sites did builds one of these, and none of them had to
     * change: a site is an addition to what a planning context can be, not a new argument every
     * composition root has to have an opinion about.</p>
     */
    public PlanningContext(long seed, ResourceKey<Level> dimension, Preset preset,
                           AssetSnapshot assets, WorldStyleField worldStyles, RoadField roadField,
                           DimensionCaches caches, LevelShape shape, TerrainSampler terrain) {
        this(seed, dimension, preset, assets, worldStyles, roadField, caches, shape, terrain, null);
    }

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

    /**
     * The Y that {@code cityLevel} 0 sits at: the preset's ground level, or a site's window bottom.
     * <p>
     * What a condition means by "level" - the number a loot table or a spawner rule is matched
     * against. Asking the preset directly, as this used to, gives a site the ground level of a
     * dimension it is nowhere near: {@code urbex:cavern} says 40, so a bunker at -36 reported level
     * -12 and matched whatever a datapack wrote for the bottom of the world.
     */
    public int baseGroundLevel() {
        return site != null ? site.minY() : preset.groundLevel();
    }
}

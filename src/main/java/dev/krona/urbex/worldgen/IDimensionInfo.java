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
 * What one dimension plans and generates with.
 *
 * <p>It no longer hands out a {@code WorldGenLevel}. Everything the planning path asked one for is
 * a narrower question now - {@link LevelShape} for the height bounds and the water line,
 * {@link TerrainSampler} for the ground height, the biome and the registries - and none of those has
 * an answer only a server can give. That is what made {@code getWorld() != null} a planning
 * condition: five call sites branched on it, meaning "am I the world-creation preview?" (issue
 * #129).</p>
 *
 * <p>Block access during generation goes through the region on the {@link ChunkGenContext}, as it
 * already did; the dimension's own level is held by its {@link DimensionRuntime}.</p>
 */
public interface IDimensionInfo {

    long getSeed();

    /**
     * How deep this dimension goes, how high it goes, and where its water sits.
     * <p>
     * Resolved once, when the dimension's runtime is built.
     */
    LevelShape shape();

    /**
     * How high the ground is, what biome is where, and which registries to resolve one against.
     * See {@link TerrainSampler}.
     */
    TerrainSampler terrain();

    /**
     * Registry access for this dimension, or {@code null} if none is available.
     * <p>
     * A real dimension's comes straight off its level; the preview has registry access without
     * having a level at all, which is why this is a question of its own.
     */
    @Nullable
    default RegistryAccess registryAccess() {
        return terrain().registryAccess();
    }

    /**
     * The compiled assets this dimension generates from.
     * <p>
     * One snapshot per world load, shared by every level in that world - the asset registries are
     * frozen when the world loads, so there is nothing per-level about what they compile to. Every
     * asset lookup goes through here rather than through a static registry, which is what makes
     * "compilation finished before generation started" a fact rather than a hope (issue #128).
     */
    AssetSnapshot assets();

    /** The per-dimension caches. Dropped with the dimension. */
    DimensionCaches caches();

    /**
     * Where the roads are. Built once per dimension from the seed, the dimension id and the
     * profile's grid settings, so every query is a pure function of the coordinate and two chunks
     * generated in either order see the same road.
     */
    RoadField roadField();

    ResourceKey<Level> getType();

    Preset getProfile();

    /**
     * The world styles this dimension generates from, and which one applies where.
     * <p>
     * Replaces the old single {@code getWorldStyle()} deliberately. A world style is not one scope
     * - a city's {@code citystyles} against a highway network's {@code parts} - and once a world can
     * mix several, a call site that silently took a dimension-wide style would be wrong without
     * saying so. Ask the field for the scope you mean.
     */
    WorldStyleField worldStyles();

    CityGenerator getFeature();

    default ChunkHeightmap getHeightmap(int chunkX, int chunkZ) {
        return terrain().heightmap(new ChunkCoord(getType(), chunkX, chunkZ));
    }

    default ChunkHeightmap getHeightmap(ChunkCoord coord) {
        return terrain().heightmap(coord);
    }

//    Biome[] getBiomes(int chunkX, int chunkZ);

    @Nullable
    default Holder<Biome> getBiome(BlockPos pos) {
        return terrain().biome(pos);
    }

    @Nullable
    ResourceKey<Level> dimension();
}

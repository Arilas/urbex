package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.plan.RoadField;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;

public interface IDimensionInfo {

    long getSeed();

    /**
     * The dimension's own level. Stable for the life of the dimension and safe to hold: this is
     * <em>not</em> the WorldGenRegion of whatever chunk happens to be generating, which is what it
     * used to be (via a setWorld() that a per-dimension lock had to guard).
     * <p>
     * Use it for level-wide questions - registry access, min/max build height, sea level, the seed.
     * Reading or writing blocks during generation must go through the region on the
     * {@link ChunkGenContext} instead; a region only has the chunks around the one being built,
     * and the level would go looking for the rest.
     */
    WorldGenLevel getWorld();

    /**
     * Registry access for this dimension, or {@code null} if none is available. Real dimensions
     * always have one (it comes straight off {@link #getWorld()}); {@code NullDimensionInfo} is the
     * one implementor that can have registry access - and so be able to evaluate registry-backed
     * worldgen rules - while still having no {@link WorldGenLevel} to hand out, which is what makes
     * this a separate question from "is {@link #getWorld()} null".
     */
    @Nullable
    default RegistryAccess registryAccess() {
        WorldGenLevel world = getWorld();
        return world != null ? world.registryAccess() : null;
    }

    /** The per-dimension caches. Dropped with the dimension. */
    DimensionCaches caches();

    /**
     * Where the roads are. Built once per dimension from the seed, the dimension id and the
     * profile's grid settings, so every query is a pure function of the coordinate and two chunks
     * generated in either order see the same road.
     */
    RoadField roadField();

    ResourceKey<Level> getType();

    UrbexProfile getProfile();

    UrbexProfile getOutsideProfile();

    WorldStyle getWorldStyle();

    CityGenerator getFeature();

    ChunkHeightmap getHeightmap(int chunkX, int chunkZ);

    ChunkHeightmap getHeightmap(ChunkCoord coord);

//    Biome[] getBiomes(int chunkX, int chunkZ);

    Holder<Biome> getBiome(BlockPos pos);

    @Nullable
    ResourceKey<Level> dimension();
}

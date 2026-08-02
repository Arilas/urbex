package dev.krona.urbex.worldgen;

import dev.krona.urbex.config.LostCityProfile;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkSource;
import org.jetbrains.annotations.Nullable;

public class DefaultDimensionInfo implements IDimensionInfo {

    // The dimension's ServerLevel, not the region of whichever chunk is generating. Final, so it
    // cannot be swapped out from under a worker thread, which is what the per-dimension lock in
    // LostCityFeature.place used to be protecting.
    private final WorldGenLevel world;
    private final LostCityProfile profile;
    private final LostCityProfile profileOutside;
    private final WorldStyle style;
    private final DimensionCaches caches;

    private final Registry<Biome> biomeRegistry;
    private final LostCityTerrainFeature feature;

    public DefaultDimensionInfo(WorldGenLevel world, LostCityProfile profile, LostCityProfile profileOutside) {
        this.world = world.getLevel();
        this.profile = profile;
        this.profileOutside = profileOutside;
        this.caches = new DimensionCaches(this.world.getSeed());
        style = AssetRegistries.WORLDSTYLES.get(this.world, profile.getWorldStyle());
        feature = new LostCityTerrainFeature(this, profile);
        biomeRegistry = this.world.registryAccess().lookupOrThrow(Registries.BIOME);
    }

    @Override
    public long getSeed() {
        return world.getSeed();
    }

    @Override
    public WorldGenLevel getWorld() {
        return world;
    }

    @Override
    public DimensionCaches caches() {
        return caches;
    }

    @Override
    public ResourceKey<Level> getType() {
        return world.getLevel().dimension();
    }

    @Override
    public LostCityProfile getProfile() {
        return profile;
    }

    @Override
    public LostCityProfile getOutsideProfile() {
        return profileOutside;
    }

    @Override
    public WorldStyle getWorldStyle() {
        return style;
    }

    @Override
    public LostCityTerrainFeature getFeature() {
        return feature;
    }

    @Override
    public ChunkHeightmap getHeightmap(int chunkX, int chunkZ) {
        ChunkCoord coord = new ChunkCoord(getType(), chunkX, chunkZ);
        return feature.getHeightmap(coord, getWorld());
    }

    @Override
    public ChunkHeightmap getHeightmap(ChunkCoord coord) {
        return feature.getHeightmap(coord, getWorld());
    }

    //    @Override
//    public Biome[] getBiomes(int chunkX, int chunkZ) {
//        AbstractChunkProvider chunkProvider = getWorld().getChunkProvider();
//        if (chunkProvider instanceof ServerChunkProvider) {
//            BiomeProvider biomeProvider = ((ServerChunkProvider) chunkProvider).getChunkGenerator().getBiomeProvider();
//            return biomeProvider.getBiomes((chunkX - 1) * 4 - 2, chunkZ * 4 - 2, 10, 10, false);
//        }
//    }
//
    @Override
    public Holder<Biome> getBiome(BlockPos pos) {
        ChunkSource chunkProvider = getWorld().getChunkSource();
        if (chunkProvider instanceof ServerChunkCache) {
            ChunkGenerator generator = ((ServerChunkCache) chunkProvider).getGenerator();
            BiomeSource biomeProvider = generator.getBiomeSource();
            Climate.Sampler sampler = ((ServerChunkCache) chunkProvider).randomState().sampler();
            return biomeProvider.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2, sampler);
        }
        return biomeRegistry.getOrThrow(Biomes.PLAINS);
    }

    @Nullable
    @Override
    public ResourceKey<Level> dimension() {
        return world.getLevel().dimension();
    }
}

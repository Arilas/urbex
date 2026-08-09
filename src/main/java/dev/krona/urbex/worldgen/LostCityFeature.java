package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.LostCityProfile;
import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.ServerEventHandlers;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LostCityFeature extends Feature<NoneFeatureConfiguration> {

    /**
     * On dedicated servers the dimensionInfo cache is no problem. The server starts only once
     * and will have the correct dimension info and for the clients it doesn't matter.
     * However, to make sure that on a single player world this cache is cleared when the player
     * exits the world and creates a new one we keep a static flag which is incremented whenever
     * the player exits the world. That is then used to help clear this cache
     */
    private final Map<ResourceKey<Level>, IDimensionInfo> dimensionInfo = new ConcurrentHashMap<>();
    public static volatile int globalDimensionInfoDirtyCounter = 0;
    private volatile int dimensionInfoDirtyCounter = -1;

    public LostCityFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    /**
     * The feature entry point is retained so an explicitly-configured datapack feature still
     * works, but the mod no longer injects it: city generation runs from the end of the carver
     * stage instead (see {@code CarverHookMixin}). At the decoration stage, a neighbouring
     * chunk's complete feature pass - ore blobs, border-crossing trees - may or may not have
     * bled into this chunk yet, so everything Urbex read from the terrain depended on worker
     * scheduling (issue #18). The pipeline guarantees that no neighbour's features can run
     * until this chunk finishes CARVERS, so at the carver tail Urbex always sees pure
     * noise+surface+carver terrain, and every vanilla feature lands strictly after the city.
     */
    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        if (level instanceof WorldGenRegion region) {
            ChunkPos center = region.getCenter();
            return generateFromPipeline(region, region.getChunk(center.x(), center.z()));
        }
        return false;
    }

    /** Generates the city content for {@code chunk}. Called from the carver-stage hook. */
    public boolean generateFromPipeline(WorldGenRegion region, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        IDimensionInfo diminfo = getDimensionInfo(region);
        if (diminfo == null) {
            return false;
        }
        ChunkPos center = chunk.getPos();
        Holder<Biome> biome = region.getBiome(center.getMiddleBlockPosition(60));
        if (biome.is(LostTags.IS_VOID)) {
            return false;
        }

        int chunkX = center.x();
        int chunkZ = center.z();
        // No lock. The terrain feature holds no per-chunk state any more (that is on the
        // ChunkGenContext built inside generate()), the caches it reaches are concurrent,
        // and the region arrives as an argument instead of being written onto the shared
        // IDimensionInfo. So Urbex generation runs on the worker pool in parallel with the
        // rest of worldgen again, as it did before the driver became shared.
        LostCityTerrainFeature feature = diminfo.getFeature();
        try {
            feature.generate(region, chunk);
        } catch (Exception e) {
            Urbex.getLogger().error("Error generating chunk {},{} (profile={}, dimension={})",
                    chunkX, chunkZ, diminfo.getProfile().getName(), diminfo.getType().identifier(), e);
            ErrorLogger.logChunkInfo(chunkX, chunkZ, diminfo);
            ErrorLogger.report("There was an error generating a chunk. See log for details!");
        }
        return true;
    }

    @Nullable
    public IDimensionInfo getDimensionInfo(WorldGenLevel world) {
        if (globalDimensionInfoDirtyCounter != dimensionInfoDirtyCounter) {
            // Force clear of cache
            cleanUp();
        }
        ResourceKey<Level> type = world.getLevel().dimension();
        IDimensionInfo known = dimensionInfo.get(type);
        if (known != null) {
            return known;
        }
        String profileName = Config.getProfileForDimension(world.getLevel(), type);
        if (profileName != null) {
            LostCityProfile profile = ProfileSetup.STANDARD_PROFILES.get(profileName);
            if (profile == null) {
                return null;
            }
            LostCityProfile outsideProfile = profile.CITYSPHERE_OUTSIDE_PROFILE == null ? null : ProfileSetup.STANDARD_PROFILES.get(profile.CITYSPHERE_OUTSIDE_PROFILE);
            // Built outside the map. Two threads may both build one for the same dimension the
            // first time a chunk is generated - the loser's is simply dropped, caches and all.
            IDimensionInfo diminfo = new DefaultDimensionInfo(world, profile, outsideProfile);
            IDimensionInfo raced = dimensionInfo.putIfAbsent(type, diminfo);
            return raced != null ? raced : diminfo;
        }
        return null;
    }

    public void cleanUp() {
        ServerEventHandlers.cleanUp();
        AssetRegistries.reset();
        dimensionInfo.clear();
        dimensionInfoDirtyCounter = globalDimensionInfoDirtyCounter;
    }
}

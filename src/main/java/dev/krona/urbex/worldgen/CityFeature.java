package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * The entry point into city generation. It holds no state.
 * <p>
 * It used to own the process-global dimension map and the dirty-counter protocol that maintained
 * it; both belong to {@link GenerationSession} now, which publishes a {@link DimensionRuntime} per
 * loaded level and retires it on unload (issue #125). What is left here is the dispatch.
 */
public class CityFeature extends Feature<NoneFeatureConfiguration> {

    public CityFeature() {
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

    /**
     * Generates the city content for {@code chunk}. Called from the carver-stage hook.
     * <p>
     * The runtime is read once, at the top, and everything below uses that one reference. A
     * {@code /reload} or an unload landing mid-chunk republishes the level's runtime for the
     * <em>next</em> chunk and leaves this one generating against the epoch it captured - which is
     * the whole point of the ownership move, and the opposite of what the dirty counter did.
     */
    public boolean generateFromPipeline(WorldGenRegion region, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        DimensionRuntime runtime = GenerationSession.runtimeFor(region);
        if (runtime == null || !runtime.isEnabled()) {
            return false;
        }
        IDimensionInfo diminfo = runtime.planning();
        ChunkPos center = chunk.getPos();
        Holder<Biome> biome = region.getBiome(center.getMiddleBlockPosition(60));
        if (biome.is(UrbexTags.IS_VOID)) {
            return false;
        }

        int chunkX = center.x();
        int chunkZ = center.z();
        // No lock. The terrain feature holds no per-chunk state any more (that is on the
        // ChunkGenContext built inside generate()), the caches it reaches are concurrent,
        // and the region arrives as an argument instead of being written onto the shared
        // IDimensionInfo. So Urbex generation runs on the worker pool in parallel with the
        // rest of worldgen again, as it did before the driver became shared.
        CityGenerator feature = runtime.generator();
        try {
            feature.generate(region, chunk);
        } catch (Exception e) {
            Urbex.getLogger().error("Error generating chunk {},{} (preset={}, dimension={})",
                    chunkX, chunkZ, diminfo.getProfile().getId(), diminfo.getType().identifier(), e);
            ErrorLogger.logChunkInfo(chunkX, chunkZ, diminfo);
            ErrorLogger.report("There was an error generating a chunk. See log for details!");
        }
        return true;
    }
}

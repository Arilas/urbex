package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
        if (runtime == null) {
            reportMissingRuntime(region.getLevel(), chunk.getPos());
            return false;
        }
        if (!runtime.isEnabled()) {
            // Urbex does not generate in this dimension. A published decision, not a missing one.
            return false;
        }
        PlanningContext diminfo = runtime.planning();
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
        // PlanningContext. So Urbex generation runs on the worker pool in parallel with the
        // rest of worldgen again, as it did before the driver became shared.
        CityGenerator feature = runtime.generator();
        try {
            feature.generate(runtime, region, chunk);
        } catch (Exception e) {
            Urbex.getLogger().error("Error generating chunk {},{} (preset={}, dimension={})",
                    chunkX, chunkZ, diminfo.preset().getId(), diminfo.dimension().identifier(), e);
            ErrorLogger.logChunkInfo(chunkX, chunkZ, diminfo);
            ErrorLogger.report("There was an error generating a chunk. See log for details!");
        }
        return true;
    }

    /**
     * Reported once per dimension per JVM: this cannot be allowed to be quiet.
     * <p>
     * A chunk reaching here with no published runtime generates as plain vanilla terrain, and
     * <em>saves that way</em>, in the middle of a city its neighbours have carved streets into. That
     * looks exactly like corrupted terrain - a floating cliff over a road - and nothing else in the
     * mod would say a word about it. It is also a shape the old code could not produce, because
     * {@code getDimensionInfo} built the missing state on the spot from the worker thread; removing
     * that lazy build (issue #125) is what makes this state reachable at all, so the diagnostic
     * belongs with it.
     * <p>
     * Every path that gets here is a lifecycle bug rather than a configuration one: a level
     * generating with no session open, or one whose runtime was retired while its chunks were still
     * in flight. The latch is a diagnostic, deliberately not the state this issue removed - it
     * decides nothing, and a wrong answer from it costs a duplicate or a missing log line.
     */
    private static void reportMissingRuntime(ServerLevel level, ChunkPos pos) {
        if (!REPORTED_MISSING_RUNTIME.add(level.dimension())) {
            return;
        }
        Urbex.getLogger().error(
                "Generating chunk {},{} in '{}' with no Urbex runtime published for that level: this "
                        + "chunk and any others in the same state get no city content at all and are "
                        + "saved that way, next to neighbours that did. Either the level loaded without "
                        + "ServerLevelEvents.LOAD firing, or its runtime was retired while chunks were "
                        + "still generating. Reported once per dimension.",
                pos.x(), pos.z(), level.dimension().identifier());
    }

    private static final Set<ResourceKey<Level>> REPORTED_MISSING_RUNTIME = ConcurrentHashMap.newKeySet();
}

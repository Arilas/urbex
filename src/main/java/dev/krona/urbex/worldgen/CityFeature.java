package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
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
 *
 * <h2>Exactly one invocation per chunk</h2>
 *
 * <p>Two routes reach {@link #generateFromPipeline}, and until issue #131 nothing coordinated them:
 * the carver-tail hook, which is how every supported world generates, and {@link #place}, which a
 * datapack can name in a biome's {@code features}. A pack that did both generated the chunk twice,
 * the second pass planning against terrain the first pass had already rewritten.</p>
 *
 * <p>The chunk itself now records that Urbex has been here - see {@link GeneratedChunkMark} - and the
 * second caller is refused. Which of the two arrives first is not this class's business: whichever
 * does, generates.</p>
 */
public class CityFeature extends Feature<NoneFeatureConfiguration> {

    public CityFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    /**
     * The custom-generator integration point: place {@code urbex:city} in a biome's features to
     * generate cities under a chunk generator the carver hook does not reach.
     *
     * <p>The mod injects this nowhere and the bundled pack does not use it. A world on
     * {@code NoiseBasedChunkGenerator} or {@code FlatLevelSource} - which is every vanilla world -
     * is generated at the carver tail instead (see {@code CarverHookMixin}), and placing the feature
     * there as well is refused rather than generating twice. Any other generator gets no carver hook
     * at all, so this is how such a world opts in, and it works because the marker makes the two
     * routes mutually exclusive rather than because a pack author avoided one of them.</p>
     *
     * <p><strong>Opting in this way costs the ordering guarantee #18 established.</strong> A feature
     * runs at the decoration stage, where a neighbouring chunk's complete feature pass - ore blobs,
     * border-crossing trees - may or may not have bled into this chunk yet, so what Urbex reads from
     * the terrain depends on worker scheduling. The carver tail has no such problem: the pipeline
     * guarantees no neighbour's features can run until this chunk finishes {@code CARVERS}, so Urbex
     * always sees pure noise+surface+carver terrain and every vanilla feature lands strictly after
     * the city. That is a property of the supported path, and a pack taking this one is choosing to
     * do without it.</p>
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
     * Static, because it needs nothing from the registered feature instance - it holds no state, and
     * everything this reads comes from the level's published {@link DimensionRuntime}. The carver
     * hook used to reach it through {@code Registration.cityFeature()}, a process-global slot holding
     * whichever {@code CityFeature} the last {@code Registration.init()} made; that is the last of
     * the "ask a static for the thing that generates" lookups #129 is about, and a lookup that can
     * answer {@code null} (before registration, or in a test) for something the mixin then skips
     * silently.
     * <p>
     * Claims the chunk first, so a second route to the same chunk is refused rather than generating
     * over what the first one wrote. Claimed before any other check, including "does Urbex generate
     * in this dimension at all": the marker records that this chunk has been dispatched, and the
     * answer to a question asked twice about one chunk must not depend on which caller asked.
     * <p>
     * The runtime is read once, at the top, and everything below uses that one reference. A
     * {@code /reload} or an unload landing mid-chunk republishes the level's runtime for the
     * <em>next</em> chunk and leaves this one generating against the epoch it captured - which is
     * the whole point of the ownership move, and the opposite of what the dirty counter did.
     */
    public static boolean generateFromPipeline(WorldGenRegion region, ChunkAccess chunk) {
        if (!GeneratedChunkMark.claim(chunk)) {
            reportDoubleDispatch(region.getLevel(), chunk.getPos());
            return false;
        }
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

    /**
     * Reported once per dimension per JVM, like the missing-runtime case above, and for the same
     * reason: a pack that reaches here is generating differently from every other install of itself,
     * and nothing else in the mod would say so.
     * <p>
     * The refusal itself is not a failure - it is the contract working - so this is a warning rather
     * than an error, and the pack keeps its cities. What it costs the pack is the ordering guarantee
     * described on {@link #place}, for the chunks the feature would have been the first to claim.
     */
    private static void reportDoubleDispatch(ServerLevel level, ChunkPos pos) {
        if (!REPORTED_DOUBLE_DISPATCH.add(level.dimension())) {
            return;
        }
        Urbex.getLogger().warn(
                "Urbex was asked to generate chunk {},{} in '{}' twice. The chunk was generated once, "
                        + "by whichever route arrived first, and the second request was refused - "
                        + "generating again would plan against terrain the first pass had already "
                        + "rewritten. A datapack has placed the 'urbex:city' feature in a world whose "
                        + "generator Urbex already hooks; remove it, since it is only needed for a "
                        + "custom chunk generator. Reported once per dimension.",
                pos.x(), pos.z(), level.dimension().identifier());
    }

    private static final Set<ResourceKey<Level>> REPORTED_MISSING_RUNTIME = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceKey<Level>> REPORTED_DOUBLE_DISPATCH = ConcurrentHashMap.newKeySet();
}

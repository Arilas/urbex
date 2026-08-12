package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.Presets;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.PresetChoice;
import dev.krona.urbex.setup.ServerEventHandlers;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
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

public class CityFeature extends Feature<NoneFeatureConfiguration> {

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

    /** Generates the city content for {@code chunk}. Called from the carver-stage hook. */
    public boolean generateFromPipeline(WorldGenRegion region, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        IDimensionInfo diminfo = getDimensionInfo(region);
        if (diminfo == null) {
            return false;
        }
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
        CityGenerator feature = diminfo.getFeature();
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

    @Nullable
    public IDimensionInfo getDimensionInfo(WorldGenLevel world) {
        reconcileDirtyCounter();
        // Every path that generates Urbex content comes through here first - CarverHookMixin ->
        // generateFromPipeline -> getDimensionInfo, before CityGenerator.generate is reached - so
        // this is where "no chunk generates against unloaded assets" is actually enforced. A
        // lifecycle event cannot enforce it on its own: reconcileDirtyCounter() above calls
        // cleanUp(), which calls AssetRegistries.reset(), from this very path (see cleanUp below),
        // and no further level-load event fires for a level that is already loaded. AssetRegistries
        // .load() latches, so from the second chunk on this costs one volatile read.
        AssetRegistries.load(world);
        ResourceKey<Level> type = world.getLevel().dimension();
        IDimensionInfo known = dimensionInfo.get(type);
        if (known != null) {
            return known;
        }
        PresetChoice choice = Config.getPresetChoiceForDimension(world.getLevel(), type);
        if (choice == null) {
            return null;
        }
        Preset preset = Presets.resolve(world.registryAccess(), choice.preset());
        if (choice.overridesJson().isPresent()) {
            // Fail-soft, unlike the preset id resolution above: the overrides JSON is either a
            // client-published payload PresetSelection.publish() encoded itself (trustworthy), or
            // saved data read back from disk - a corrupted/hand-edited save file must not crash
            // chunk generation. PresetSelection.restore() already validates before publishing, so
            // this guard is a backstop against corrupted saved data reaching this far, not the
            // primary defense.
            try {
                PresetRE re = PresetRE.CODEC.parse(JsonOps.INSTANCE,
                        JsonParser.parseString(choice.overridesJson().get())).getOrThrow();
                preset = Presets.applyOverrides(preset, re);
            } catch (Exception e) {
                Urbex.getLogger().error("Malformed Urbex preset overrides for dimension '{}'; " +
                        "generating with the un-overridden preset '{}'.", type.identifier(), choice.preset(), e);
            }
        }
        // Route 4 of the four that name a city style (see AssetRegistries.loadReachableCityStyles):
        // the alternative style can arrive as per-world override JSON rather than from a registry
        // entry, so the load-time sweep cannot see it - a player types an id into the ADVANCED
        // settings box and it rides into the world through UrbexData. Checked here instead, once per
        // dimension and before any chunk work, so an incomplete or missing style refuses the
        // pipeline naming the dimension rather than throwing from a worker on every chunk. This is
        // deliberately not fail-soft like the overrides parse above: a malformed payload can be
        // ignored and the un-overridden preset used, but a style that cannot resolve has no such
        // fallback - City.getCityStyle would simply hand null on to generation.
        AssetRegistries.requireCityStyle(world, preset.CITY_STYLE_ALTERNATIVE, type.identifier());
        // Built outside the map. Two threads may both build one for the same dimension the
        // first time a chunk is generated - the loser's is simply dropped, caches and all.
        IDimensionInfo diminfo = new DefaultDimensionInfo(world, preset, choice.worldStyles());
        IDimensionInfo raced = dimensionInfo.putIfAbsent(type, diminfo);
        return raced != null ? raced : diminfo;
    }

    /**
     * Drops the caches once per bump of {@link #globalDimensionInfoDirtyCounter}, and makes every
     * other caller wait while that happens.
     * <p>
     * This used to be a bare {@code if (global != mine) cleanUp();} on two volatile ints - a
     * check-then-act, run from the parallel worldgen worker pool, so two threads reading the counter
     * before either had written it back both called {@link #cleanUp()}. The first call of a session
     * always reconciles ({@code dimensionInfoDirtyCounter} starts at -1), so that was reachable on
     * any world's first chunks: the second reset landed while the first thread was already
     * generating against the registries it cleared. MultiChunk's city-style counter names the same
     * window, because a style resolved either side of a reset is two instances of one id.
     * <p>
     * The lock only bites when the counters differ; once they agree, the volatile read on the first
     * line returns and nothing synchronizes.
     * <p>
     * <b>What this does not cover, and what it costs.</b> A bump that arrives while generation is
     * already in flight still resets the registries underneath it, and the consequence is not
     * merely MultiChunk's split city-style vote - that is the mild version. {@link
     * AssetRegistries#reset()} sets the stuff-by-tag index to empty, and unlike every other registry
     * that index has no lazy rebuild: {@code Stuff.generateStuff} reads null for every tag, places
     * nothing, and the chunk is written and <em>saved</em> undecorated. That is the shipped defect
     * this whole task removed, reappearing one chunk at a time. It is reachable rather than
     * theoretical - {@code ClientEventHandlers.java:42-46} bumps the counter from
     * {@code ClientPlayConnectionEvents.DISCONNECT} on the client thread, which in single-player
     * fires while the integrated server is still draining in-flight generation. Nothing here
     * prevents it; {@code Stuff.generateStuff} logs loudly when it happens so it can no longer pass
     * quietly, and closing it properly means not tearing the registries down from a path that
     * generation shares.
     */
    private void reconcileDirtyCounter() {
        if (globalDimensionInfoDirtyCounter == dimensionInfoDirtyCounter) {
            return;
        }
        synchronized (this) {
            if (globalDimensionInfoDirtyCounter != dimensionInfoDirtyCounter) {
                cleanUp();
            }
        }
    }

    /**
     * Private and synchronized on purpose. {@link #reconcileDirtyCounter()} is the only caller and
     * already holds this monitor, and the design above depends on that staying true: a caller that
     * reached this without the monitor would be back to two threads resetting the registries at
     * once. Java monitors are reentrant, so the redundant acquisition costs nothing on the existing
     * path and is what keeps a future second caller from silently reopening the window.
     */
    private synchronized void cleanUp() {
        ServerEventHandlers.cleanUp();
        AssetRegistries.reset();
        dimensionInfo.clear();
        dimensionInfoDirtyCounter = globalDimensionInfoDirtyCounter;
    }
}

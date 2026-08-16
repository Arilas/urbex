package dev.krona.urbex.worldgen;

import com.google.gson.JsonParser;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.api.SiteSpec;
import dev.krona.urbex.api.UrbexSite;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetRoadGrid;
import dev.krona.urbex.config.Presets;
import dev.krona.urbex.plan.grid.GridRoadField;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.RetiredPresetKeyException;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The sites another mod has asked for, in the levels they were asked for.
 *
 * <p>Building a site is expensive - a preset resolution, a world-style field, a road field and a
 * fresh set of per-dimension caches - and a caller wants one per chunk. So it is built once per
 * {@code (level, spec id)} and kept, and that memo is what makes
 * {@link dev.krona.urbex.api.UrbexApi#site} cheap enough to call from a generation path.</p>
 *
 * <p>Owned by the {@link GenerationSession}, and so it lives exactly as long as the world does.
 * A static map keyed by dimension id is precisely the shape issue #125 removed from
 * {@code CityFeature}: a second world in the same JVM inherited the first world's entry, complete
 * with its seed, its assets and its caches.</p>
 *
 * <h2>What a site borrows and what it owns</h2>
 *
 * <p>It <em>owns</em> its planning context and its generator, which is what lets it use a different
 * preset and a different world style from the dimension it sits in, and its own
 * {@link DimensionCaches}, which is what stops its plans colliding with the level's own at the same
 * {@code ChunkCoord}.</p>
 *
 * <p>It <em>borrows</em> the world's compiled assets and block-tag epoch, and the level's deferred
 * task queue. Borrowing the queue is what makes a site's deferred work drain: the tick handler
 * drains the queue on the level's published runtime, and a queue of the site's own would fill up and
 * never be looked at.</p>
 *
 * <p>It borrows nothing from the level's <em>preset</em>, which is the point worth stating: a site
 * generates in a dimension where Urbex surface generation is switched off entirely. A vanilla
 * overworld with bunkers under it is a supported configuration, not an accident.</p>
 */
public final class SiteRuntimes {

    private final Map<ServerLevel, Map<Identifier, Site>> byLevel = new ConcurrentHashMap<>();

    /**
     * The site {@code spec} names in {@code level}, built on first use.
     *
     * <p>Keyed by {@code spec.id()} alone. A caller that hands two different specs under one id gets
     * the first one back, with a warning: the alternative is two sites racing to be the definition
     * of the same name, and a world whose bunkers depend on which chunk generated first.</p>
     */
    public UrbexSite site(ServerLevel level, SiteSpec spec, AssetSnapshot assets) {
        Map<Identifier, Site> sites = byLevel.computeIfAbsent(level, l -> new ConcurrentHashMap<>());
        Site known = sites.get(spec.id());
        if (known != null) {
            warnIfRedefined(known, spec);
            return known;
        }
        // Built outside the map. Construction reaches the registries and compiles a road field;
        // doing it inside computeIfAbsent would hold a bin lock across all of it, on a path several
        // worldgen workers enter at once.
        Site built = build(level, spec, assets);
        Site raced = sites.putIfAbsent(spec.id(), built);
        return raced != null ? raced : built;
    }

    /** Drops the sites of a level that is unloading. */
    public void unload(ServerLevel level) {
        byLevel.remove(level);
    }

    private static void warnIfRedefined(Site known, SiteSpec spec) {
        if (known.spec().equals(spec)) {
            return;
        }
        Urbex.getLogger().warn(
                "Two different Urbex site definitions were registered under the id '{}' in '{}'. The "
                        + "first one is what generates; the second is ignored. A site id is what "
                        + "makes a per-chunk lookup cheap, so it has to mean one thing.",
                spec.id(), known.level().dimension().identifier());
    }

    private static Site build(ServerLevel level, SiteSpec spec, AssetSnapshot assets) {
        long seed = level.getSeed();
        Preset preset = resolvePreset(level, spec);
        SiteBinding binding = new SiteBinding(spec.id(), spec.field(), spec.minY(), spec.maxY());
        DimensionCaches caches = new DimensionCaches(seed);
        PlanningContext planning = new PlanningContext(
                seed,
                level.dimension(),
                preset,
                assets,
                WorldStyleField.resolve(assets, seed, spec.worldStyles()),
                // The site's id is part of the road field's address, so two sites in one dimension
                // do not lay their streets on the same grid lines - and neither of them lands on the
                // dimension's own.
                new GridRoadField(seed, level.dimension().identifier() + "#" + spec.id(),
                        PresetRoadGrid.of(preset)),
                caches,
                shapeFor(level, spec),
                new SiteTerrain(new LevelTerrain(level, preset, caches), binding),
                binding);
        return new Site(spec, level, planning, new CityGenerator(planning, preset));
    }

    /**
     * The level's shape, clamped to the site's window.
     *
     * <p>This is the planning half of the window: floor counts already clamp against
     * {@code shape().maxY()}, so a site plans buildings that fit rather than buildings that get cut
     * off at the top by {@link ChunkBuffer}.</p>
     *
     * <p>The sea level is put <em>below</em> the window rather than carried over from the level.
     * A dimension's sea level is a fact about its surface; a site three hundred blocks under it is
     * not underwater, and every rule that fills below the water line would think it was. A preset
     * that wants a flooded site says so with its own {@code seaLevel}, which takes precedence over
     * this everywhere it is read.</p>
     */
    private static LevelShape shapeFor(ServerLevel level, SiteSpec spec) {
        LevelShape whole = LevelShape.of(level);
        return new LevelShape(
                Math.max(whole.minY(), spec.minY()),
                Math.min(whole.maxY(), spec.maxY()),
                Math.max(whole.minY(), spec.minY()) - 1);
    }

    /**
     * Resolves the site's preset and applies its overlay.
     *
     * <p>The same three-way rule {@code DimensionRuntime.create} uses for a dimension's overrides,
     * and deliberately so: a retired preset key is rethrown, because silently generating with an
     * un-overridden preset is how a caller ends up debugging a world that ignored half its
     * configuration; anything else malformed is logged and the base preset used, because a site that
     * refuses to build takes the caller's whole chunk down with it.</p>
     */
    private static Preset resolvePreset(ServerLevel level, SiteSpec spec) {
        Preset preset = Presets.resolve(level.registryAccess(), spec.preset());
        String overrides = spec.presetOverridesJson();
        if (overrides == null) {
            return preset;
        }
        try {
            return Presets.applyOverrides(preset,
                    PresetDefinition.parseOverrides(JsonParser.parseString(overrides)));
        } catch (RetiredPresetKeyException e) {
            throw e;
        } catch (Exception e) {
            Urbex.getLogger().error("Malformed preset overrides on Urbex site '{}'; generating with "
                    + "the un-overridden preset '{}'.", spec.id(), spec.preset(), e);
            return preset;
        }
    }

    /**
     * One site: the caller's spec, and the planning context and generator built from it.
     *
     * <p>Nothing here is per-chunk. The {@link DimensionRuntime} a generation needs is assembled at
     * {@link #fill} from the level's published one, so a site cannot hold a queue or a tag epoch
     * belonging to a world that has since been unloaded.</p>
     */
    private static final class Site implements UrbexSite {

        private final SiteSpec spec;
        private final ServerLevel level;
        private final PlanningContext planning;
        private final CityGenerator generator;
        /** So a lifecycle bug is one line in the log rather than one per chunk of a whole world. */
        private final AtomicBoolean reportedMissingRuntime = new AtomicBoolean();

        private Site(SiteSpec spec, ServerLevel level, PlanningContext planning,
                     CityGenerator generator) {
            this.spec = spec;
            this.level = level;
            this.planning = planning;
            this.generator = generator;
        }

        @Override
        public SiteSpec spec() {
            return spec;
        }

        ServerLevel level() {
            return level;
        }

        @Override
        public boolean fill(WorldGenRegion region, ChunkAccess chunk) {
            if (!spec.field().isSite(chunk.getPos().x(), chunk.getPos().z())) {
                return false;
            }
            DimensionRuntime published = GenerationSession.runtimeFor(region);
            if (published == null) {
                reportMissingRuntime();
                return false;
            }
            GenerationSession session = GenerationSession.current();
            TagEpoch tags = session == null ? null : session.tagEpoch();
            if (tags == null) {
                reportMissingRuntime();
                return false;
            }
            // The level's task queue and the world's tag epoch, paired with this site's own planning
            // and generator. Built per call rather than held: an unload or a reload republishes what
            // this reads, and a chunk starting now must generate against what is published now.
            generator.generate(
                    new DimensionRuntime(level, planning, generator, published.tasks(), tags),
                    region, chunk);
            return true;
        }

        private void reportMissingRuntime() {
            if (!reportedMissingRuntime.compareAndSet(false, true)) {
                return;
            }
            Urbex.getLogger().error(
                    "Urbex site '{}' was asked to fill a chunk of '{}', but that level has no "
                            + "published Urbex runtime and the world has compiled no assets. The "
                            + "chunk gets no site content. Either the level loaded without "
                            + "ServerLevelEvents.LOAD firing, or its runtime was retired while "
                            + "chunks were still generating.",
                    spec.id(), level.dimension().identifier());
        }
    }
}

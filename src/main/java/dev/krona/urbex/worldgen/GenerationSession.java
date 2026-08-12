package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.config.Presets;
import dev.krona.urbex.worldgen.lost.cityassets.AssetCompiler;
import dev.krona.urbex.worldgen.lost.cityassets.AssetDiagnostics;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;

import javax.annotation.Nullable;

/**
 * One running server's generation state: the levels it generates in, and the runtime each of them
 * generates with.
 *
 * <p>What this replaces is not a data structure but an ownership vacuum. {@code CityFeature} kept a
 * {@code Map<ResourceKey<Level>, IDimensionInfo>} on the registered feature instance - one object
 * for the whole process - and reconciled it against a {@code static volatile int} that the client
 * bumped on disconnect, the GUI bumped on world creation, and {@code /reload} bumped on the server
 * thread. Reconciliation ran from the generation path, so the reset it triggered could clear the
 * asset registries while a worker was midway through a chunk; the chunk was then written, saved and
 * never revisited, missing everything the emptied stuff index would have placed (issue #125).</p>
 *
 * <p>The session is opened at server start and closed at server stop, and each level's runtime is
 * published when that level loads and retired when it unloads. Two servers started in sequence in
 * one JVM get two sessions, so the second cannot see the first's runtimes whether or not anything
 * remembered to invalidate them.</p>
 *
 * <h2>The asset-load invariant</h2>
 *
 * <p><strong>No chunk generates against unloaded assets.</strong> Until this change the only thing
 * enforcing that was the generation path itself calling {@code AssetRegistries.load}, because that
 * same path was where the registries got reset. Enforcement now has two halves, and neither is a
 * best-effort:</p>
 * <ul>
 *   <li>{@link #load} loads the asset registries <em>before</em> it builds the level's runtime, from
 *       {@code ServerLevelEvents.LOAD}. Fabric raises that event from the {@code levels.put} inside
 *       {@code MinecraftServer.createLevels}, and {@code loadLevel} calls {@code createLevels()}
 *       before {@code prepareLevels()} - so it is ordered before "Preparing spawn area" generates
 *       its first chunk, which is the case a plain lifecycle hook previously failed.</li>
 *   <li>Generation reads a <em>published</em> runtime and does nothing at all without one. A level
 *       that never loaded has no runtime, and a runtime cannot exist without its assets having been
 *       loaded first. There is no longer a path that generates first and loads afterwards.</li>
 * </ul>
 * <p>Nothing resets the registries from a worker thread any more: the only resets left are at
 * session open and session close, both on the server thread, with no level loaded.</p>
 */
public final class GenerationSession {

    /**
     * The session the current server is running. Static because the generation path is entered from
     * a mixin holding nothing but a {@code WorldGenRegion} - but unlike the map it replaces, this is
     * one slot with one writer (server start/stop) rather than shared mutable state that generation
     * itself maintains.
     */
    private static volatile GenerationSession current;

    /**
     * The server this session belongs to. Held for identity only - it is compared by reference in
     * {@link #close} and never dereferenced - which is why the two methods below take it as an
     * {@code Object}: a {@code MinecraftServer} cannot be constructed in a test, and the identity
     * rule is one of the things that has to be tested.
     */
    @Nullable
    private final Object owner;
    private final RuntimeRepository<ServerLevel, DimensionRuntime> dimensions = new RuntimeRepository<>();
    /**
     * Compiled once, by the first level to load, and shared by every level in this world. The asset
     * registries are frozen when the world loads (issue #61), so there is nothing per-level about
     * what they compile to - and nothing a reload can change, which is why {@link #reload} leaves
     * this alone.
     */
    @Nullable
    private volatile AssetSnapshot assets;

    private GenerationSession(@Nullable Object owner) {
        this.owner = owner;
    }

    /** Opens the session for a starting server, closing whatever came before it. */
    public static GenerationSession open(@Nullable MinecraftServer server) {
        return openFor(server);
    }

    /** Closes the session belonging to {@code server}, if it is the current one. */
    public static void close(@Nullable MinecraftServer server) {
        closeFor(server);
    }

    /**
     * @see #open(MinecraftServer)
     *
     * <p>The asset registries are reset here rather than trusted to be empty. {@code load} latches,
     * so a previous world's assets left loaded would silently become the new world's - the failure
     * would be a world generating from another world's datapacks, with nothing logged.</p>
     */
    static synchronized GenerationSession openFor(@Nullable Object owner) {
        GenerationSession previous = current;
        if (previous != null) {
            previous.dimensions.close();
        }
        // The canonical-copy pools and the preset resolution cache are the two pieces of asset
        // state that are not in the snapshot, because they are deduplication caches rather than
        // compiled assets. They still have the session's lifetime: without this they would hold every
        // palette entry of every world this process ever loaded.
        PaletteEntry.clearPools();
        Presets.reset();
        GenerationSession session = new GenerationSession(owner);
        current = session;
        return session;
    }

    /**
     * @see #close(MinecraftServer)
     *
     * <p>Identity-checked so a server that stopped after another had already started cannot close
     * the running server's state - the same cross-lifetime confusion that keying runtimes by
     * dimension id alone produced.</p>
     */
    static synchronized void closeFor(@Nullable Object owner) {
        GenerationSession session = current;
        if (session == null || session.owner != owner) {
            return;
        }
        session.dimensions.close(GenerationSession::reportUnfinishedWork);
        PaletteEntry.clearPools();
        Presets.reset();
        current = null;
    }

    @Nullable
    public static GenerationSession current() {
        return current;
    }

    /**
     * The runtime for {@code level}, or {@code null} if that level has none - no session, an
     * unloaded level, or a level from a server that has already stopped.
     */
    @Nullable
    public static DimensionRuntime runtimeFor(ServerLevelAccessor level) {
        GenerationSession session = current;
        if (session == null) {
            return null;
        }
        return session.dimensions.find(level.getLevel());
    }

    /**
     * What {@code level} plans its chunks with, or {@code null} if Urbex does not generate there.
     *
     * <p>The direct replacement for {@code CityFeature.getDimensionInfo}, with the same nullable
     * contract and none of its side effects: it builds nothing, loads nothing and resets nothing.</p>
     */
    @Nullable
    public static IDimensionInfo planningFor(ServerLevelAccessor level) {
        DimensionRuntime runtime = runtimeFor(level);
        return runtime == null ? null : runtime.planning();
    }

    /**
     * Builds and publishes the runtime for a level that has just loaded.
     *
     * <p>The asset load on the first line is the invariant this class documents. Everything after it
     * reads the level; nothing before it does.</p>
     */
    public DimensionRuntime load(ServerLevel level) {
        DimensionRuntime runtime = DimensionRuntime.create(level, compileAssetsOnce(level));
        // Logged per level, at debug, because the ordering this line sits in is the invariant: in a
        // real server log every one of these lands before "Preparing spawn area", which is what a
        // unit test cannot show. (Verified on the digest run's dedicated server: overworld, nether
        // and end all publish before that line, and the overworld's before "Selecting global world
        // spawn", the other pre-tick generator.)
        Urbex.getLogger().debug("Published the Urbex runtime for '{}' ({})", level.dimension().identifier(),
                runtime.isEnabled() ? "generating" : "no preset for this dimension");
        return dimensions.publish(level, runtime);
    }

    /**
     * Compiles this world's assets, once, before the first level that needs them can generate.
     *
     * <p>The whole of the asset-load invariant, in one place: a runtime cannot be built without a
     * snapshot, a snapshot is only built here, and a pack that does not compile refuses the world
     * naming every problem at once rather than failing from a worldgen worker on the first chunk that
     * touches the broken file.</p>
     */
    private synchronized AssetSnapshot compileAssetsOnce(ServerLevel level) {
        AssetSnapshot known = assets;
        if (known != null) {
            return known;
        }
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        AssetSnapshot compiled = AssetCompiler.compile(level.registryAccess(), diagnostics);
        // Before publication, not after: a snapshot nobody validated is exactly the partially
        // compiled view this whole issue removes.
        diagnostics.throwIfAny();
        assets = compiled;
        return compiled;
    }

    /** This world's compiled assets, or null before the first level has loaded. */
    @Nullable
    public AssetSnapshot assets() {
        return assets;
    }

    /** Retires a level's runtime. Chunks already generating keep the runtime they captured. */
    public void unload(ServerLevel level) {
        DimensionRuntime retired = dimensions.retire(level);
        if (retired != null) {
            reportUnfinishedWork(level, retired);
        }
    }

    /**
     * Says what a retiring runtime still had queued, instead of dropping it silently.
     *
     * <p>Deferred level work dying with its level is the point - {@code GlobalTodo} kept its buckets
     * for the life of the process, so a task queued in one single-player world ran in the next world
     * with the same dimension id (issue #127). But "the work is gone" and "there was no work" are
     * different facts, and only one of them is a bug worth chasing.</p>
     */
    private static void reportUnfinishedWork(ServerLevel level, DimensionRuntime runtime) {
        int dropped = runtime.tasks().retire();
        if (dropped > 0) {
            Urbex.getLogger().info("Dropped {} deferred Urbex task(s) queued in '{}': the level is no "
                    + "longer loaded.", dropped, level.dimension().identifier());
        }
    }

    /**
     * Rebuilds every loaded level's runtime, for a {@code /reload}.
     *
     * <p>Rebuilding is what {@code /reload} can honestly do. The thirteen asset registries are
     * Fabric dynamic registries, loaded once with the world and frozen (issue #61), so an edited
     * building or palette JSON needs the world reopened whatever happens here - the old counter bump
     * cleared and re-resolved them anyway, which changed nothing an author could see and is exactly
     * how a running worker ended up reading an emptied stuff index. Block tags <em>do</em> reload,
     * and {@code CityGenerator} expands several of them into {@code BlockState} sets in its
     * constructor, so a fresh runtime per level is what makes an edited tag take effect.</p>
     *
     * <p>Each replacement is published whole; a chunk already generating finishes against the
     * runtime it captured.</p>
     */
    public void reload() {
        dimensions.republish(level -> DimensionRuntime.create(level, compileAssetsOnce(level)));
    }

    /** Who this session belongs to, for a caller that has to close it without holding that server. */
    @Nullable
    Object owner() {
        return owner;
    }

    boolean isClosed() {
        return dimensions.isClosed();
    }

    int loadedLevelCount() {
        return dimensions.size();
    }
}

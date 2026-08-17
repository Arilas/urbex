package dev.krona.urbex.setup;

import dev.krona.urbex.commands.ModCommands;
import dev.krona.urbex.worldgen.GenerationSession;
import dev.krona.urbex.worldgen.DimensionRuntime;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The server-side Fabric event wiring. The spawn-placement algorithm lives in
 * {@link SpawnPlacement}; this class only registers it. (Formerly {@code ForgeEventHandlers},
 * a name left over from the port.)
 */
public class ServerEventHandlers {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ModCommands.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> SpawnPlacement.onPlayerFirstJoin(handler.player));
        // Where a level's generation state begins and ends. Fabric raises LOAD from a
        // @WrapOperation on the levels.put call inside MinecraftServer.createLevels
        // (fabric-lifecycle-events-v1 4.1.3, MinecraftServerMixin#onLoadWorld), and
        // MinecraftServer.loadLevel calls createLevels() before prepareLevels() - so this runs
        // before "Preparing spawn area" generates its first chunk, and before the overworld's own
        // setInitialSpawn (which createLevels invokes after that same put, and which SpawnPlacement
        // hooks).
        //
        // GenerationSession.load resolves the asset registries before it builds the level's
        // runtime, which is what keeps the eager validation a load-time check - fail at load,
        // naming the file. It is also now the whole of the "no chunk generates against unloaded
        // assets" guarantee: generation reads a published runtime and does nothing without one, so
        // there is no longer a path that generates first and loads afterwards. The generation path
        // used to have to load the registries itself, because it was also where they were reset.
        ServerLevelEvents.LOAD.register((server, level) -> session(server).load(level));
        ServerLevelEvents.UNLOAD.register((server, level) -> {
            GenerationSession current = GenerationSession.current();
            if (current != null) {
                current.unload(level);
            }
        });
        // /reload, and what it can and cannot do for a datapack author.
        //
        // It cannot reload the thirteen asset registries. They are registered through Fabric's
        // DynamicRegistries (CustomRegistries.init), which puts them in
        // RegistryDataLoader.WORLDGEN_REGISTRIES - loaded once while the world loads and frozen.
        // Only ReloadableServerRegistries' own list (loot tables and friends) comes back on a
        // reload, so an edited building or palette JSON needs the world reopened whatever we do
        // here, exactly as a vanilla worldgen file does.
        //
        // Block tags are a different matter: those do reload, and they are the whole of what this
        // hook has to refresh. GenerationSession.reload re-captures them into the server's one
        // TagEpoch, which the next chunk to start reads and a chunk already generating does not.
        //
        // What it no longer does is rebuild anything else. It used to republish every loaded
        // level's runtime, because CityGenerator's constructor expanded urbex:needspoi and
        // urbex:foliage into BlockState sets, so a fresh generator was the only way to see an
        // edited tag - and a fresh generator brought a fresh road field, fresh heightmaps and an
        // empty plan cache with it, none of which a tag can affect (issue #128). Nor does it reset
        // the asset registries: they cannot change on a reload (see above), and clearing them from
        // the server thread while workers were generating is precisely how a chunk got written and
        // saved with an emptied stuff index behind it.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                GenerationSession current = GenerationSession.current();
                if (current != null) {
                    current.reload();
                }
            }
        });
        ServerTickEvents.END_LEVEL_TICK.register(ServerEventHandlers::onWorldTick);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            cleanUp();
            GenerationSession.open(server);
        });
        // STOPPED, not STOPPING, and the difference is a whole class of ruined chunk.
        //
        // STOPPING fires at the top of MinecraftServer.stopServer, while the chunk system is still
        // being drained - so a chunk finishing generation after it would find its level's runtime
        // already retired, get no city content at all, and be saved that way next to neighbours that
        // got theirs. Vanilla terrain in the middle of a city, permanently, from a clean quit.
        // STOPPED fires once the server has finished stopping, so nothing can still be generating.
        //
        // Everything retired here is per-session state that only the next server start reads, and
        // GenerationSession.open resets the asset registries itself, so nothing depends on this
        // having happened earlier.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            GenerationSession.close(server);
            cleanUp();
            Config.reset();
        });
    }

    /**
     * The session for {@code server}, opening one if the {@code SERVER_STARTING} registration above
     * did not (a level loading on a server that never announced its start - which nothing in
     * vanilla or Fabric does, but a level with no session would silently generate nothing at all).
     */
    private static GenerationSession session(MinecraftServer server) {
        GenerationSession current = GenerationSession.current();
        return current != null ? current : GenerationSession.open(server);
    }

    /**
     * Drains the level's own deferred work, if it has any.
     * <p>
     * Two lookups and a volatile read for a level with nothing queued, which is nearly every level
     * on nearly every tick. {@code GlobalTodo} allocated a {@code HashMap} copy and a
     * {@code HashSet} here twenty times a second per dimension to reach the same conclusion.
     */
    private static void onWorldTick(ServerLevel serverLevel) {
        DimensionRuntime runtime = GenerationSession.runtimeFor(serverLevel);
        if (runtime != null) {
            runtime.tasks().drain(serverLevel);
        }
    }

    public static void cleanUp() {
        Config.resetPresetCache();
        // Everything that used to be cleared here now lives on DimensionCaches and goes away with
        // the DimensionRuntime that owns it (GenerationSession retires those on unload and at
        // server stop), or on the AssetSnapshot and goes away with the session that compiled it -
        // which is what removed the last datapack-derived static map from here (issue #129).
        // Pending spawn corrections must not leak into the next world of this JVM session
        SpawnPlacement.reset();
    }
}

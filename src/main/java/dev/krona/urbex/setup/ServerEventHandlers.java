package dev.krona.urbex.setup;

import dev.krona.urbex.commands.ModCommands;
import dev.krona.urbex.worldgen.CityFeature;
import dev.krona.urbex.worldgen.GlobalTodo;
import dev.krona.urbex.worldgen.lost.City;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
        // Loading assets here, and not from the tick below, is what makes the eager validation in
        // AssetRegistries.load a load-time check again. Fabric raises this event from a
        // @WrapOperation on the levels.put call inside MinecraftServer.createLevels
        // (fabric-lifecycle-events-v1 4.1.3, MinecraftServerMixin#onLoadWorld), and
        // MinecraftServer.loadLevel calls createLevels() before prepareLevels() - so it fires
        // before "Preparing spawn area" generates its first chunk, and before the overworld's own
        // setInitialSpawn (which createLevels invokes after that same put, and which SpawnPlacement
        // hooks). It is not what guarantees loaded assets during generation, though: a level that is
        // already loaded never fires it again, so the guarantee lives on the generation path in
        // CityFeature.getDimensionInfo. This is the eager half - fail at load, naming the file.
        //
        // The two are not simply complementary, and it is worth being exact: on a fresh world load
        // this populates the registries, and then the very first getDimensionInfo of the session
        // reconciles its dirty counter (it starts at -1), calls cleanUp(), and throws all of it away
        // - reloading it on the same call. So every session resolves the ten registries twice. The
        // validation still happens first, which is the point of having this at all, but the second
        // resolve is not free and is not a fresh requirement being met.
        ServerLevelEvents.LOAD.register((server, level) -> AssetRegistries.load(level));
        // /reload, and what it can and cannot do for a datapack author.
        //
        // It cannot reload the thirteen asset registries. They are registered through Fabric's
        // DynamicRegistries (CustomRegistries.init), which puts them in
        // RegistryDataLoader.WORLDGEN_REGISTRIES - loaded once while the world loads and frozen.
        // Only ReloadableServerRegistries' own list (loot tables and friends) comes back on a
        // reload, so an edited building or palette JSON needs the world reopened whatever we do
        // here, exactly as a vanilla worldgen file does.
        //
        // Block tags are a different matter: those do reload, and Urbex reads several of them once
        // and caches the result. CityGenerator's constructor expands urbex:lights and
        // urbex:needspoi into BlockState sets and holds them for the lifetime of the
        // IDimensionInfo, so without this an edited tag kept generating against the pre-reload
        // membership, silently, until the world was reopened. Bumping the counter drops the
        // dimension info - and with it those sets and every compiled asset - so the next chunk
        // rebuilds from what the reload produced.
        //
        // Same in-flight hazard as every other bump of this counter (see
        // CityFeature.reconcileDirtyCounter): a chunk already generating can have the registries
        // reset underneath it and be saved undecorated. /reload is an explicit operator action, and
        // Stuff.generateStuff logs loudly if it happens, so this is the same trade the DISCONNECT
        // bump already makes rather than a new one.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                CityFeature.globalDimensionInfoDirtyCounter++;
            }
        });
        ServerTickEvents.END_LEVEL_TICK.register(ServerEventHandlers::onWorldTick);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> cleanUp());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            cleanUp();
            Config.reset();
        });
    }

    private static void onWorldTick(ServerLevel serverLevel) {
        GlobalTodo.get(serverLevel).executeAndClearTodo(serverLevel);
    }

    public static void cleanUp() {
        Config.resetPresetCache();
        // Everything that used to be cleared here now lives on DimensionCaches, and goes away with
        // the IDimensionInfo that owns it (CityFeature.cleanUp clears that map right after
        // calling us). Only the datapack-derived predefined maps are still global.
        City.cleanPredefinedCache();
        // Pending spawn corrections must not leak into the next world of this JVM session
        SpawnPlacement.reset();
    }
}

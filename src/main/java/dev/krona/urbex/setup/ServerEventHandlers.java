package dev.krona.urbex.setup;

import dev.krona.urbex.commands.ModCommands;
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

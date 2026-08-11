package dev.krona.urbex.setup;

import dev.krona.urbex.commands.ModCommands;
import dev.krona.urbex.worldgen.GlobalTodo;
import dev.krona.urbex.worldgen.lost.City;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
        ServerTickEvents.END_LEVEL_TICK.register(ServerEventHandlers::onWorldTick);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> cleanUp());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            cleanUp();
            Config.reset();
        });
    }

    private static void onWorldTick(ServerLevel serverLevel) {
        AssetRegistries.load(serverLevel);
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

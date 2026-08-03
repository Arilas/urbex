package dev.krona.urbex.varia;

import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;

/**
 * Fabric replacement for NeoForge's ServerLifecycleHooks.getCurrentServer().
 * The current server instance is captured by ServerLifecycleEvents in the mod initializer.
 */
public final class ServerAccess {

    private static MinecraftServer currentServer;

    private ServerAccess() {
    }

    public static void setServer(@Nullable MinecraftServer server) {
        currentServer = server;
    }

    @Nullable
    public static MinecraftServer getServer() {
        return currentServer;
    }
}

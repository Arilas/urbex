package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.LostChunkCharacteristics;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import dev.krona.urbex.varia.ServerAccess;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ErrorLogger {

    private static long lastReportTime = -1;

    public static void report(String message) {
        long time = System.currentTimeMillis();
        if (lastReportTime == -1 || lastReportTime < (time-10000)) {
            // Not reported before or too long ago
            lastReportTime = time;
            MinecraftServer server = ServerAccess.getServer();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
            }
        }
    }

    public static void logChunkInfo(int chunkX, int chunkZ, IDimensionInfo provider) {
        Logger logger = Urbex.getLogger();
        try {
            ChunkCoord coord = new ChunkCoord(provider.getType(), chunkX, chunkZ);
            logger.info("IsCity: " + BuildingInfo.isCityRaw(coord, provider, provider.getProfile()));
            LostChunkCharacteristics characteristics = BuildingInfo.getChunkCharacteristics(coord, provider);
            logger.info("    Level: " + characteristics.cityLevel);
            if (characteristics.multiBuilding != null) {
                logger.info("    Multibuilding: " + characteristics.multiBuilding.getName());
            }
            if (characteristics.buildingType != null) {
                logger.info("    Building: " + characteristics.buildingType.getName());
            }
            BuildingInfo info = BuildingInfo.getBuildingInfo(coord, provider);
            if (info.hasBuilding) {
                logger.info("        Floors: " + info.getNumFloors());
                logger.info("        Cellars: " + info.getNumCellars());
            }
        } catch (Exception e) {
            logger.warn("Error loging chunk info!", e);
        }
    }
}

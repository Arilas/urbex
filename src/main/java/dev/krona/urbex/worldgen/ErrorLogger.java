package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.CityField;
import dev.krona.urbex.worldgen.lost.ChunkCandidate;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

public class ErrorLogger {

    private static long lastReportTime = -1;

    /**
     * Tells whoever is playing that a chunk went wrong, at most once every ten seconds.
     * <p>
     * The server arrives as an argument, from the level whose runtime this generation belongs to,
     * rather than being read from a process-global slot. That slot was the last thing on any
     * worldgen or error-reporting path reaching {@code ServerAccess}, and reading it here meant a
     * chunk failing during a different server's lifetime reported to that server (issue #131).
     * <p>
     * The null guard is not defensive tidiness. This is called from the handler around chunk
     * generation, and the window it most wants to survive is shutdown - a worker finishing a chunk
     * after the server has stopped. Without it the error <em>handler</em> threw a
     * {@link NullPointerException} of its own, turning a reported chunk failure into a dead worldgen
     * worker and losing the message that was being reported (issue #56). The log line below is not a
     * fallback for the chat message; it is where the report has always actually belonged, and now
     * happens whether or not anyone is listening.
     */
    public static void report(@Nullable MinecraftServer server, String message) {
        long time = System.currentTimeMillis();
        if (lastReportTime != -1 && lastReportTime >= (time - 10000)) {
            return;
        }
        lastReportTime = time;
        Urbex.getLogger().error(message);
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
        }
    }

    public static void logChunkInfo(int chunkX, int chunkZ, PlanningContext provider) {
        Logger logger = Urbex.getLogger();
        try {
            ChunkCoord coord = new ChunkCoord(provider.dimension(), chunkX, chunkZ);
            logger.info("IsCity: " + CityField.isCityRaw(coord, provider, provider.preset()));
            ChunkCandidate candidate = ChunkPlan.getChunkCandidate(coord, provider);
            logger.info("    Level: " + candidate.cityLevel());
            if (candidate.multiBuilding() != null) {
                logger.info("    Multibuilding: " + candidate.multiBuilding().getName());
            }
            if (candidate.buildingType() != null) {
                logger.info("    Building: " + candidate.buildingType().getName());
            }
            ChunkPlan info = ChunkPlan.getChunkPlan(coord, provider);
            if (info.hasBuilding) {
                logger.info("        Floors: " + info.getNumFloors());
                logger.info("        Cellars: " + info.getNumCellars());
            }
        } catch (Exception e) {
            logger.warn("Error loging chunk info!", e);
        }
    }
}

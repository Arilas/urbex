package dev.krona.urbex.commands;

import dev.krona.urbex.worldgen.GenerationSession;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.krona.urbex.plan.RoadCell;
import dev.krona.urbex.plan.TertiarySegment;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.PrimaryBridgePlanner;
import dev.krona.urbex.worldgen.lost.Railway;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.world.level.WorldGenLevel;

import java.util.Optional;

public class CommandDebug implements Command<CommandSourceStack> {

    private static final CommandDebug CMD = new CommandDebug();

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("debug")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .executes(CMD);
    }


    /**
     * Sends one debug line to whoever ran the command.
     * <p>
     * Everything here used to go to {@code System.out}, so the player who typed {@code /urbex
     * debug} saw nothing at all and the answer landed in the server console - on a dedicated server,
     * a machine they may not have. Not {@code sendSuccess}: these are ~50 lines of diagnostics, and
     * broadcasting them to every other operator (which {@code sendSuccess(.., true)} does) is noise;
     * the sender asked, so the sender is told.
     */
    private static void line(CommandContext<CommandSourceStack> context, String text) {
        context.getSource().sendSystemMessage(Component.literal(text));
    }

    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos position = player.blockPosition();
        PlanningContext dimInfo = GenerationSession.planningFor((WorldGenLevel) player.level());
        if (dimInfo == null) {
            context.getSource().sendFailure(Component.literal("This dimension doesn't support Urbex!"));
            return 0;
        }
        ChunkCoord coord = new ChunkCoord(dimInfo.dimension(), position.getX() >> 4, position.getZ() >> 4);
        ChunkPlan info = ChunkPlan.getChunkPlan(coord, dimInfo);
        line(context, "profile = " + info.profile.getId());
        line(context, "buildingType = " + info.buildingType.getName());
        line(context, "floors = " + info.getNumFloors());
        line(context, "floorsBelowGround = " + info.cellars);
        line(context, "cityLevel = " + info.cityLevel);
        line(context, "cityGroundLevel = " + info.getCityGroundLevel());
        line(context, "isCity = " + info.isCity);
        line(context, "chunkX = " + info.coord.chunkX());
        line(context, "chunkZ = " + info.coord.chunkZ());
        CityStyle cityStyle = ChunkPlan.getChunkCandidate(info.coord, info.provider).cityStyle();
        // Name first, id after: the id is what you edit, the name is what the world-style picker
        // showed you, and a debug dump is the one place both are worth having side by side.
        line(context, "getCityStyle() = " + cityStyle.getDisplayName() + " (" + cityStyle.getName() + ")");
        line(context, "streetType = " + info.streetType);
        line(context, "ruinHeight = " + info.ruinHeight);
        line(context, "tunnel0 = " + info.isTunnel(0));
        line(context, "tunnel1 = " + info.isTunnel(1));
        line(context, "getHighwayXLevel() = " + info.getHighwayXLevel());
        line(context, "getHighwayZLevel() = " + info.getHighwayZLevel());

        Railway.RailChunkInfo railInfo = Railway.getRailChunkType(info.coord, info.provider, info.profile);
        line(context, "railInfo.getType() = " + railInfo.getType());
        line(context, "railInfo.getLevel() = " + railInfo.getLevel());
        line(context, "railInfo.getDirection() = " + railInfo.getDirection());
        line(context, "railInfo.getRails() = " + railInfo.getRails());

        int explosions = info.getExplosions().size();
        line(context, "explosions = " + explosions);

        ChunkHeightmap heightmap = dimInfo.heightmap(info.coord);
        line(context, "Chunk height (heightmap): " + heightmap.getHeight());

        line(context, "buildingMinFloors = " + dimInfo.preset().buildingMinFloors());
        line(context, "buildingMaxFloors = " + dimInfo.preset().buildingMaxFloors());
        line(context, "cityChance = " + dimInfo.preset().cityChance());
        line(context, "info.isOcean() = " + info.isOcean());

        printRoadDebug(context, info, dimInfo);
        return 1;
    }

    /**
     * Everything a person standing in a city would want to diagnose the street layout at their feet:
     * raw vs. effective road class (the two can disagree - an accepted multi-building cuts the road
     * without the raw field ever knowing), the block geometry the road field derived this chunk from,
     * and anything that can override or interrupt it (a planned bridge span, the conflict policy, the
     * containing multi-building). Printed only on command - never during ordinary generation - and
     * grouped under three headers so the three kinds of information don't run together.
     */
    private static void printRoadDebug(CommandContext<CommandSourceStack> context, ChunkPlan info, PlanningContext dimInfo) {
        RoadCell road = dimInfo.roadField().at(info.coord.chunkX(), info.coord.chunkZ());

        line(context, "-- roads: raw vs effective --");
        line(context, "road.raw = " + road.type());
        line(context, "road.effective = " + info.getEffectiveRoadType());
        line(context, "road.connections = north=" + road.north() + " south=" + road.south()
                + " west=" + road.west() + " east=" + road.east());

        line(context, "-- roads: block geometry --");
        line(context, "road.block = (" + road.blockX() + ", " + road.blockZ() + ")");
        line(context, "road.bounds = x[" + road.westX() + ".." + road.eastX()
                + "] z[" + road.northZ() + ".." + road.southZ() + "]");
        line(context, "road.density = " + road.density());
        line(context, "road.secondaryX = " + road.secondaryX());
        line(context, "road.secondaryZ = " + road.secondaryZ());
        TertiarySegment tertiary = road.tertiary();
        if (tertiary == null) {
            line(context, "road.tertiary = none");
        } else {
            line(context, "road.tertiary = origin=(" + tertiary.originX() + ", " + tertiary.originZ()
                    + ") direction=" + tertiary.direction() + " length=" + tertiary.length());
        }

        line(context, "-- roads: bridge / conflict --");
        Optional<PrimaryBridgePlanner.BridgeSpan> bridge = PrimaryBridgePlanner.spanAt(info.coord, dimInfo);
        if (bridge.isEmpty()) {
            line(context, "road.bridgeSpan = none");
        } else {
            PrimaryBridgePlanner.BridgeSpan span = bridge.get();
            line(context, "road.bridgeSpan = " + span.orientation()
                    + " (" + span.fromX() + ", " + span.fromZ() + ") -> (" + span.toX() + ", " + span.toZ() + ")");
        }
        line(context, "road.conflictPolicy = " + dimInfo.preset().multiBuildingStreetConflict());
        if (info.multiBuildingPos.isMulti() && info.multiBuilding != null) {
            line(context, "road.multiBuilding = " + info.multiBuilding.getName());
        } else {
            line(context, "road.multiBuilding = none");
        }
    }
}

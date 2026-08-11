package dev.krona.urbex.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.krona.urbex.plan.RoadCell;
import dev.krona.urbex.plan.TertiarySegment;
import dev.krona.urbex.setup.Registration;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import dev.krona.urbex.worldgen.lost.PrimaryBridgePlanner;
import dev.krona.urbex.worldgen.lost.Railway;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.world.level.WorldGenLevel;

import java.util.Optional;

public class CommandDebug implements Command<CommandSourceStack> {

    private static final CommandDebug CMD = new CommandDebug();

    public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
        return Commands.literal("debug")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .executes(CMD);
    }


    @SuppressWarnings("UseOfSystemOutOrSystemErr")
    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos position = player.blockPosition();
        IDimensionInfo dimInfo = Registration.cityFeature().getDimensionInfo((WorldGenLevel) player.level());
        if (dimInfo != null) {
            ChunkCoord coord = new ChunkCoord(dimInfo.getType(), position.getX() >> 4, position.getZ() >> 4);
            BuildingInfo info = BuildingInfo.getBuildingInfo(coord, dimInfo);
            System.out.println("profile = " + info.profile.getId());
//            System.out.println("provider.hasMansion = " + info.provider.hasMansion(info.chunkX, info.chunkZ));
            System.out.println("buildingType = " + info.buildingType.getName());
            System.out.println("floors = " + info.getNumFloors());
            System.out.println("floorsBelowGround = " + info.cellars);
            System.out.println("cityLevel = " + info.cityLevel);
            System.out.println("cityGroundLevel = " + info.getCityGroundLevel());
            System.out.println("isCity = " + info.isCity);
            System.out.println("chunkX = " + info.coord.chunkX());
            System.out.println("chunkZ = " + info.coord.chunkZ());
            System.out.println("getCityStyle() = " + BuildingInfo.getChunkCharacteristics(info.coord, info.provider).cityStyle.getName());
            System.out.println("streetType = " + info.streetType);
            System.out.println("ruinHeight = " + info.ruinHeight);
            System.out.println("tunnel0 = " + info.isTunnel(0));
            System.out.println("tunnel1 = " + info.isTunnel(1));
            System.out.println("getHighwayXLevel() = " + info.getHighwayXLevel());
            System.out.println("getHighwayZLevel() = " + info.getHighwayZLevel());

            Railway.RailChunkInfo railInfo = Railway.getRailChunkType(info.coord, info.provider, info.profile);
            System.out.println("railInfo.getType() = " + railInfo.getType());
            System.out.println("railInfo.getLevel() = " + railInfo.getLevel());
            System.out.println("railInfo.getDirection() = " + railInfo.getDirection());
            System.out.println("railInfo.getRails() = " + railInfo.getRails());

            int explosions = info.getExplosions().size();
            System.out.println("explosions = " + explosions);

            ChunkHeightmap heightmap = dimInfo.getFeature().getHeightmap(info.coord, (WorldGenLevel) player.level());
            System.out.println("Chunk height (heightmap): " + heightmap.getHeight());

            System.out.println("dimInfo.getProfile().BUILDING_MINFLOORS = " + dimInfo.getProfile().BUILDING_MINFLOORS);
            System.out.println("dimInfo.getProfile().BUILDING_MAXFLOORS = " + dimInfo.getProfile().BUILDING_MAXFLOORS);
            System.out.println("dimInfo.getProfile().CITY_CHANCE = " + dimInfo.getProfile().CITY_CHANCE);
            System.out.println("info.isOcean() = " + info.isOcean());

            printRoadDebug(info, dimInfo);
        }
        return 0;
    }

    /**
     * Everything a person standing in a city would want to diagnose the street layout at their feet:
     * raw vs. effective road class (the two can disagree - an accepted multi-building cuts the road
     * without the raw field ever knowing), the block geometry the road field derived this chunk from,
     * and anything that can override or interrupt it (a planned bridge span, the conflict policy, the
     * containing multi-building). Printed only on command - never during ordinary generation - and
     * grouped under three headers so the three kinds of information don't run together in the log.
     */
    @SuppressWarnings("UseOfSystemOutOrSystemErr")
    private static void printRoadDebug(BuildingInfo info, IDimensionInfo dimInfo) {
        RoadCell road = dimInfo.roadField().at(info.coord.chunkX(), info.coord.chunkZ());

        System.out.println("-- roads: raw vs effective --");
        System.out.println("road.raw = " + road.type());
        System.out.println("road.effective = " + info.getEffectiveRoadType());
        System.out.println("road.connections = north=" + road.north() + " south=" + road.south()
                + " west=" + road.west() + " east=" + road.east());

        System.out.println("-- roads: block geometry --");
        System.out.println("road.block = (" + road.blockX() + ", " + road.blockZ() + ")");
        System.out.println("road.bounds = x[" + road.westX() + ".." + road.eastX()
                + "] z[" + road.northZ() + ".." + road.southZ() + "]");
        System.out.println("road.density = " + road.density());
        System.out.println("road.secondaryX = " + road.secondaryX());
        System.out.println("road.secondaryZ = " + road.secondaryZ());
        TertiarySegment tertiary = road.tertiary();
        if (tertiary == null) {
            System.out.println("road.tertiary = none");
        } else {
            System.out.println("road.tertiary = origin=(" + tertiary.originX() + ", " + tertiary.originZ()
                    + ") direction=" + tertiary.direction() + " length=" + tertiary.length());
        }

        System.out.println("-- roads: bridge / conflict --");
        Optional<PrimaryBridgePlanner.BridgeSpan> bridge = PrimaryBridgePlanner.spanAt(info.coord, dimInfo);
        if (bridge.isEmpty()) {
            System.out.println("road.bridgeSpan = none");
        } else {
            PrimaryBridgePlanner.BridgeSpan span = bridge.get();
            System.out.println("road.bridgeSpan = " + span.orientation()
                    + " (" + span.fromX() + ", " + span.fromZ() + ") -> (" + span.toX() + ", " + span.toZ() + ")");
        }
        System.out.println("road.conflictPolicy = " + dimInfo.getProfile().MULTI_BUILDING_STREET_CONFLICT);
        if (info.multiBuildingPos.isMulti() && info.multiBuilding != null) {
            System.out.println("road.multiBuilding = " + info.multiBuilding.getName());
        } else {
            System.out.println("road.multiBuilding = none");
        }
    }
}

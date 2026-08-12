package dev.krona.urbex.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.krona.urbex.setup.Registration;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;

public class CommandMap implements Command<CommandSourceStack> {

    private static final CommandMap CMD = new CommandMap();

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("map")
                // Was LEVEL_ALL, which let any player print a 41x41 block of text into the server
                // console on demand - trivially spammable, and by the one audience that could not
                // read the result, since it went to stdout rather than to them. The output now goes
                // to the sender; the permission matches the other diagnostics.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(CMD);
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos position = player.blockPosition();
        IDimensionInfo dimInfo = Registration.cityFeature().getDimensionInfo((WorldGenLevel) player.level());
        if (dimInfo == null) {
            context.getSource().sendFailure(Component.literal("This dimension doesn't support Urbex!"));
            return 0;
        }
        {
            ChunkPos pos = ChunkPos.containing(position);
            for (int z = pos.z() - 20 ; z <= pos.z() + 20 ; z++) {
                StringBuilder buf = new StringBuilder();
                for (int x = pos.x() - 20 ; x <= pos.x() + 20 ; x++) {
                    ChunkCoord coord = new ChunkCoord(dimInfo.getType(), x, z);
                    BuildingInfo info = BuildingInfo.getBuildingInfo(coord, dimInfo);
                    if (info.isCity && info.hasBuilding) {
                        buf.append("B");
                    } else if (info.isCity) {
                        buf.append("+");
                    } else if (info.highwayXLevel >= 0 || info.highwayZLevel >= 0) {
                        buf.append(".");
                    } else {
                        buf.append(" ");
                    }
                }
                // To the sender, in a monospaced-friendly literal: the map is 41 characters of
                // aligned columns and only means anything to whoever asked for it.
                context.getSource().sendSystemMessage(Component.literal(buf.toString()));
            }
        }
        return 1;
    }
}

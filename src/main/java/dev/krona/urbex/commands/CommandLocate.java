package dev.krona.urbex.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.krona.urbex.setup.Registration;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.BuildingInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;

public class CommandLocate implements Command<CommandSourceStack> {

    private static final CommandLocate CMD = new CommandLocate();

    public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
        return Commands.literal("locate")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("name", IdentifierArgument.id()).suggests(
                        ModCommands.getBuildingSuggestionProvider()
                ).executes(CMD));
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Identifier name = context.getArgument("name", Identifier.class);

        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos start = player.blockPosition();

        ServerLevel level = (ServerLevel) player.level();
        IDimensionInfo dimInfo = Registration.lostCityFeature().getDimensionInfo(level);
        if (dimInfo == null) {
            context.getSource().sendFailure(Component.literal("This dimension doesn't support Urbex!"));
            return 0;
        }

        ChunkPos cp = ChunkPos.containing(start);
        // Abuse BlockPos as ChunkPos
        int cnt = 0;
        for (BlockPos.MutableBlockPos mpos : BlockPos.spiralAround(new BlockPos(cp.x(), 0, cp.z()), 30, Direction.EAST, Direction.SOUTH)) {
            BuildingInfo info = BuildingInfo.getBuildingInfo(new ChunkCoord(level.dimension(), mpos.getX(), mpos.getZ()), dimInfo);
            if (info != null && info.hasBuilding && info.getBuilding().getId().equals(name)) {
                context.getSource().sendSuccess(() -> Component.literal("Found at " + ((mpos.getX() << 4) + 8) + "," + info.groundLevel + "," + ((mpos.getZ() << 4) + 8)), false);
                cnt++;
                if (cnt > 6) {
                    break;
                }
            }
        }
        return 0;
    }
}

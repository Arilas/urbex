package dev.krona.urbex.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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

    /**
     * Chunks searched out from the sender when no radius is given.
     * <p>
     * Kept at what used to be hardcoded, because the cost is not the search - it is that every
     * chunk visited builds a full {@link BuildingInfo}, synchronously, on the server thread. At 30
     * that is ~3700 chunks and already a visible stall; {@link #MAX_RADIUS} is where it stops being
     * a stall and starts being a timeout.
     */
    public static final int DEFAULT_RADIUS = 30;
    public static final int MAX_RADIUS = 100;

    /** How many hits are reported before the search gives up looking for more. */
    private static final int MAX_HITS = 6;

    private static final CommandLocate CMD = new CommandLocate();

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("locate")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("name", IdentifierArgument.id()).suggests(
                                ModCommands.getBuildingSuggestionProvider())
                        .executes(CMD)
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                                .executes(CMD)));
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Identifier name = context.getArgument("name", Identifier.class);
        int radius = ModCommands.optionalRadius(context, DEFAULT_RADIUS);

        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos start = player.blockPosition();

        ServerLevel level = (ServerLevel) player.level();
        IDimensionInfo dimInfo = Registration.cityFeature().getDimensionInfo(level);
        if (dimInfo == null) {
            context.getSource().sendFailure(Component.literal("This dimension doesn't support Urbex!"));
            return 0;
        }

        ChunkPos cp = ChunkPos.containing(start);
        // Abuse BlockPos as ChunkPos
        int cnt = 0;
        for (BlockPos.MutableBlockPos mpos : BlockPos.spiralAround(new BlockPos(cp.x(), 0, cp.z()), radius, Direction.EAST, Direction.SOUTH)) {
            BuildingInfo info = BuildingInfo.getBuildingInfo(new ChunkCoord(level.dimension(), mpos.getX(), mpos.getZ()), dimInfo);
            if (info != null && info.hasBuilding && info.getBuilding().getId().equals(name)) {
                context.getSource().sendSuccess(() -> Component.literal("Found at " + ((mpos.getX() << 4) + 8) + "," + info.groundLevel + "," + ((mpos.getZ() << 4) + 8)), false);
                cnt++;
                if (cnt >= MAX_HITS) {
                    break;
                }
            }
        }
        if (cnt == 0) {
            // Said out loud, because silence here reads as "there is no such building" when it may
            // equally mean "not within <radius> chunks" or "that id is not in this world's pack".
            context.getSource().sendFailure(Component.literal("No '" + name + "' within " + radius
                    + " chunks. Try a larger radius, or check the id is one this world's datapacks define."));
        }
        // The count, so /execute if and command blocks can branch on whether anything was found.
        return cnt;
    }
}

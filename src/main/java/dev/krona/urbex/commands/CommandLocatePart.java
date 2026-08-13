package dev.krona.urbex.commands;

import dev.krona.urbex.worldgen.GenerationSession;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.krona.urbex.editor.EditModeData;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.PlanningContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import net.minecraft.network.chat.Component;

public class CommandLocatePart implements Command<CommandSourceStack> {

    /** How many hits are reported before the search gives up looking for more. */
    private static final int MAX_HITS = 6;

    private static final CommandLocatePart CMD = new CommandLocatePart();

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("locatepart")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("name", IdentifierArgument.id()).suggests(
                                ModCommands.getPartSuggestionProvider())
                        .executes(CMD)
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, CommandLocate.MAX_RADIUS))
                                .executes(CMD)));
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Identifier name = context.getArgument("name", Identifier.class);
        int radius = ModCommands.optionalRadius(context, CommandLocate.DEFAULT_RADIUS);

        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos start = player.blockPosition();

        ServerLevel level = (ServerLevel) player.level();
        PlanningContext dimInfo = GenerationSession.planningFor(level);
        if (dimInfo == null) {
            context.getSource().sendFailure(Component.literal("This dimension doesn't support Urbex!"));
            return 0;
        }
        if (!dimInfo.preset().editMode()) {
            context.getSource().sendFailure(Component.literal("This world was not created with edit mode enabled. This command is not possible!"));
            return 0;
        }

        ChunkPos cp = ChunkPos.containing(start);
        // Abuse BlockPos as ChunkPos
        int cnt = 0;
        outer:
        for (BlockPos.MutableBlockPos mpos : BlockPos.spiralAround(new BlockPos(cp.x(), 0, cp.z()), radius, Direction.EAST, Direction.SOUTH)) {
            List<EditModeData.PartData> data = EditModeData.getData().getPartData(new ChunkCoord(level.dimension(), mpos.getX(), mpos.getZ()));
            for (EditModeData.PartData pd : data) {
                // Both sides are the fully-qualified id: EditModeData stores what
                // CityGenerator.generatePart recorded, which is BuildingPart.getName() - the
                // Identifier's toString - and the argument is an Identifier. (This comparison used
                // to be against a bare stored path and so never matched; it is CommandEditPart's
                // "always qualified" note that now holds on both sides.)
                if (pd.partName().equals(name.toString())) {
                    context.getSource().sendSuccess(() -> Component.literal("Found at " + ((mpos.getX() << 4) + 8) + "," + pd.y() + "," + ((mpos.getZ() << 4) + 8)), false);
                    cnt++;
                    // Labelled: the bare break here left the spiral running, so the cap bounded the
                    // reports from one chunk rather than the search.
                    if (cnt >= MAX_HITS) {
                        break outer;
                    }
                }
            }
        }
        if (cnt == 0) {
            context.getSource().sendFailure(Component.literal("No '" + name + "' recorded within "
                    + radius + " chunks. Only parts this world has actually generated are recorded, "
                    + "so a part that exists in the pack but nowhere nearby will not be found."));
        }
        return cnt;
    }
}

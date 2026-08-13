package dev.krona.urbex.commands;

import dev.krona.urbex.worldgen.GenerationSession;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.krona.urbex.editor.EditModeData;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.PlanningContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import net.minecraft.network.chat.Component;

public class CommandListParts implements Command<CommandSourceStack> {

    private static final CommandListParts CMD = new CommandListParts();

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("listparts")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .executes(CMD);
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
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
        List<EditModeData.PartData> data = EditModeData.getData().getPartData(new ChunkCoord(level.dimension(), cp.x(), cp.z()));
        for (EditModeData.PartData pd : data) {
            context.getSource().sendSuccess(() -> Component.literal("Found '" + pd.partName() + "' at " + pd.y()), false);
        }
        if (data.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No generated parts recorded in this chunk."));
        }
        // The count, so a command block or /execute can branch on it. (This lists the parts of the
        // chunk the sender is standing in, not the part registry - see #72.)
        return data.size();
    }
}

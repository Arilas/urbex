package dev.krona.urbex.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.krona.urbex.editor.EditModeData;
import dev.krona.urbex.editor.Editor;
import dev.krona.urbex.setup.Registration;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;

public class CommandResumeEdit implements Command<CommandSourceStack> {

    private static final CommandResumeEdit CMD = new CommandResumeEdit();

    public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
        return Commands.literal("resumeedit")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS)).executes(CMD);
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos start = player.blockPosition();

        ServerLevel level = (ServerLevel) player.level();
        IDimensionInfo dimInfo = Registration.lostCityFeature().getDimensionInfo(level);
        if (dimInfo == null) {
            context.getSource().sendFailure(Component.literal("This dimension doesn't support Urbex!"));
            return 0;
        }
        if (!dimInfo.getProfile().EDITMODE) {
            context.getSource().sendFailure(Component.literal("This world was not created with edit mode enabled. This command is not possible!"));
            return 0;
        }

        ChunkPos cp = ChunkPos.containing(start);
        for (EditModeData.PartData data : EditModeData.getData().getPartData(new ChunkCoord(level.dimension(), cp.x(), cp.z()))) {
            BuildingPart part = AssetRegistries.PARTS.get(level, data.partName());
            if (part == null) {
                context.getSource().sendFailure(Component.literal("Unknown part '" + data.partName() + "' in this chunk!"));
                return 0;
            }
            if (data.y() <= start.getY() && start.getY() < data.y() + part.getSliceCount()) {
                context.getSource().sendSuccess(() -> Component.literal("Start editing part '" + data.partName() + "'!"), false);
                Editor.startEditing(part, player, new BlockPos(start.getX(), data.y(), start.getZ()), level, dimInfo, false);
                return 0;
            }
        }

        context.getSource().sendFailure(Component.literal("Could not find a part to edit in this chunk!"));
        return 0;
    }
}

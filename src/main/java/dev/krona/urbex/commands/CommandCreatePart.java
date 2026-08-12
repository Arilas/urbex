package dev.krona.urbex.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.krona.urbex.editor.Editor;
import dev.krona.urbex.setup.Registration;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class CommandCreatePart implements Command<CommandSourceStack> {

    private static final CommandCreatePart CMD = new CommandCreatePart();

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("createpart")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("name", IdentifierArgument.id())
                        .suggests(ModCommands.getPartSuggestionProvider())
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(CMD)));
    }

    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Identifier name = context.getArgument("name", Identifier.class);
        BuildingPart part = null;
        try {
            part = AssetRegistries.PARTS.get(context.getSource().getLevel(), name);
        } catch (Exception e) {
            part = null;
        }
        if (part == null) {
            context.getSource().sendFailure(Component.literal("Error finding part '" + name + "'!").withStyle(ChatFormatting.RED));
            return 0;
        }

        ServerPlayer player = context.getSource().getPlayerOrException();
        WorldCoordinates start = context.getArgument("pos", WorldCoordinates.class);


        ServerLevel level = (ServerLevel) player.level();
        IDimensionInfo dimInfo = Registration.cityFeature().getDimensionInfo(level);
        if (dimInfo == null) {
            context.getSource().sendFailure(Component.literal("This dimension doesn't support Urbex!"));
            return 0;
        }

        Editor.startEditing(part, player, start.getBlockPos(context.getSource()), level, dimInfo, true);

        return Command.SINGLE_SUCCESS;
    }

}

package dev.krona.urbex.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.lost.cityassets.AssetRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.Building;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

import javax.annotation.Nonnull;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ModCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> commands = dispatcher.register(
                Commands.literal(Urbex.MODID)
                        .then(CommandCreateBuilding.register())
                        .then(CommandDebug.register())
                        .then(CommandStats.register())
                        .then(CommandMap.register())
                        .then(CommandSavePreset.register())
                        .then(CommandCreatePart.register())
                        .then(CommandLocatePart.register())
                        .then(CommandLocate.register())
                        .then(CommandEditPart.register())
                        .then(CommandResumeEdit.register())
                        .then(CommandListParts.register())
                        .then(CommandExportPart.register())
                        .then(CommandDigest.register())
        );

        dispatcher.register(Commands.literal("ubx").redirect(commands));
        // @todo 1.21
//        ResetChunksCommand.register(dispatcher);
    }

    /**
     * The {@code radius} argument, or {@code fallback} when the sender left it off.
     * <p>
     * Brigadier binds one {@link com.mojang.brigadier.Command} to both the two- and three-argument
     * forms of a command, and asking for an argument the parsed node never supplied throws
     * {@code IllegalArgumentException} out of the executor. Checking the parsed nodes is how a
     * command distinguishes the two forms without duplicating its body.
     */
    static int optionalRadius(CommandContext<CommandSourceStack> context, int fallback) {
        boolean given = context.getNodes().stream()
                .anyMatch(node -> "radius".equals(node.getNode().getName()));
        return given ? IntegerArgumentType.getInteger(context, "radius") : fallback;
    }

    @Nonnull
    static SuggestionProvider<CommandSourceStack> getPartSuggestionProvider() {
        return (context, builder) -> {
            Stream<BuildingPart> stream = StreamSupport.stream(AssetRegistries.PARTS.getIterable().spliterator(), false);
            return SharedSuggestionProvider.suggest(stream.map(b -> b.getId().toString()), builder);
        };
    }

    @Nonnull
    static SuggestionProvider<CommandSourceStack> getBuildingSuggestionProvider() {
        return (context, builder) -> {
            Stream<Building> stream = StreamSupport.stream(AssetRegistries.BUILDINGS.getIterable().spliterator(), false);
            return SharedSuggestionProvider.suggest(stream.map(b -> b.getId().toString()), builder);
        };
    }
}

package dev.krona.urbex.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.lost.cityassets.AssetCompiler;
import dev.krona.urbex.worldgen.lost.cityassets.AssetDiagnostics;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Re-resolves every registered Urbex asset and reports what is wrong, changing nothing.
 * <p>
 * For a datapack author, who otherwise learns about a broken reference either from a world that
 * refuses to load or - before load-time resolution existed - from a chunk that generated wrong
 * (issue #56). Every problem this can find has already refused the world at load, so on a running
 * world it reports nothing; that is the answer worth being able to ask for after installing a pack.
 * <p>
 * It cannot see an edit made since the world opened: the thirteen asset registries are Fabric
 * dynamic registries, loaded once with the world and frozen, so an edited file needs the world
 * reopened exactly as a vanilla worldgen file does.
 */
public class CommandValidate implements Command<CommandSourceStack> {

    private static final CommandValidate CMD = new CommandValidate();

    /** How many problems to put in chat before pointing at the log. */
    private static final int CHAT_LIMIT = 10;

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("validate")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(CMD);
    }

    @Override
    public int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        // A throwaway compile, not the live snapshot: the running world's chunks are generating
        // against theirs, and asking what is wrong must not replace it. Compiling a second one costs
        // a few milliseconds and leaves nothing behind (issue #128).
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        AssetCompiler.compile(source.registryAccess(), diagnostics);

        if (diagnostics.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Urbex assets: no problems found.")
                    .withStyle(ChatFormatting.GREEN), false);
            return Command.SINGLE_SUCCESS;
        }

        // The whole report goes to the log whatever its size; chat gets the head of it. A pack with
        // fifty broken files would otherwise scroll the useful part of the session away.
        Urbex.getLogger().error(diagnostics.format(
                diagnostics.size() + " Urbex asset problem(s) found by /urbex validate:"));
        source.sendFailure(Component.literal(diagnostics.size() + " Urbex asset problem(s):")
                .withStyle(ChatFormatting.RED));
        diagnostics.problems().stream().limit(CHAT_LIMIT).forEach(problem ->
                source.sendSystemMessage(Component.literal("  " + problem).withStyle(ChatFormatting.RED)));
        int remaining = diagnostics.size() - CHAT_LIMIT;
        if (remaining > 0) {
            source.sendSystemMessage(Component.literal("  ... and " + remaining
                    + " more; the full list is in the server log.").withStyle(ChatFormatting.GRAY));
        }
        return 0;
    }
}

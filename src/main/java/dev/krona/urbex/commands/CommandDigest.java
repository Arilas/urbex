package dev.krona.urbex.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import dev.krona.urbex.worldgen.ChunkDriver;
import dev.krona.urbex.worldgen.DigestRunner;

/**
 * Generates a square of chunks in a chosen order and prints two hashes of the result.
 * <p>
 * {@code DRIVERDIGEST} is the acceptance signal: it covers the positions this mod wrote
 * <em>through {@link ChunkDriver}</em>, and nothing else. Generating the same seed in two
 * different orders must produce the same value.
 * <p>
 * It does <em>not</em> cover writes that bypass the driver and go straight to the world - today
 * the vine generation in {@code ChunkFixer} and the post-todo callbacks in
 * {@code LostCityTerrainFeature}, both of which call {@code setBlock} on the level. Those blocks
 * are never recorded, so order-dependence on those paths cannot be detected by this command at
 * all. Treat a matching DRIVERDIGEST as evidence about the driven paths only. Issue #20 tracks a
 * known order-dependence on the vine path that this command is structurally blind to.
 * <p>
 * {@code DIGEST} hashes every non-air block in every chunk. It is kept as a loose tripwire only.
 * It cannot be used as an acceptance signal, because it also hashes vanilla's ore blobs and
 * underwater vegetation, and those bleed across chunk borders: the same seed in the same order,
 * in a dimension with no Urbex profile at all, produces two different values on two runs.
 * <p>
 * The machinery lives in {@link DigestRunner} so the headless digest check can run it without a
 * command source.
 */
public class CommandDigest implements Command<CommandSourceStack> {

    private static final CommandDigest CMD = new CommandDigest();

    public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
        return Commands.literal("digest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                        .then(Commands.argument("order", StringArgumentType.word())
                                .then(Commands.argument("offset", IntegerArgumentType.integer(-100000, 100000))
                                        .executes(CMD))));
    }

    @Override
    public int run(CommandContext<CommandSourceStack> context) {
        int radius = IntegerArgumentType.getInteger(context, "radius");
        String order = StringArgumentType.getString(context, "order");
        int offset = IntegerArgumentType.getInteger(context, "offset");
        ServerLevel level = context.getSource().getLevel();

        DigestRunner.Result result;
        try {
            result = DigestRunner.run(level, radius, order, offset);
        } catch (IllegalArgumentException e) {
            context.getSource().sendFailure(Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }

        String driverLine = result.driverLine(order, offset);
        String line = result.fullLine(order, offset);
        context.getSource().sendSuccess(() -> Component.literal(driverLine).withStyle(ChatFormatting.GREEN), true);
        context.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.YELLOW), true);
        System.out.println(driverLine);     // so headless runs can grep stdout
        System.out.println(line);
        return 1;
    }
}

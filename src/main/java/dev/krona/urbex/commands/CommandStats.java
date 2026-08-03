package dev.krona.urbex.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.krona.urbex.setup.Registration;
import dev.krona.urbex.varia.Statistics;
import dev.krona.urbex.worldgen.IDimensionInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class CommandStats implements Command<CommandSourceStack> {

    private static final CommandStats CMD = new CommandStats();

    public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
        return Commands.literal("stats")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .executes(CMD);
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // The source's own level, not the player's: this has to work from the server console too,
        // which is where the generation timings are actually read from during a headless run.
        IDimensionInfo dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo(context.getSource().getLevel());
        if (dimInfo != null) {
            Statistics statistics = dimInfo.getFeature().getStatistics();
            float averageTime = statistics.getAverageTime();
            long minTime = statistics.getMinTime();
            long maxTime = statistics.getMaxTime();
            context.getSource().sendSuccess(() -> Component.literal("Average time: " + averageTime + "ms").withStyle(ChatFormatting.YELLOW), false);
            context.getSource().sendSuccess(() -> Component.literal("Min time: " + minTime + "ms").withStyle(ChatFormatting.YELLOW), false);
            context.getSource().sendSuccess(() -> Component.literal("Max time: " + maxTime + "ms").withStyle(ChatFormatting.YELLOW), false);
        } else {
            context.getSource().sendFailure(Component.literal("No dimension info found!").withStyle(ChatFormatting.RED));
        }
        return 0;
    }
}

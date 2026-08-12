package dev.krona.urbex.commands;

import dev.krona.urbex.worldgen.GenerationSession;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exports the sender dimension's resolved (extends chain flattened, overrides applied) preset to a
 *  standalone JSON file, for sharing or as a starting point for a new datapack preset. */
public class CommandSavePreset implements Command<CommandSourceStack> {

    private static final CommandSavePreset CMD = new CommandSavePreset();

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("savepreset")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .executes(CMD);
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // The source's own level, not the player's: this has to work from the server console too.
        IDimensionInfo dimInfo = GenerationSession.planningFor(context.getSource().getLevel());
        if (dimInfo == null) {
            context.getSource().sendFailure(Component.literal("No dimension info found!").withStyle(ChatFormatting.RED));
            return 0;
        }
        Preset preset = dimInfo.getProfile();
        JsonElement json = PresetRE.CODEC.encodeStart(JsonOps.INSTANCE, preset.toRE()).getOrThrow();
        Path out = FabricLoader.getInstance().getGameDir().resolve("urbex-export");
        Path target = out.resolve(preset.getId().getPath() + ".json");
        try {
            // The preset id's path can itself contain '/' (a datapack preset registered under a
            // subdirectory), so the target's own parent - not just the export root - must exist.
            Files.createDirectories(target.getParent());
            Files.writeString(target, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json));
        } catch (IOException e) {
            context.getSource().sendFailure(Component.literal("Error saving preset '" + preset.getId() + "'!").withStyle(ChatFormatting.RED));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Saved preset to '" + target + "'!").withStyle(ChatFormatting.GREEN), true);
        return Command.SINGLE_SUCCESS;
    }
}

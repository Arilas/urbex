package dev.krona.urbex.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.Presets;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.network.chat.Component;

/** Exports a resolved (parent chain flattened) preset to a standalone JSON file, for sharing or
 *  as a starting point for a new datapack preset. */
public class CommandSaveProfile implements Command<CommandSourceStack> {

    private static final CommandSaveProfile CMD = new CommandSaveProfile();

    public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
        return Commands.literal("saveprofile")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("preset", StringArgumentType.word())
                    .executes(CMD));
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = context.getArgument("preset", String.class);
        Identifier id = DataTools.fromName(name);
        Preset preset;
        try {
            preset = Presets.resolve(context.getSource().registryAccess(), id);
        } catch (IllegalStateException e) {
            context.getSource().sendSuccess(() -> Component.literal(ChatFormatting.RED + "Could not find preset '" + name + "'!"), true);
            return 0;
        }
        PresetRE re = preset.toRE();
        JsonObject jsonObject = PresetRE.CODEC.encodeStart(JsonOps.INSTANCE, re).getOrThrow().getAsJsonObject();
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Path target;
        try {
            Path profileDir = FabricLoader.getInstance().getConfigDir().resolve("urbex").resolve("profiles");
            target = ExportPath.resolve(profileDir, name);
            Files.createDirectories(profileDir);
            Files.writeString(target, gson.toJson(jsonObject));
        } catch (IllegalArgumentException | IOException e) {
            context.getSource().sendSuccess(() -> Component.literal(ChatFormatting.RED + "Error saving preset '" + name + "'!"), true);
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(ChatFormatting.GREEN + "Saved preset to '" + target + "'!"), true);
        return 0;
    }
}

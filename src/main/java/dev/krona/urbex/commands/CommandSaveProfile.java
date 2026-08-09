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
import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.config.LostCityProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.network.chat.Component;

public class CommandSaveProfile implements Command<CommandSourceStack> {

    private static final CommandSaveProfile CMD = new CommandSaveProfile();

    public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
        return Commands.literal("saveprofile")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("profile", StringArgumentType.word())
                    .executes(CMD));
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = context.getArgument("profile", String.class);
        LostCityProfile profile = ProfileSetup.STANDARD_PROFILES.get(name);
        if (profile == null) {
            context.getSource().sendSuccess(() -> Component.literal(ChatFormatting.RED + "Could not find profile '" + name + "'!"), true);
            return 0;
        }
        JsonObject jsonObject = profile.toJson(false);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Path target;
        try {
            Path profileDir = FabricLoader.getInstance().getConfigDir().resolve("urbex").resolve("profiles");
            target = ExportPath.resolve(profileDir, name);
            Files.createDirectories(profileDir);
            Files.writeString(target, gson.toJson(jsonObject));
        } catch (IllegalArgumentException | IOException e) {
            context.getSource().sendSuccess(() -> Component.literal(ChatFormatting.RED + "Error saving profile '" + name + "'!"), true);
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal(ChatFormatting.GREEN + "Saved profile to '" + target + "'!"), true);
        return 0;
    }
}

package dev.krona.urbex.commands;

import dev.krona.urbex.worldgen.GenerationSession;
import com.google.gson.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.editor.EditorInfo;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Tools;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.cityassets.BuildingPart;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class CommandExportPart implements Command<CommandSourceStack> {

    private static final CommandExportPart CMD = new CommandExportPart();

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("exportpart")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("name", StringArgumentType.word()).executes(CMD));
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String filename = context.getArgument("name", String.class);
        ServerPlayer player = context.getSource().getPlayerOrException();
        EditorInfo editorInfo = EditorInfo.getEditorInfo(player.getUUID());
        if (editorInfo == null) {
            context.getSource().sendFailure(Component.literal("You are not editing anything!").withStyle(ChatFormatting.RED));
            return 0;
        }

        BlockPos start = editorInfo.getBottomLocation();
        ServerLevel level = (ServerLevel) player.level();
        // The provider first: the part is looked up in this level's compiled assets (issue #128).
        PlanningContext dimInfo = GenerationSession.planningFor(level);
        if (dimInfo == null) {
            context.getSource().sendFailure(Component.literal("Urbex does not generate in this dimension!").withStyle(ChatFormatting.RED));
            return 0;
        }

        // editorInfo.getPartName() is Editor.startEditing's recorded part id - always qualified.
        BuildingPart part = dimInfo.assets().parts().get(DataTools.fromName(editorInfo.getPartName()));
        if (part == null) {
            context.getSource().sendFailure(Component.literal("Error finding part '" + editorInfo.getPartName() + "'!").withStyle(ChatFormatting.RED));
            return 0;
        }

        ChunkCoord coord = new ChunkCoord(dimInfo.dimension(), start.getX() >> 4, start.getZ() >> 4);
        ChunkPlan info = ChunkPlan.getChunkPlan(coord, dimInfo);
        CompiledPalette palette = info.getCompiledPalette();
        Palette partPalette = part.getLocalPalette();
        Palette buildingPalette = info.getBuilding().getLocalPalette();
        if (partPalette != null || buildingPalette != null) {
            palette = new CompiledPalette(palette, partPalette, buildingPalette);
        }

        Map<BlockState, Character> unknowns = new HashMap<>();

        List<List<String>> slices = new ArrayList<>();
        Set<Character> usedCharacters = new HashSet<>(palette.getCharacters());
        StringBuilder chars = new StringBuilder("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;:'<>,.?/`~");

        // Add various unicode characters
        for (int i = 0x0370; i < 0x0400; i++) {
            chars.append((char) i);
        }
        for (int i = 0x0400; i < 0x0500; i++) {
            chars.append((char) i);
        }
        String possibleChars = chars.toString();

        for (int y = 0 ; y < part.getSliceCount() ; y++) {
            List<String> yslice = new ArrayList<>();
            for (int z = 0; z < part.getZSize(); z++) {
                StringBuilder b = new StringBuilder();
                for (int x = 0; x < part.getXSize(); x++) {
                    BlockPos pos = info.getRelativePos(x, start.getY()+y, z);
                    BlockState state = level.getBlockState(pos);
                    Character c = editorInfo.getPaleteEntry(state);
                    if (c == null) {
                        c = unknowns.get(state);
                    }
                    if (c == null) {
                        // New state!
                        // Find a character that is not yet used
                        for (int i = 0 ; i < possibleChars.length() ; i++) {
                            char cc = possibleChars.charAt(i);
                            if (!usedCharacters.contains(cc)) {
                                c = cc;
                                break;
                            }
                        }

                        unknowns.put(state, c);
                        usedCharacters.add(c);
                    }
                    b.append(c);
                }
                yslice.add(b.toString());
            }
            slices.add(yslice);
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        JsonObject root = new JsonObject();

        if (!unknowns.isEmpty()) {
            List<PaletteEntry> entries = new ArrayList<>();
            for (Map.Entry<BlockState, Character> entry : unknowns.entrySet()) {
                entries.add(PaletteEntry.block(entry.getValue(), Tools.stateToString(entry.getKey())));
            }
            PaletteDefinition paletteDefinition = new PaletteDefinition(Optional.empty(), Optional.of(entries));
            DataResult<JsonElement> result = PaletteDefinition.CODEC.encodeStart(JsonOps.INSTANCE, paletteDefinition);
            root.add("__comment__", new JsonPrimitive("'missingpalette' represents all blockstates that it couldn't find in the palette. These have to be put in a palette. " +
                    "'exportedpart' is the actual exported part"));
            root.add("missingpalette", result.result().get());
        } else {
            root.add("__comment__", new JsonPrimitive("'exportedpart' is the actual exported part"));
        }

        BuildingPartDefinition buildingPartRE = new BuildingPartDefinition(Optional.empty(),
                Optional.of(part.getXSize()), Optional.of(part.getZSize()), Optional.of(slices),
                Optional.ofNullable(part.getRefPaletteName()), Optional.empty(), Optional.empty());
        DataResult<JsonElement> result = BuildingPartDefinition.CODEC.encodeStart(JsonOps.INSTANCE, buildingPartRE);
        root.add("exportedpart", result.result().get());

        String json = gson.toJson(root);

        try {
            Path exportDir = FabricLoader.getInstance().getConfigDir().resolve("urbex").resolve("exports");
            Path target = ExportPath.resolve(exportDir, filename);
            Files.createDirectories(exportDir);
            Files.writeString(target, json);
            context.getSource().sendSuccess(() -> Component.literal("Exported part to '" + target + "'!"), false);
        } catch (IllegalArgumentException | IOException e) {
            context.getSource().sendFailure(Component.literal("Error writing file '" + filename + "': " + e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }

        return Command.SINGLE_SUCCESS;
    }
}

package dev.krona.urbex.commands;

import dev.krona.urbex.worldgen.GenerationSession;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.IDimensionInfo;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.cityassets.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static dev.krona.urbex.worldgen.CityGenerator.FLOORHEIGHT;
import net.minecraft.network.chat.Component;

public class CommandCreateBuilding implements Command<CommandSourceStack> {

    private static final CommandCreateBuilding CMD = new CommandCreateBuilding();

    public static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("createbuilding")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("name", IdentifierArgument.id())
                        .suggests(ModCommands.getBuildingSuggestionProvider())
                        .then(Commands.argument("floors", IntegerArgumentType.integer(1, 20))
                                .then(Commands.argument("cellars", IntegerArgumentType.integer(0, 10))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(CMD)))));
    }


    @Override
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Identifier name = context.getArgument("name", Identifier.class);
        Integer floors = context.getArgument("floors", Integer.class);
        Integer cellars = context.getArgument("cellars", Integer.class);
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        // The provider first: the building is looked up in this level's compiled assets (issue #128).
        IDimensionInfo dimInfo = GenerationSession.planningFor(level);
        if (dimInfo == null) {
            context.getSource().sendFailure(Component.literal("Urbex does not generate in this dimension!"));
            return 0;
        }
        Building building = dimInfo.assets().buildings().get(name);
        if (building == null) {
            context.getSource().sendFailure(Component.literal("Cannot find building: " + name + "!"));
            return 0;
        }
        WorldCoordinates pos = context.getArgument("pos", WorldCoordinates.class);
        BlockPos bottom = pos.getBlockPos(context.getSource());

        ChunkCoord coord = new ChunkCoord(level.dimension(), bottom.getX() >> 4, bottom.getZ() >> 4);
        // Detached, not the cached plan: this command draws an arbitrary building on request, and
        // rewriting the published ChunkPlan left the shared plan for the chunk describing a
        // building the seed never chose (issue #126).
        ChunkPlan info = ChunkPlan.detachedForEditing(coord, dimInfo,
                new ChunkPlan.BuildingOverride(building, cellars, floors, bottom.getY()));

        ChunkPos cp = ChunkPos.containing(bottom);

        int height = bottom.getY();
        for (int y = height ; y < level.getMaxY() + 1 ; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    level.setBlock(cp.getBlockAt(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }

        for (int f = -info.cellars; f <= info.getNumFloors(); f++) {
            BuildingPart part = info.getFloor(f);

            generatePart(level, cp, info, part, height);
            part = info.getFloorPart2(f);
            if (part != null) {
                generatePart(level, cp, info, part, height);
            }

            height += FLOORHEIGHT;    // We currently only support 6 here
        }

        return Command.SINGLE_SUCCESS;
    }

    private static void generatePart(Level level, ChunkPos cp, ChunkPlan info, IBuildingPart part, int oy) {
        CompiledPalette compiledPalette = info.getCompiledPalette();
        // Cache the combined palette?
        Palette partPalette = part.getLocalPalette();
        Palette buildingPalette = info.getBuilding().getLocalPalette();
        if (partPalette != null || buildingPalette != null) {
            compiledPalette = new CompiledPalette(compiledPalette, partPalette, buildingPalette);
        }

        boolean nowater = part.getMetaBoolean("nowater");
        BlockPos.MutableBlockPos current = new BlockPos.MutableBlockPos();

        for (int x = 0; x < part.getXSize(); x++) {
            for (int z = 0; z < part.getZSize(); z++) {
                char[] vs = part.getVSlice(x, z);
                if (vs != null) {
                    int rx = cp.getBlockX(x);
                    int rz = cp.getBlockZ(z);
                    current.set(rx, oy, rz);
                    for (char c : vs) {
                        BlockState b = compiledPalette.get(c, level.getRandom());
                        if (b == null) {
                            throw new RuntimeException("Could not find entry '" + c + "' in the palette for part '" + part.getName() + "'!");
                        }
                        level.setBlock(current, b, Block.UPDATE_CLIENTS);
                        current.setY(current.getY() + 1);
                    }
                }
            }
        }
    }


}

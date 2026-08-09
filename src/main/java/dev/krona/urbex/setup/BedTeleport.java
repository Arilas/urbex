package dev.krona.urbex.setup;

import dev.krona.urbex.varia.CustomTeleporter;
import dev.krona.urbex.varia.WorldTools;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static dev.krona.urbex.setup.Registration.LOSTCITY;
import net.minecraft.network.chat.Component;

/**
 * The special-bed teleporter: a bed on the configured block, ringed by six skulls, teleports
 * between the overworld and the Urbex city dimension.
 */
public class BedTeleport {

    private static boolean isValidSpawnBed(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return false;
        }
        Direction direction = state.getValue(BedBlock.FACING);
        Block b1 = world.getBlockState(pos.below()).getBlock();
        Block b2 = world.getBlockState(pos.relative(direction.getOpposite()).below()).getBlock();
        Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(Config.SPECIAL_BED_BLOCK.get()));
        if (b1 != b || b2 != b) {
            return false;
        }
        // Check if the bed is surrounded by 6 skulls
        if (!(world.getBlockState(pos.relative(direction)).getBlock() instanceof AbstractSkullBlock)) {   // @todo 1.14 other skulls!
            return false;
        }
        if (!(world.getBlockState(pos.relative(direction.getClockWise())).getBlock() instanceof AbstractSkullBlock)) {
            return false;
        }
        if (!(world.getBlockState(pos.relative(direction.getCounterClockWise())).getBlock() instanceof AbstractSkullBlock)) {
            return false;
        }
        if (!(world.getBlockState(pos.relative(direction.getOpposite(), 2)).getBlock() instanceof AbstractSkullBlock)) {
            return false;
        }
        if (!(world.getBlockState(pos.relative(direction.getOpposite()).relative(direction.getOpposite().getClockWise())).getBlock() instanceof AbstractSkullBlock)) {
            return false;
        }
        if (!(world.getBlockState(pos.relative(direction.getOpposite()).relative(direction.getOpposite().getCounterClockWise())).getBlock() instanceof AbstractSkullBlock)) {
            return false;
        }
        return true;
    }

    public static Player.BedSleepingProblem onPlayerSleepInBed(Player player, BlockPos bedLocation) {
        Level world = player.level();
        if (world.isClientSide()) {
            return null;
        }
        if (bedLocation == null || !isValidSpawnBed(world, bedLocation)) {
            return null;
        }

        if (world.dimension() == Registration.DIMENSION) {
            ServerLevel destWorld = WorldTools.getOverworld(world);
            BlockPos location = findLocation(bedLocation, destWorld);
            CustomTeleporter.teleportToDimension(player, destWorld, location);
            return Player.BedSleepingProblem.OTHER_PROBLEM;
        } else {
            ServerLevel destWorld = player.level().getServer().getLevel(Registration.DIMENSION);
            if (destWorld == null) {
                player.sendSystemMessage(Component.literal("Error finding Urbex dimension: " + LOSTCITY + "!").withStyle(ChatFormatting.RED));
            } else {
                BlockPos location = findLocation(bedLocation, destWorld);
                CustomTeleporter.teleportToDimension(player, destWorld, location);
            }
            return Player.BedSleepingProblem.OTHER_PROBLEM;
        }
    }

    private static BlockPos findLocation(BlockPos bedLocation, ServerLevel destWorld) {
        BlockPos top = bedLocation.above(5);//destWorld.getHeight(Heightmap.Type.MOTION_BLOCKING, bedLocation).up(10);
        BlockPos location = top;
        while (top.getY() > 1 && destWorld.getBlockState(location).isAir()) {
            location = location.below();
        }
//        BlockPos location = findValidTeleportLocation(destWorld, top);
        if (destWorld.isEmptyBlock(location.below())) {
            // No place to teleport
            destWorld.setBlockAndUpdate(bedLocation, Blocks.COBBLESTONE.defaultBlockState());
        }
        return location.above(1);
    }
}

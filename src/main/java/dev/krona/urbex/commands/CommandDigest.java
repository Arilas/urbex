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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Generates a square of chunks in a chosen order and prints a hash of the result.
 * <p>
 * The acceptance harness for reproducible worldgen: generating the same seed in two different
 * orders must produce the same digest. Aggregation is order-independent by construction (chunks
 * are sorted before hashing), so only the <em>generation</em> order is under test.
 */
public class CommandDigest implements Command<CommandSourceStack> {

    private static final CommandDigest CMD = new CommandDigest();

    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

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

        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                chunks.add(new ChunkPos(offset + x, offset + z));
            }
        }

        switch (order) {
            case "rowmajor" -> { /* already row-major */ }
            case "reverse" -> Collections.reverse(chunks);
            case "shuffled" -> Collections.shuffle(chunks, new Random(0xC0FFEE));
            default -> {
                context.getSource().sendFailure(Component.literal(
                        "order must be one of: rowmajor, reverse, shuffled").withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        long start = System.currentTimeMillis();
        for (ChunkPos pos : chunks) {
            level.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, true);
        }

        // Hash in a canonical order so only generation order can affect the result.
        List<ChunkPos> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));

        long digest = FNV_OFFSET;
        for (ChunkPos pos : sorted) {
            digest = hashChunk(level, pos, digest);
        }

        long elapsed = System.currentTimeMillis() - start;
        String line = String.format("DIGEST=%016x chunks=%d order=%s offset=%d ms=%d",
                digest, chunks.size(), order, offset, elapsed);
        context.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.YELLOW), true);
        System.out.println(line);   // so headless runs can grep stdout
        return 1;
    }

    private long hashChunk(ServerLevel level, ChunkPos pos, long digest) {
        ChunkAccess chunk = level.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, true);
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    mutable.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z);
                    BlockState state = chunk.getBlockState(mutable);
                    if (state.isAir()) {
                        continue;
                    }
                    digest = hashLong(digest, mutable.asLong());
                    digest = hashString(digest, state.toString());
                    // Look the block entity up through the level rather than the raw ChunkAccess:
                    // ChunkAccess no longer exposes a bare getBlockEntity(BlockPos) on 26.2 (only
                    // LevelChunk does), and the level lookup is the stable public way to reach it
                    // for a chunk we just forced to FULL status.
                    BlockEntity be = level.getBlockEntity(mutable);
                    if (be != null) {
                        // saveWithId(ValueOutput) returns void on 26.2 (the old CompoundTag-returning
                        // overload is gone). saveWithFullMetadata(HolderLookup.Provider) is the
                        // vanilla replacement that still hands back a CompoundTag directly - it's the
                        // exact method LevelChunk/ProtoChunk use to persist block entities to disk,
                        // so its NBT representation is guaranteed stable across JVM restarts.
                        digest = hashString(digest,
                                be.saveWithFullMetadata(level.registryAccess()).toString());
                    }
                }
            }
        }
        return digest;
    }

    private static long hashLong(long digest, long value) {
        for (int i = 0; i < 8; i++) {
            digest = (digest ^ ((value >>> (i * 8)) & 0xFF)) * FNV_PRIME;
        }
        return digest;
    }

    private static long hashString(long digest, String value) {
        for (int i = 0; i < value.length(); i++) {
            digest = (digest ^ value.charAt(i)) * FNV_PRIME;
        }
        return digest;
    }
}

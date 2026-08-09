package dev.krona.urbex.worldgen;

import net.minecraft.core.BlockPos;
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
 * Generates a square of chunks in a chosen order and produces two hashes of the result. This is
 * the machinery behind {@code /urbex digest} and the headless digest check; see
 * {@link dev.krona.urbex.commands.CommandDigest} for what the two digests do and do not cover.
 * <p>
 * Both digests aggregate in a canonical sorted order - chunks by (x, z), and within a chunk the
 * recorded positions ascending - so only the <em>generation</em> order is ever under test.
 */
public final class DigestRunner {

    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private DigestRunner() {
    }

    /**
     * @param driverDigest hash of the final state at each position the mod wrote through
     *                     {@link ChunkDriver} - the acceptance signal
     * @param fullDigest   hash of every non-air block in every chunk - a loose tripwire only
     */
    public record Result(long driverDigest, long fullDigest, long driverBlocks, int drivenChunks,
                         int chunkCount, long elapsedMs) {

        public String driverLine(String order, int offset) {
            return String.format(
                    "DRIVERDIGEST=%016x blocks=%d drivenChunks=%d chunks=%d order=%s offset=%d ms=%d",
                    driverDigest, driverBlocks, drivenChunks, chunkCount, order, offset, elapsedMs);
        }

        public String fullLine(String order, int offset) {
            return String.format("DIGEST=%016x chunks=%d order=%s offset=%d ms=%d",
                    fullDigest, chunkCount, order, offset, elapsedMs);
        }
    }

    /**
     * The chunk square {@code (offset±radius, offset±radius)} arranged in the requested
     * generation order: {@code rowmajor}, {@code reverse} or {@code shuffled}.
     *
     * @throws IllegalArgumentException for an unknown order
     */
    public static List<ChunkPos> chunkSquare(int radius, String order, int offset) {
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
            default -> throw new IllegalArgumentException("order must be one of: rowmajor, reverse, shuffled");
        }
        return chunks;
    }

    /** Generates the square to FULL status in the given order and digests the result. */
    public static Result run(ServerLevel level, int radius, String order, int offset) {
        List<ChunkPos> chunks = chunkSquare(radius, order, offset);

        long start = System.currentTimeMillis();
        int recordedChunks;
        ChunkDriver.startRecordingWrites();
        try {
            for (ChunkPos pos : chunks) {
                level.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, true);
            }
        } finally {
            ChunkDriver.stopRecordingWrites();
            recordedChunks = ChunkDriver.recordedChunkCount();
        }

        // Hash in a canonical order so only generation order can affect the result.
        List<ChunkPos> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));

        boolean detail = System.getProperty("urbex.digestCheck.detail") != null;
        String dumpPath = System.getProperty("urbex.digestCheck.dump");
        java.io.PrintWriter dump = null;
        if (dumpPath != null) {
            try {
                dump = new java.io.PrintWriter(new java.io.FileWriter(dumpPath));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
        long digest = FNV_OFFSET;
        long driverDigest = FNV_OFFSET;
        long driverBlocks = 0;
        for (ChunkPos pos : sorted) {
            digest = hashChunk(level, pos, digest);
            long[] written = ChunkDriver.recordedWrites(pos);
            driverBlocks += written.length;
            driverDigest = hashDriverWrites(level, pos, written, driverDigest);
            if (detail) {
                // Independent per-chunk digest, for diffing two runs to localize a mismatch
                long chunkDigest = hashDriverWrites(level, pos, written, FNV_OFFSET);
                System.out.printf("CHUNKDIGEST %d %d %016x blocks=%d%n",
                        pos.x(), pos.z(), chunkDigest, written.length);
            }
            if (dump != null) {
                BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
                long[] sortedWrites = written.clone();
                java.util.Arrays.sort(sortedWrites);
                for (long packed : sortedWrites) {
                    mutable.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                    dump.printf("%d %d %d %s%n", mutable.getX(), mutable.getY(), mutable.getZ(),
                            ChunkDriver.recordedState(pos, packed));
                }
            }
        }
        if (dump != null) {
            dump.close();
        }
        ChunkDriver.clearRecordedWrites();

        long elapsed = System.currentTimeMillis() - start;
        return new Result(driverDigest, digest, driverBlocks, recordedChunks, chunks.size(), elapsed);
    }

    /**
     * Hash the state this mod last wrote at each position it touched.
     * <p>
     * The states come from the driver's own record, captured at write time - deliberately not
     * read back from the world: vanilla decoration from neighbouring chunks (ore blobs)
     * overwrites border columns in pipeline-timing-dependent order, and a digest reading the
     * final world would measure vanilla's scheduling rather than this mod's output. A position
     * overwritten several times by the driver contributes its last state exactly once, so two
     * runs that reach the same blocks by different internal paths agree. Block entities are
     * still read from the world; ores cannot replace containers, so those positions are stable.
     */
    private static long hashDriverWrites(ServerLevel level, ChunkPos chunkPos, long[] written, long digest) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (long packed : written) {
            mutable.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            digest = hashLong(digest, packed);
            BlockState recorded = ChunkDriver.recordedState(chunkPos, packed);
            digest = hashString(digest, recorded == null ? "null" : recorded.toString());
            BlockEntity be = level.getBlockEntity(mutable);
            if (be != null) {
                digest = hashString(digest, be.saveWithFullMetadata(level.registryAccess()).toString());
            }
        }
        return digest;
    }

    private static long hashChunk(ServerLevel level, ChunkPos pos, long digest) {
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

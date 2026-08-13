package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.ChunkPlan;
import dev.krona.urbex.worldgen.lost.PrimaryBridgePlanner;
import dev.krona.urbex.worldgen.lost.Railway;
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

    /**
     * Clear every planning cache after each {@code N} chunks driven, simulating the timed eviction
     * a long-running world does by itself. Absent or zero leaves the caches alone.
     */
    public static final String EXPIRE_EVERY_PROPERTY = "urbex.digestCheck.expireEvery";

    private DigestRunner() {
    }

    /**
     * @param driverDigest hash of the final state at each position the mod wrote through
     *                     {@link ChunkDriver} - the acceptance signal
     * @param fullDigest   hash of every non-air block in every chunk - a loose tripwire only
     * @param bridgeChunks how many sampled chunks carry a planned primary bridge deck (see
     *                     {@link PrimaryBridgePlanner#spanAt}). Printed so a sample window that
     *                     stops containing a bridge - the only mechanical proof one renders at
     *                     all - announces itself instead of moving silently; see
     *                     {@code digestCheckFeatures} in {@code build.gradle}.
     * @param slopeChunks  how many sampled chunks carry a sloped minor road (see
     *                     {@link ChunkPlan#getStreetSlopeDirection}). Printed for the same
     *                     reason as {@code bridgeChunks}: a sample window that stops containing a
     *                     slope - the only mechanical proof the full-chunk {@code street_stair}
     *                     part renders at all - announces itself instead of moving silently; see
     *                     {@code digestCheckFeatures} in {@code build.gradle}.
     * @param unsafeReads  how many cross-chunk terrain reads Urbex made during this run (see
     *                     {@link UnsafeReadCounter}). City generation runs at the carver stage,
     *                     where a chunk may touch only itself; a non-zero count here is a
     *                     contract violation, not a style preference, and {@code DigestCheck}
     *                     fails on it whenever asked to.
     */
    public record Result(long driverDigest, long fullDigest, long driverBlocks, int drivenChunks,
                         int chunkCount, long elapsedMs, int bridgeChunks, int slopeChunks, int avoidedChunks,
                         int railCollisionChunks, long unsafeReads) {

        public String driverLine(String order, int offset) {
            return String.format(
                    "DRIVERDIGEST=%016x blocks=%d drivenChunks=%d chunks=%d order=%s offset=%d ms=%d bridgeChunks=%d slopeChunks=%d avoidedChunks=%d railCollisionChunks=%d unsafeReads=%d",
                    driverDigest, driverBlocks, drivenChunks, chunkCount, order, offset, elapsedMs, bridgeChunks,
                    slopeChunks, avoidedChunks, railCollisionChunks, unsafeReads);
        }

        public String fullLine(String order, int offset) {
            return String.format("DIGEST=%016x chunks=%d order=%s offset=%d ms=%d",
                    fullDigest, chunkCount, order, offset, elapsedMs);
        }
    }

    /**
     * The generation orders {@link #chunkSquare} accepts, in the order they are offered for
     * tab-completion. Public so {@code CommandDigest} suggests exactly what this switch accepts -
     * the two used to drift, and the command's failure message listed values its own argument
     * offered no completion for.
     */
    public static final List<String> ORDERS = List.of("rowmajor", "reverse", "shuffled");

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
            default -> throw new IllegalArgumentException("order must be one of: " + String.join(", ", ORDERS));
        }
        return chunks;
    }

    /** Generates the square to FULL status in the given order and digests the result. */
    public static Result run(ServerLevel level, int radius, String order, int offset) {
        List<ChunkPos> chunks = chunkSquare(radius, order, offset);

        UnsafeReadCounter.reset();
        long start = System.currentTimeMillis();
        int recordedChunks;
        // Forced cache expiry, off unless asked for. Planning caches are TimedCaches that can drop
        // an entry at any moment in a real world, and a plan rebuilt from a different starting point
        // than its neighbours saw is one of the two defects issue #126 was opened for. Clearing them
        // mid-drive is the standing version of "wait for the eviction to happen by itself": if any
        // planning value is not a pure function of the seed and the coordinate, the digest moves.
        int expireEvery = Integer.getInteger(EXPIRE_EVERY_PROPERTY, 0);
        int driven = 0;
        ChunkDriver.startRecordingWrites();
        try {
            for (ChunkPos pos : chunks) {
                level.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, true);
                if (expireEvery > 0 && ++driven % expireEvery == 0) {
                    IDimensionInfo dimInfo = GenerationSession.planningFor(level);
                    if (dimInfo != null) {
                        dimInfo.caches().clear();
                    }
                }
            }
        } finally {
            ChunkDriver.stopRecordingWrites();
            recordedChunks = ChunkDriver.recordedChunkCount();
        }
        long unsafeReads = UnsafeReadCounter.count();

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

        int bridgeChunks = countBridgeChunks(level, sorted);
        int slopeChunks = countSlopeChunks(level, sorted);
        int avoidedChunks = countAvoidedChunks(level, sorted);
        int railCollisionChunks = countRailCollisionChunks(level, sorted);

        long elapsed = System.currentTimeMillis() - start;
        return new Result(driverDigest, digest, driverBlocks, recordedChunks, chunks.size(), elapsed, bridgeChunks,
                slopeChunks, avoidedChunks, railCollisionChunks, unsafeReads);
    }

    /**
     * How many of the sampled chunks carry a planned primary bridge deck. Read-only: queries the
     * same {@link IDimensionInfo} real generation used for these chunks, after generation, so it
     * costs no extra draw and cannot perturb either digest. Zero whenever no Urbex profile is
     * configured for this dimension - the driver-writes check already fails that case loudly.
     */
    /**
     * How many of the sampled chunks structure avoidance suppresses.
     * <p>
     * The same coverage guard {@code bridgeChunks} and {@code slopeChunks} are: a window that stops
     * containing an avoided structure stops testing avoidance, and passes while doing so. Counted
     * after generation, when every chunk is loaded, so this reads the answer the neighbourhood probe
     * <em>would</em> give with nothing missing - which is deliberately not the answer generation got,
     * and the gap between them is issue #126.
     */
    private static int countAvoidedChunks(ServerLevel level, List<ChunkPos> chunkPositions) {
        int count = 0;
        StringBuilder where = new StringBuilder();
        for (ChunkPos pos : chunkPositions) {
            // Loaded first, then asked through the production method. Not a reimplementation of the
            // matching - that stays in one place - but the residency guard inside it answers "no"
            // for a chunk the level does not hold, and after generation some of the sample is not
            // resident. A coverage counter that inherited that would report "nothing to cover" for a
            // window full of structures, which is the opposite of its job.
            level.getChunk(pos.x(), pos.z(), ChunkStatus.STRUCTURE_REFERENCES, true);
            CityGenerator.AvoidChunk avoid = CityGenerator.hasBlacklistedStructure(level, pos.x(), pos.z());
            if (avoid != CityGenerator.AvoidChunk.NO) {
                count++;
                where.append(' ').append(pos.x()).append(',').append(pos.z()).append('=').append(avoid);
            }
        }
        // The production method, not a reimplementation of it. It answers "skip" for a chunk the
        // region does not hold, which is exactly the order-dependence issue #126 is about - so a
        // count taken through it is the honest one: it says what avoidance would actually have done,
        // not what a perfectly-loaded world would say. If those two ever disagree, that disagreement
        // is the bug, and hiding it behind a second loop here would be the wrong place to find out.
        // Logged when it fires, so a window that loses its structure says where it used to be -
        // the same job the comments over digestCheckFeatures do by hand for the bridge and the slope.
        if (count > 0) {
            Urbex.getLogger().info("AVOIDEDCHUNKS{}", where);
        }
        return count;
    }

    /**
     * How many of the sampled chunks have a building deep enough to cancel the railway planned
     * there. The same coverage guard {@code avoidedChunks} is: the collision only exists under a
     * world style that sets {@code railwayavoidance: block_railway}, and the one this mod ships sets
     * {@code ignore}, so every window is blind to it unless a datapack turns it on - which is how it
     * stayed order-dependent unobserved (issue #126).
     */
    private static int countRailCollisionChunks(ServerLevel level, List<ChunkPos> chunkPositions) {
        IDimensionInfo dimInfo = GenerationSession.planningFor(level);
        if (dimInfo == null) {
            return 0;
        }
        int count = 0;
        StringBuilder where = new StringBuilder();
        for (ChunkPos pos : chunkPositions) {
            ChunkCoord coord = new ChunkCoord(level.dimension(), pos.x(), pos.z());
            if (Railway.buildingBlocksRail(coord, dimInfo)) {
                count++;
                where.append(' ').append(pos.x()).append(',').append(pos.z());
            }
        }
        if (count > 0) {
            Urbex.getLogger().info("RAILCOLLISIONS{}", where);
        }
        return count;
    }

    private static int countBridgeChunks(ServerLevel level, List<ChunkPos> chunkPositions) {
        IDimensionInfo dimInfo = GenerationSession.planningFor(level);
        if (dimInfo == null) {
            return 0;
        }
        int count = 0;
        for (ChunkPos pos : chunkPositions) {
            ChunkCoord coord = new ChunkCoord(level.dimension(), pos.x(), pos.z());
            if (PrimaryBridgePlanner.spanAt(coord, dimInfo).isPresent()) {
                count++;
            }
        }
        return count;
    }

    /**
     * How many of the sampled chunks carry a sloped minor road. Read-only: queries the same
     * {@link IDimensionInfo} real generation used for these chunks, after generation, so it costs
     * no extra draw and cannot perturb either digest. Zero whenever no Urbex profile is configured
     * for this dimension - the driver-writes check already fails that case loudly.
     */
    private static int countSlopeChunks(ServerLevel level, List<ChunkPos> chunkPositions) {
        IDimensionInfo dimInfo = GenerationSession.planningFor(level);
        if (dimInfo == null) {
            return 0;
        }
        int count = 0;
        for (ChunkPos pos : chunkPositions) {
            ChunkCoord coord = new ChunkCoord(level.dimension(), pos.x(), pos.z());
            if (ChunkPlan.getChunkPlan(coord, dimInfo).getStreetSlopeDirection() != null) {
                count++;
            }
        }
        return count;
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

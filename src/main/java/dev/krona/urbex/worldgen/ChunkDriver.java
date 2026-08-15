package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.GenerationMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class ChunkDriver {

    // ---------------------------------------------------------------------------------------
    // Write recording. Off in normal play; /urbex digest switches it on around a generation run
    // so it can hash what this mod placed *through this driver*, rather than the whole chunk.
    // Hashing a whole chunk also hashes vanilla's ore blobs and underwater vegetation, which
    // bleed across chunk borders and so disagree between two runs even with this mod switched
    // off entirely.
    //
    // What this does NOT cover: writes this mod makes straight to the world, bypassing the
    // driver. Those are invisible here and so invisible to the digest. Today that is two things:
    // the post-todo callbacks ChunkFixer drains off the ChunkGenContext, which write through the
    // WorldGenLevel; and the border-block shape updates this class defers to vanilla
    // postprocessing (see updateAdjacent) - a matching DRIVERDIGEST no longer proves fence, wall
    // and stair connections at chunk borders are unchanged, because postprocessing writes them
    // outside the driver. UnsafeReadGateMixin covers the residual risk from a different angle: it
    // hooks both WorldGenRegion.getChunk and WorldGenRegion.ensureCanWrite, so any read or write
    // that ever crossed a chunk boundary - post-todo or otherwise - is counted there, whether or
    // not it is one this comment enumerates.
    //
    // Each touched position is recorded together with the state the driver last wrote there.
    // A position written three times contributes its last state once, so the internal path by
    // which two runs reached the same block cannot change the answer - that is the property
    // under test. The state is captured at write time, NOT read back from the world afterwards:
    // vanilla decoration from neighbouring chunks (ore blobs and friends) overwrites border
    // columns in pipeline-timing-dependent order, and a digest that read the final world would
    // measure vanilla's scheduling instead of this mod's output.
    //
    // Thread safety: a ChunkDriver belongs to one ChunkGenContext, which belongs to one call of
    // CityGenerator.generate() on one thread, and never escapes it. So the accumulator
    // is a plain, unsynchronised, thread-confined set - no lock is taken per block written.
    //
    // It crosses to other threads exactly once, at the end of actuallyGenerate(): the local set is
    // split by chunk and merged into RECORDED_WRITES. Merging produces a *new* set rather than
    // mutating the one already in the map, so nothing reachable from the map is ever written to
    // again after it is published. The reader (the /urbex digest command, on the server thread,
    // after every chunk it asked for has finished) therefore needs no lock either: the
    // ConcurrentHashMap.merge/get pair gives it the happens-before edge, and what it finds is
    // immutable.
    // ---------------------------------------------------------------------------------------

    private static volatile boolean recordingWrites = false;
    // Position -> the state the driver last wrote there. The state is captured at write time
    // rather than re-read from the world afterwards: vanilla decoration from neighbouring
    // chunks (ore blobs and friends) overwrites border columns in pipeline-timing-dependent
    // order, so a world read would fold vanilla's scheduling into a digest that must measure
    // only this mod's output (issue #24).
    private static final Map<ChunkPos, Long2ObjectOpenHashMap<BlockState>> RECORDED_WRITES = new ConcurrentHashMap<>();

    public static void startRecordingWrites() {
        RECORDED_WRITES.clear();
        recordingWrites = true;
    }

    public static void stopRecordingWrites() {
        recordingWrites = false;
    }

    public static boolean isRecordingWrites() {
        return recordingWrites;
    }

    /** Positions this mod wrote in {@code pos}, ascending. Empty if the chunk was never driven. */
    public static long[] recordedWrites(ChunkPos pos) {
        Long2ObjectOpenHashMap<BlockState> map = RECORDED_WRITES.get(pos);
        if (map == null) {
            return new long[0];
        }
        long[] positions = map.keySet().toLongArray();
        Arrays.sort(positions);     // canonical order, so hashing cannot see the write order
        return positions;
    }

    /** The state the driver last wrote at {@code packed}, or null if the position was never recorded. */
    public static BlockState recordedState(ChunkPos pos, long packed) {
        Long2ObjectOpenHashMap<BlockState> map = RECORDED_WRITES.get(pos);
        return map == null ? null : map.get(packed);
    }

    public static int recordedChunkCount() {
        return RECORDED_WRITES.size();
    }

    public static void clearRecordedWrites() {
        RECORDED_WRITES.clear();
    }

    /**
     * Every position this driver wrote and the state it last put there. Thread-confined: only ever
     * touched by the single thread driving this chunk.
     *
     * <p>Filled on every write, whether or not anything is recording, and that is the fix rather
     * than an implementation detail. {@code putRange} used to consult {@link #recordingWrites}
     * before noting a position at all - and {@link #correctionsPass} walks this same log - so a
     * bulk-filled wall got its connection properties during {@code /urbex digest} and did not
     * during normal play. The corrections pass is generation; the recorder is a harness, and a
     * harness must not be load-bearing (issue #52).</p>
     *
     * <p>No golden moved when this was fixed, and none moves if it is broken again: every suite
     * hashes the same chunks under either rule. The guard is
     * {@code ChunkDriverCorrectionsTest}, not the digests - which is worth knowing before trusting
     * a green digest run to cover a change to <em>which</em> positions get corrected.</p>
     */
    private Long2ObjectOpenHashMap<BlockState> written;

    /**
     * What {@link #written} is sized for on creation, rather than grown into from fastutil's
     * default of 16.
     *
     * <p>A driven chunk writes about 9200 positions - measured at 9241 on both the
     * {@code digestCheckAvoid} window and a 625-chunk {@code onlycities} one - so the map was
     * doubling its way up through eleven rehashes, and the last few of those allocate the
     * expensive pairs. Growing to capacity C allocates about 2C of backing arrays; starting at C
     * allocates C. At fastutil's 0.75 load factor this asks for the 16384-slot table that 9200
     * entries were going to end up in anyway.</p>
     *
     * <p>Chunks that write far less than that over-allocate, which is the trade: the map is
     * created lazily on the first write, so a chunk the driver never touches still costs nothing,
     * and one that writes at all is overwhelmingly a city chunk writing thousands.</p>
     */
    private static final int EXPECTED_WRITES_PER_CHUNK = 12288;

    private boolean published;
    private boolean loggedLateWrite;

    /** This driver put {@code state} at the given position. */
    private void wrote(int x, int y, int z, BlockState state) {
        if (published) {
            recordLateWrite(x, y, z, state);
            return;
        }
        if (written == null) {
            written = new Long2ObjectOpenHashMap<>(EXPECTED_WRITES_PER_CHUNK);
        }
        // put, not putIfAbsent: within one driver, the last write to a position wins - the same
        // property the old read-the-final-world approach had for driver-internal overwrites
        written.put(BlockPos.asLong(x, y, z), state);
    }

    private void recordLateWrite(int x, int y, int z, BlockState state) {
        if (!recordingWrites) {
            return;
        }
        // publishRecordedWrites() has already run for this driver, so the accumulator is gone
        // and this position would simply vanish. Nothing reaches here today - updateAdjacent is
        // the only writer that could outlive the placement pass, and it is only ever called
        // from correct(). But Task 6 verifies itself with this harness, and a harness that
        // silently discards writes is the worst possible thing to debug. So: say so, loudly,
        // and record it anyway rather than quietly reporting a wrong digest.
        if (!loggedLateWrite) {
            loggedLateWrite = true;
            Urbex.getLogger().error(
                    "ChunkDriver write recorder: block written at {},{},{} after this driver "
                            + "published its writes. The /urbex digest harness would have "
                            + "dropped it; recording it separately. This is a bug - some pass "
                            + "now runs after actuallyGenerate().", x, y, z);
        }
        Long2ObjectOpenHashMap<BlockState> late = new Long2ObjectOpenHashMap<>();
        late.put(BlockPos.asLong(x, y, z), state);
        mergeIntoRecordedWrites(late);
    }

    /**
     * Hand this chunk's write log over to the shared map, if anyone asked for it. Called once, at
     * the end of generation, from the thread that did the driving.
     *
     * <p>The {@code recordingWrites} check is the whole point of the method. {@link #RECORDED_WRITES}
     * is a static that only {@code /urbex digest} ever clears, and this published into it
     * unconditionally - so a server that never ran the command still kept every position and
     * {@code BlockState} of every chunk it had ever generated, for the life of the process, and
     * paid a full copy of the accumulated map per chunk to put them there (issue #52).</p>
     */
    private void publishRecordedWrites() {
        Long2ObjectOpenHashMap<BlockState> local = written;
        written = null;
        published = true;
        if (!recordingWrites || local == null || local.isEmpty()) {
            return;
        }
        mergeIntoRecordedWrites(local);
    }

    private static void mergeIntoRecordedWrites(Long2ObjectOpenHashMap<BlockState> local) {
        Map<ChunkPos, Long2ObjectOpenHashMap<BlockState>> byChunk = new HashMap<>();
        for (Long2ObjectOpenHashMap.Entry<BlockState> entry : local.long2ObjectEntrySet()) {
            long packed = entry.getLongKey();
            ChunkPos key = new ChunkPos(BlockPos.getX(packed) >> 4, BlockPos.getZ(packed) >> 4);
            byChunk.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<BlockState>()).put(packed, entry.getValue());
        }
        // Copy-on-merge: whatever is already published stays untouched, so a concurrent reader
        // never sees a map being mutated underneath it.
        byChunk.forEach((key, map) -> RECORDED_WRITES.merge(key, map, (existing, added) -> {
            Long2ObjectOpenHashMap<BlockState> merged = new Long2ObjectOpenHashMap<>(existing);
            merged.putAll(added);
            return merged;
        }));
    }

    private LevelAccessor region;
    private long seed;
    private ChunkAccess primer;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    /** Where the caller is walking; see {@link BlockCursor}. */
    private BlockCursor cursor;
    /** Where writes accumulate until {@link #flushToChunk}. */
    private ChunkBuffer buffer;
    /** Resolves connections once the chunk is finished; see {@link #correctionsPass}. */
    private BlockShaper shaper;
    private int cx;
    private int cz;

    public void setPrimer(LevelAccessor region, ChunkAccess primer) {
        this.region = region;
        this.seed = region instanceof WorldGenLevel level ? level.getSeed() : 0L;
        this.primer = primer;
        if (primer != null) {
            buffer = new ChunkBuffer(this::wrote, region::getBlockState,
                    region.getMinY(), region.getMaxY() + 1,
                    primer.getPos().x() << 4, primer.getPos().z() << 4);
            this.cx = primer.getPos().x();
            this.cz = primer.getPos().z();
            shaper = new BlockShaper(chunkView, region, seed);
            cursor = new BlockCursor(blockAccess, primer.getPos().x() << 4, primer.getPos().z() << 4);
        }
    }

    /**
     * Commit what the cache holds to the chunk without finishing the chunk: no corrections, no
     * heightmaps, no publishing. For the mid-generation flush a part2 floor needs - the write
     * recorder keeps accumulating across it, so nothing lands on the late-write path any more
     * (issue #48).
     */
    /**
     * Where this driver is relative to writing its buffered blocks into the world.
     *
     * <p>Everything before {@link #flushToChunk} is buffered in this driver's own section cache, so
     * a generation that fails before it has touched nothing. What a failure costs depends on which
     * side of that line it lands on, and a report that cannot say which side is a report nobody can
     * act on (issue #131).</p>
     */
    public enum CommitState {
        /** Nothing has been written to the world. The chunk is still pure vanilla terrain. */
        BUFFERED,
        /** Partway through writing. Some of this chunk's city is in the world and some is not. */
        COMMITTING,
        /** Every buffered block is in the world. Anything after this is post-processing. */
        COMMITTED
    }

    private CommitState commitState = CommitState.BUFFERED;

    /** @see CommitState */
    public CommitState commitState() {
        return commitState;
    }

    public void flushToChunk(ChunkAccess chunk) {
        commitState = CommitState.COMMITTING;
        BulkSectionAccess bulk = new BulkSectionAccess(region);
        buffer.flush(bulk);
        bulk.close();
        buffer.clear();
        commitState = CommitState.COMMITTED;
    }

    /** @see #actuallyGenerate(ChunkAccess, long) */
    public void actuallyGenerate(ChunkAccess chunk) {
        actuallyGenerate(chunk, 0L);
    }

    /**
     * @param ordinal this chunk's {@link dev.krona.urbex.varia.GenerationMetrics#beginChunk}, so the
     *                three passes below can be measured apart. This step is two thirds of a chunk's
     *                cost and the three things it does have nothing in common, so one figure for it
     *                names no target; the overload above exists for callers that have no ordinal to
     *                give and are not being measured.
     */
    public void actuallyGenerate(ChunkAccess chunk, long ordinal) {
        long mark = GenerationMetrics.mark();
        long alloc = GenerationMetrics.allocMark();
        correctionsPass(ordinal);
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.CORRECT, mark, alloc);

        mark = GenerationMetrics.mark();
        alloc = GenerationMetrics.allocMark();
        flushToChunk(chunk);
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.FLUSH, mark, alloc);

        // Full recompute instead of the old fake-bedrock Heightmap.update calls, which could
        // only ever raise heights and lied to MOTION_BLOCKING_NO_LEAVES (issue #46). O(chunk),
        // once, unconditionally correct in both directions.
        mark = GenerationMetrics.mark();
        alloc = GenerationMetrics.allocMark();
        Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(
                Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.HEIGHTMAP, mark, alloc);

        mark = GenerationMetrics.mark();
        alloc = GenerationMetrics.allocMark();
        publishRecordedWrites();
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.PUBLISH, mark, alloc);
    }

    /**
     * The relocated {@link #correct}: connection properties and neighbour shape updates used to
     * run for every single write - four updateShape calls and a RandomSource allocation per
     * block, mostly against half-built state that later writes overwrote (issue #34). Now they
     * run once per finally-written position, against the finished chunk, in sorted order so the
     * result cannot depend on write order.
     */
    private void correctionsPass(long ordinal) {
        if (written == null || written.isEmpty()) {
            return;
        }
        long mark = GenerationMetrics.mark();
        long alloc = GenerationMetrics.allocMark();
        long[] positions = written.keySet().toLongArray();
        // Not Arrays.sort: same order, a fraction of the cost on this input. See PositionSort.
        PositionSort.sort(positions);
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.CORRECT_SORT, mark, alloc);

        mark = GenerationMetrics.mark();
        alloc = GenerationMetrics.allocMark();
        for (long packed : positions) {
            int x = BlockPos.getX(packed);
            int y = BlockPos.getY(packed);
            int z = BlockPos.getZ(packed);
            pos.set(x, y, z);
            BlockState state = getBlock(pos);
            BlockState corrected = shaper.correct(state, x, y, z);
            if (corrected != null && corrected != state) {
                setBlock(pos, corrected);
            }
        }
        GenerationMetrics.phase(ordinal, GenerationMetrics.Phase.CORRECT_SHAPE, mark, alloc);
    }

    /**
     * The chunk as {@link BlockShaper} needs to see it. An adapter rather than {@code implements},
     * so resolving connections does not force the driver's read-through and containment checks into
     * its public surface.
     */
    private final BlockShaper.ChunkView chunkView = new BlockShaper.ChunkView() {
        @Override
        public BlockState get(BlockPos pos) {
            return getBlock(pos);
        }

        @Override
        public void set(BlockPos pos, BlockState state) {
            setBlock(pos, state);
        }

        @Override
        public boolean contains(BlockPos pos) {
            return isThisChunk(pos);
        }
    };

    /** What a {@link BlockCursor} does to this chunk: explicit positions, absolute. */
    private final BlockCursor.Blocks blockAccess = new BlockCursor.Blocks() {
        @Override
        public void set(int x, int y, int z, BlockState state) {
            setBlock(x, y, z, state);
        }

        @Override
        public BlockState get(int x, int y, int z) {
            return getBlock(pos.set(x, y, z));
        }
    };

    /** Writes one block, by position rather than by where the cursor happens to be (issue #52). */
    public void setBlock(int x, int y, int z, BlockState state) {
        buffer.set(x, y, z, state);
    }

    private void setBlock(BlockPos p, BlockState state) {
        buffer.set(p.getX(), p.getY(), p.getZ(), state);
    }

    // This version of getBlock() is less optimal but it will work for different chunks
    private BlockState getBlockSafe(BlockPos p) {
        if (isThisChunk(p)) {
            return getBlock(p);
        }
        // Defensive: during parallel world generation a neighbouring chunk may not be part of
        // (or ready in) the current WorldGenRegion. Reading it would throw
        // "Requested chunk unavailable during world generation" and kill chunk gen.
        // Treat unavailable neighbours as air - only cosmetic connection states depend on this.
        if (!region.hasChunk(p.getX() >> 4, p.getZ() >> 4)) {
            return Blocks.AIR.defaultBlockState();
        }
        try {
            return region.getBlockState(p);
        } catch (RuntimeException e) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    public BlockState getBlockAt(BlockPos pos) {
        return getBlockSafe(pos);
    }

    private BlockState getBlock(BlockPos p) {
        BlockState state = buffer.get(p.getX(), p.getY(), p.getZ());
        if (state == null) {
            state = region.getBlockState(p);
            buffer.remember(p.getX(), p.getY(), p.getZ(), state);
        }
        return state;
    }

    public LevelAccessor getRegion() {
        return region;
    }

    public ChunkAccess getPrimer() {
        return primer;
    }

    // -----------------------------------------------------------------------------------------
    // The cursor. State and behaviour live in BlockCursor; these delegate, and keep the shape every
    // caller already writes against (issue #52).
    // -----------------------------------------------------------------------------------------

    public ChunkDriver current(int x, int y, int z) {
        cursor.at(x, y, z);
        return this;
    }

    public ChunkDriver currentAbsolute(BlockPos pos) {
        cursor.atAbsolute(pos);
        return this;
    }

    public ChunkDriver currentRelative(BlockPos pos) {
        return current(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockPos getCurrentCopy() {
        return cursor.copy();
    }

    public BlockPos.MutableBlockPos getCurrent() {
        return cursor.position();
    }

    public void incY() {
        cursor.up();
    }

    public void decY() {
        cursor.down();
    }

    public void incX() {
        cursor.east();
    }

    public void incZ() {
        cursor.south();
    }

    public int getX() {
        return cursor.x();
    }

    public int getY() {
        return cursor.y();
    }

    public int getZ() {
        return cursor.z();
    }

    public void setBlockRange(int x, int y, int z, int y2, BlockState state) {
        buffer.fill(worldX(x), worldZ(z), y, y2 - 1, state);
    }

    public void setBlockRange(int x, int y, int z, int y2, BlockState state, Predicate<BlockState> test) {
        buffer.fillWhere(worldX(x), worldZ(z), y, y2 - 1, state, test);
    }

    public void setBlockRangeToAir(int x, int y, int z, int y2) {
        buffer.fill(worldX(x), worldZ(z), y, y2 - 1, Blocks.AIR.defaultBlockState());
    }

    public void setBlockRangeToAir(int x, int y, int z, int y2, Predicate<BlockState> test) {
        buffer.fillWhere(worldX(x), worldZ(z), y, y2 - 1, Blocks.AIR.defaultBlockState(), test);
    }

    private int worldX(int x) {
        return x + (primer.getPos().x() << 4);
    }

    private int worldZ(int z) {
        return z + (primer.getPos().z() << 4);
    }

    private boolean isThisChunk(BlockPos pos) {
        int px = pos.getX() >> 4;
        int pz = pos.getZ() >> 4;
        return px == cx && pz == cz;
    }

//    private void validate() {
//        if (current.getX() < 0 || current.getY() < 0 || current.getZ() < 0) {
//            throw new RuntimeException("current: " + current.getX() + "," + current.getY() + "," + current.getZ());
//        }
//        if (current.getX() > 15 || current.getY() > 255 || current.getZ() > 15) {
//            throw new RuntimeException("current: " + current.getX() + "," + current.getY() + "," + current.getZ());
//        }
//    }

    public ChunkDriver block(BlockState c) {
        cursor.write(c);
        return this;
    }

    public ChunkDriver add(BlockState state) {
        cursor.writeAndRise(state);
        return this;
    }

    public BlockState getBlock() {
        return cursor.read();
    }

    public BlockState getBlockDown() {
        return cursor.readBelow();
    }






    public BlockState getBlock(int x, int y, int z) {
        return getBlock(pos.set(x + (primer.getPos().x() << 4), y, z + (primer.getPos().z() << 4)));
    }
}

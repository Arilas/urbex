package dev.krona.urbex.worldgen;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.varia.Rng;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

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

    private boolean published;
    private boolean loggedLateWrite;

    /** This driver put {@code state} at the given position. */
    private void wrote(int x, int y, int z, BlockState state) {
        if (published) {
            recordLateWrite(x, y, z, state);
            return;
        }
        if (written == null) {
            written = new Long2ObjectOpenHashMap<>();
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
    private final BlockPos.MutableBlockPos current = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    /** Reused across every shape update: thread-confined, reseeded per position via Rng.posSeed. */
    private final XoroshiroRandomSource shapeRandom = new XoroshiroRandomSource(0);
    /** Where writes accumulate until {@link #flushToChunk}. */
    private ChunkBuffer buffer;
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

    public void actuallyGenerate(ChunkAccess chunk) {
        correctionsPass();
        flushToChunk(chunk);
        // Full recompute instead of the old fake-bedrock Heightmap.update calls, which could
        // only ever raise heights and lied to MOTION_BLOCKING_NO_LEAVES (issue #46). O(chunk),
        // once, unconditionally correct in both directions.
        Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(
                Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
        publishRecordedWrites();
    }

    /**
     * The relocated {@link #correct}: connection properties and neighbour shape updates used to
     * run for every single write - four updateShape calls and a RandomSource allocation per
     * block, mostly against half-built state that later writes overwrote (issue #34). Now they
     * run once per finally-written position, against the finished chunk, in sorted order so the
     * result cannot depend on write order.
     */
    private void correctionsPass() {
        if (written == null || written.isEmpty()) {
            return;
        }
        long[] positions = written.keySet().toLongArray();
        Arrays.sort(positions);
        for (long packed : positions) {
            current.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            BlockState state = getBlock(current);
            BlockState corrected = correct(state);
            if (corrected != null && corrected != state) {
                setBlock(current, corrected);
            }
        }
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

    public ChunkDriver current(int x, int y, int z) {
        current.set(x + (primer.getPos().x() << 4), y, z + (primer.getPos().z() << 4));
        return this;
    }

    public ChunkDriver currentAbsolute(BlockPos pos) {
        current.set(pos);
        return this;
    }

    public ChunkDriver currentRelative(BlockPos pos) {
        current(pos.getX(), pos.getY(), pos.getZ());
        return this;
    }

    public BlockPos getCurrentCopy() {
        return current.immutable();
    }

    public BlockPos.MutableBlockPos getCurrent() {
        return current;
    }

    public void incY() {
        current.setY(current.getY()+1);
    }

    public void decY() {
        current.setY(current.getY()-1);
    }

    public void incX() {
        current.setX(current.getX()+1);
    }

    public void incZ() {
        current.setZ(current.getZ()+1);
    }

    public int getX() {
        return current.getX();
    }

    public int getY() {
        return current.getY();
    }

    public int getZ() {
        return current.getZ();
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

    /**
     * True when this position sits on the edge of its own chunk, so at least one of its four
     * horizontal neighbours lives in the next chunk along.
     */
    private static boolean isOnChunkBoundary(BlockPos pos) {
        int lx = pos.getX() & 0xf;
        int lz = pos.getZ() & 0xf;
        return lx == 0 || lx == 15 || lz == 0 || lz == 15;
    }

    /**
     * Shape-updates the in-chunk neighbour at {@code pos} against the newly placed {@code state}
     * and returns the neighbour's (possibly updated) state for the placed block's own
     * connection decisions.
     * <p>
     * Returns {@code null} - "unknown" - for positions outside the chunk being generated, and
     * touches nothing. It used to read the neighbouring chunk (whose content depends on whether
     * that chunk's features ran yet) and even write into it when it happened to be FULL; both
     * made generated output depend on worker-thread timing - the run-to-run digest divergence
     * behind issue #24. Border blocks are marked for vanilla postprocessing right here, below,
     * instead of being shape-updated - which recomputes their connections from final neighbour
     * data once every chunk involved has finished generating.
     */
    private BlockState updateAdjacent(BlockState state, Direction direction, BlockPos pos, ChunkAccess thisChunk) {
        if (!isThisChunk(pos)) {
            return null;
        }
        BlockState adjacent = getBlock(pos);
        if (adjacent.getBlock() instanceof LadderBlock) {
            return adjacent;
        }
        if (isOnChunkBoundary(pos)) {
            // updateShape consults this block's own outward neighbour, which lives in the next
            // chunk. At the carver stage the write radius is 0, so that read is forbidden - and
            // the neighbour is not finished anyway. Defer instead: vanilla recomputes the
            // connections from final neighbour data when the chunk is postprocessed, the same
            // mechanism vanilla structures use across chunk borders. Marking here rather than
            // relying on correct()'s mark is deliberate: that one only fires for positions the
            // driver itself writes, and this position may be untouched terrain.
            //
            // Skip air and LiquidBlock: LevelChunk.postProcessGeneration only calls
            // updateFromNeighbourShapes for the else branch of "is this a liquid" - a LiquidBlock
            // position is ticked instead, never shape-updated, so marking one buys nothing and
            // costs a tick this position would not otherwise have had during generation (e.g.
            // untouched water at a chunk edge starting to flow into an adjacent excavated street).
            // Air always resolves back to air, so marking it is pure waste. Neither case loses a
            // shape update, since neither would have received one anyway.
            if (!adjacent.isAir() && !(adjacent.getBlock() instanceof LiquidBlock)) {
                thisChunk.markPosForPostProcessing(pos.immutable());
            }
            return adjacent;
        }
        BlockState newAdjacent = null;
        try {
            // updateShape hands the block a RandomSource; almost none use it, but the level's own
            // source is shared across every chunk being generated, so address one on this
            // position - by reseeding the reused instance, not allocating per block (issue #34)
            //
            // One stream per position, deliberately shared across the four directions, because the
            // reshaped block is the same block. `pos` is the neighbour being reshaped, not the
            // corrected block, so a position P that neighbours several corrected blocks is entered
            // once per corrected neighbour and every entry gets the identical stream - same
            // address, same purpose. That is the invariant, not an oversight (issue #30): keying on
            // direction as well would make a block's shape depend on which of its neighbours was
            // corrected first, i.e. on correction order, which is the one thing the whole addressed
            // -RNG design exists to keep out of generation. Anyone adding direction to this address
            // is trading determinism for independence no vanilla block asks for.
            shapeRandom.setSeed(Rng.posSeed(seed, pos.getX(), pos.getY(), pos.getZ(), Rng.Purpose.SHAPE));
            newAdjacent = adjacent.updateShape(region, region, pos, direction, pos.relative(direction), state, shapeRandom);
        } catch (Exception e) {
            // We got an exception. For example for beehives there can potentially be a problem so in this case we just ignore it
            return adjacent;
        }
        if (newAdjacent != adjacent) {
            setBlock(pos, newAdjacent);
        }
        return newAdjacent;
    }

    public static boolean isBlockStairs(BlockState state) {
        return state.getBlock() instanceof StairBlock;
    }

    /**
     * In-chunk cache read; AIR for positions outside the chunk being generated. The constant
     * placeholder keeps shape decisions deterministic - a real read of the neighbouring chunk
     * would return different content depending on whether its features ran yet. Border blocks
     * are marked for postprocessing, which recomputes shapes from the final neighbours.
     */
    private BlockState getBlockDeterministic(BlockPos p) {
        return isThisChunk(p) ? getBlock(p) : Blocks.AIR.defaultBlockState();
    }

    private boolean isDifferentStairs(BlockState state, BlockPos pos, Direction face) {
        BlockPos relative = pos.relative(face);
        BlockState blockstate = getBlockDeterministic(relative);
        return !isBlockStairs(blockstate) || blockstate.getValue(StairBlock.FACING) != state.getValue(StairBlock.FACING) || blockstate.getValue(StairBlock.HALF) != state.getValue(StairBlock.HALF);
    }

    private StairsShape getShapeProperty(BlockState state, BlockPos pos) {
        Direction direction = state.getValue(StairBlock.FACING);
        BlockPos relative = pos.relative(direction);
        BlockState blockstate = getBlockDeterministic(relative);
        if (isBlockStairs(blockstate) && state.getValue(StairBlock.HALF) == blockstate.getValue(StairBlock.HALF)) {
            Direction direction1 = blockstate.getValue(StairBlock.FACING);
            if (direction1.getAxis() != state.getValue(StairBlock.FACING).getAxis() && isDifferentStairs(state, pos, direction1.getOpposite())) {
                if (direction1 == direction.getCounterClockWise()) {
                    return StairsShape.OUTER_LEFT;
                }

                return StairsShape.OUTER_RIGHT;
            }
        }

        BlockPos relativeOpposite = pos.relative(direction.getOpposite());
        BlockState blockstate1 = getBlockDeterministic(relativeOpposite);
        if (isBlockStairs(blockstate1) && state.getValue(StairBlock.HALF) == blockstate1.getValue(StairBlock.HALF)) {
            Direction direction2 = blockstate1.getValue(StairBlock.FACING);
            if (direction2.getAxis() != state.getValue(StairBlock.FACING).getAxis() && isDifferentStairs(state, pos, direction2)) {
                if (direction2 == direction.getCounterClockWise()) {
                    return StairsShape.INNER_LEFT;
                }

                return StairsShape.INNER_RIGHT;
            }
        }

        return StairsShape.STRAIGHT;
    }

    private static WallSide canAttachWall(BlockState state) {
        return canAttach(state) ? WallSide.LOW : WallSide.NONE;
    }

    private static boolean canAttach(BlockState state) {
        if (state == null || state.isAir()) {
            // null: the neighbour is in another chunk and deliberately unknown during
            // generation - no connection now, postprocessing recomputes it later
            return false;
        }
        if (state.canOcclude()) {
            return true;
        }
        return !Block.isExceptionForConnection(state);
    }

    private BlockState correct(BlockState state) {
        if (state == null) {
            // A caller could not resolve a palette character. setBlock() already treats null
            // as "leave what is there", so stop before dereferencing it. Whoever produced the
            // null is responsible for reporting which asset is at fault.
            return null;
        }
        int cx = current.getX();
        int cy = current.getY();
        int cz = current.getZ();

        ChunkAccess thisChunk = region.getChunk(cx >> 4, cz >> 4);
        BlockState westState = updateAdjacent(state, Direction.EAST, pos.set(cx - 1, cy, cz), thisChunk);
        BlockState eastState = updateAdjacent(state, Direction.WEST, pos.set(cx + 1, cy, cz), thisChunk);
        BlockState northState = updateAdjacent(state, Direction.SOUTH, pos.set(cx, cy, cz - 1), thisChunk);
        BlockState southState = updateAdjacent(state, Direction.NORTH, pos.set(cx, cy, cz + 1), thisChunk);

        // A border block could not see (or update) its out-of-chunk neighbours; have vanilla
        // recompute its connections from the final neighbour data when the chunk is
        // postprocessed - the same mechanism vanilla structures use across chunk borders.
        int lx = cx & 0xf;
        int lz = cz & 0xf;
        if (lx == 0 || lx == 15 || lz == 0 || lz == 15) {
            thisChunk.markPosForPostProcessing(pos.set(cx, cy, cz));
        }

        if (state.getBlock() instanceof CrossCollisionBlock) {
            state = state.setValue(CrossCollisionBlock.WEST, canAttach(westState));
            state = state.setValue(CrossCollisionBlock.EAST, canAttach(eastState));
            state = state.setValue(CrossCollisionBlock.NORTH, canAttach(northState));
            state = state.setValue(CrossCollisionBlock.SOUTH, canAttach(southState));
        } else if (state.getBlock() instanceof WallBlock) {
            state = state.setValue(WallBlock.WEST, canAttachWall(westState));
            state = state.setValue(WallBlock.EAST, canAttachWall(eastState));
            state = state.setValue(WallBlock.NORTH, canAttachWall(northState));
            state = state.setValue(WallBlock.SOUTH, canAttachWall(southState));
        } else if (state.getBlock() instanceof StairBlock) {
            state = state.setValue(StairBlock.SHAPE, getShapeProperty(state, pos.set(cx, cy, cz)));
        } else if (state.getBlock() instanceof StructureVoidBlock){
            //like an alpha channel - but for parts! Uses whatever block was previously there instead of changing it!
            return null;
        }
        return state;
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
        setBlock(current, c);
        return this;
    }

    public ChunkDriver add(BlockState state) {
        setBlock(current, state);
        incY();
        return this;
    }

    public BlockState getBlock() {
        return getBlock(current);
    }

    public BlockState getBlockDown() {
        return getBlock(pos.set(current.getX(), current.getY()-1, current.getZ()));
    }






    public BlockState getBlock(int x, int y, int z) {
        return getBlock(pos.set(x + (primer.getPos().x() << 4), y, z + (primer.getPos().z() << 4)));
    }
}

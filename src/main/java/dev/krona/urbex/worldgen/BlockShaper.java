package dev.krona.urbex.worldgen;

import dev.krona.urbex.varia.Rng;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StructureVoidBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

import javax.annotation.Nullable;

/**
 * Resolves what a placed block connects to, once the chunk around it is finished.
 *
 * <p>Fences, walls, panes and stairs carry their neighbours in their own blockstate, so a block
 * placed before its neighbours exist is placed with the wrong one. This settles that: given a
 * position and the state written there, it returns the state that position should end up with, and
 * shape-updates the four horizontal neighbours against it on the way.</p>
 *
 * <p>Extracted from {@link ChunkDriver}, which ran it inline for every single write - four
 * {@code updateShape} calls and a {@code RandomSource} allocation per block, mostly against
 * half-built state that later writes overwrote (issue #34). It now runs once per finally-written
 * position, in sorted order, so the result cannot depend on write order; separating it from the
 * driver is issue #52.</p>
 *
 * <p>Thread-confined, like everything else on the generation path: one shaper per driver, per chunk,
 * per thread. The {@link XoroshiroRandomSource} below is reused rather than allocated because of
 * that, and reseeded per position.</p>
 */
final class BlockShaper {

    /**
     * The chunk being generated, as this class needs to see it: read-through to the world, writable,
     * and able to say whether a position is inside it.
     */
    interface ChunkView {
        /** What is at {@code pos} now - the pending write if there is one, else the world. */
        BlockState get(BlockPos pos);

        void set(BlockPos pos, BlockState state);

        /** Whether {@code pos} is in the chunk being generated. */
        boolean contains(BlockPos pos);
    }

    private final ChunkView chunk;
    private final LevelAccessor region;
    private final long seed;

    /** Reused across every shape update: thread-confined, reseeded per position via Rng.posSeed. */
    private final XoroshiroRandomSource shapeRandom = new XoroshiroRandomSource(0);
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    BlockShaper(ChunkView chunk, LevelAccessor region, long seed) {
        this.chunk = chunk;
        this.region = region;
        this.seed = seed;
    }

    /**
     * The state {@code x,y,z} should hold, given that {@code state} was written there and the chunk
     * around it is now finished. Returns null for "leave whatever is already there".
     *
     * <p>Shape-updates the four horizontal neighbours as a side effect, which is why it is called
     * once per written position rather than per written block: the neighbour updates are writes
     * too.</p>
     */
    @Nullable
    BlockState correct(@Nullable BlockState state, int x, int y, int z) {
        if (state == null) {
            // A caller could not resolve a palette character. The write path already treats null
            // as "leave what is there", so stop before dereferencing it. Whoever produced the
            // null is responsible for reporting which asset is at fault.
            return null;
        }
        ChunkAccess thisChunk = region.getChunk(x >> 4, z >> 4);
        BlockState westState = updateAdjacent(state, Direction.EAST, scratch.set(x - 1, y, z), thisChunk);
        BlockState eastState = updateAdjacent(state, Direction.WEST, scratch.set(x + 1, y, z), thisChunk);
        BlockState northState = updateAdjacent(state, Direction.SOUTH, scratch.set(x, y, z - 1), thisChunk);
        BlockState southState = updateAdjacent(state, Direction.NORTH, scratch.set(x, y, z + 1), thisChunk);

        // A border block could not see (or update) its out-of-chunk neighbours; have vanilla
        // recompute its connections from the final neighbour data when the chunk is
        // postprocessed - the same mechanism vanilla structures use across chunk borders.
        if (isOnChunkBoundary(x, z)) {
            thisChunk.markPosForPostProcessing(scratch.set(x, y, z));
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
            state = state.setValue(StairBlock.SHAPE, getShapeProperty(state, scratch.set(x, y, z)));
        } else if (state.getBlock() instanceof StructureVoidBlock) {
            //like an alpha channel - but for parts! Uses whatever block was previously there instead of changing it!
            return null;
        }
        return state;
    }

    /**
     * True when this position sits on the edge of its own chunk, so at least one of its four
     * horizontal neighbours lives in the next chunk along.
     */
    private static boolean isOnChunkBoundary(int x, int z) {
        int lx = x & 0xf;
        int lz = z & 0xf;
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
        if (!chunk.contains(pos)) {
            return null;
        }
        BlockState adjacent = chunk.get(pos);
        if (adjacent.getBlock() instanceof LadderBlock) {
            return adjacent;
        }
        if (isOnChunkBoundary(pos.getX(), pos.getZ())) {
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
            chunk.set(pos, newAdjacent);
        }
        return newAdjacent;
    }

    private static boolean isBlockStairs(BlockState state) {
        return state.getBlock() instanceof StairBlock;
    }

    /**
     * In-chunk read; AIR for positions outside the chunk being generated. The constant
     * placeholder keeps shape decisions deterministic - a real read of the neighbouring chunk
     * would return different content depending on whether its features ran yet. Border blocks
     * are marked for postprocessing, which recomputes shapes from the final neighbours.
     */
    private BlockState getBlockDeterministic(BlockPos p) {
        return chunk.contains(p) ? chunk.get(p) : Blocks.AIR.defaultBlockState();
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
}

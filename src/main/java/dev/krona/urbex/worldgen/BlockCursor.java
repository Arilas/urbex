package dev.krona.urbex.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A position in the chunk being generated, and the writes made at it.
 *
 * <p>Most of this mod's block placement is a vertical run - put a block, step up, put another - and
 * the cursor is what that idiom is written against: {@code at(x, y, z)} then {@code add(state)} as
 * many times as there are blocks. Roughly 150 call sites across the generator and the {@code gen}
 * package use it, and it is a genuinely good fit for what they express.</p>
 *
 * <p>What it should not be is a field on {@link ChunkDriver}. The driver is a read-through view of
 * one chunk; the cursor is a walk over it. They were the same object, so any method could move the
 * position under any other method's feet, and "which block does this write" was a question about the
 * driver's history rather than about the call (issue #52).</p>
 *
 * <p>Positions are chunk-local on the way in and absolute from there on: {@link #at} adds the chunk
 * origin once, so nothing downstream has to remember which convention it is holding.</p>
 *
 * <p>Thread-confined, like everything else on the generation path: one cursor per driver, per chunk,
 * per thread.</p>
 */
final class BlockCursor {

    /** The chunk this cursor walks, addressed absolutely. */
    interface Blocks {
        void set(int x, int y, int z, BlockState state);

        BlockState get(int x, int y, int z);
    }

    private final Blocks blocks;
    private final int originX;
    private final int originZ;
    private final BlockPos.MutableBlockPos current = new BlockPos.MutableBlockPos();

    BlockCursor(Blocks blocks, int originX, int originZ) {
        this.blocks = blocks;
        this.originX = originX;
        this.originZ = originZ;
    }

    /** Moves to a chunk-local position. */
    void at(int x, int y, int z) {
        current.set(x + originX, y, z + originZ);
    }

    /** Moves to an absolute position. */
    void atAbsolute(BlockPos pos) {
        current.set(pos);
    }

    int x() {
        return current.getX();
    }

    int y() {
        return current.getY();
    }

    int z() {
        return current.getZ();
    }

    /**
     * The position itself.
     *
     * <p>Mutable, and shared: it is this cursor's own field, so a caller that keeps it sees the
     * cursor move. Every caller either reads it immediately or copies it - {@link #copy} is the one
     * to reach for otherwise.
     */
    BlockPos.MutableBlockPos position() {
        return current;
    }

    BlockPos copy() {
        return current.immutable();
    }

    void up() {
        current.setY(current.getY() + 1);
    }

    void down() {
        current.setY(current.getY() - 1);
    }

    void east() {
        current.setX(current.getX() + 1);
    }

    void south() {
        current.setZ(current.getZ() + 1);
    }

    /** Writes at the current position and stays there. */
    void write(BlockState state) {
        blocks.set(current.getX(), current.getY(), current.getZ(), state);
    }

    /** Writes at the current position and steps up, for building a column. */
    void writeAndRise(BlockState state) {
        write(state);
        up();
    }

    BlockState read() {
        return blocks.get(current.getX(), current.getY(), current.getZ());
    }

    BlockState readBelow() {
        return blocks.get(current.getX(), current.getY() - 1, current.getZ());
    }
}

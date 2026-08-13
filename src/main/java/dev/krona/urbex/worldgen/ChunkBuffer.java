package dev.krona.urbex.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.StructureVoidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * Somewhere for one chunk's blocks to accumulate before any of them reach the world.
 *
 * <p>Write-behind, by section: a write lands in a plain {@code BlockState[]} and nothing touches the
 * level until {@link #flush}. That is what lets a generation that fails partway leave the chunk as
 * pure vanilla terrain, and it is why only sections that were actually written are copied out - a
 * section this chunk merely read from is not rewritten with the values it already held.</p>
 *
 * <p><strong>Explicit positions, in world coordinates.</strong> Every entry point names the block it
 * acts on. This used to be an inner class of {@link ChunkDriver} reading the driver's cursor and
 * calling back into it to log writes, so "which block does this write" was a question about the
 * driver's current state rather than about the call (issue #52). Horizontal coordinates are masked
 * into the owning chunk, as they always were: the callers hand it either chunk-local coordinates
 * already offset by the chunk origin, or an absolute position inside the same chunk.</p>
 *
 * <p>Thread-confined. One buffer belongs to one {@link ChunkDriver}, which belongs to one call of
 * {@code CityGenerator.generate()} on one thread and never escapes it, so nothing here is
 * synchronised.</p>
 */
final class ChunkBuffer {

    // Section constants, formerly on LevelChunkSection (removed in 26.2).
    private static final int SECTION_WIDTH = 16;
    private static final int SECTION_HEIGHT = 16;
    private static final int SECTION_SIZE = SECTION_WIDTH * SECTION_WIDTH * SECTION_HEIGHT;

    /**
     * Told about every write this buffer accepted.
     *
     * <p>Accepted, not applied: {@link #set} and {@link #fill} report a position even when the slot
     * already held that exact state, because the log is what the chunk claims to have placed rather
     * than a record of which array slots changed. The end-of-chunk corrections pass walks it, and a
     * block whose neighbour was written twice still needs its connections resolved once.</p>
     */
    @FunctionalInterface
    interface WriteLog {
        void wrote(int x, int y, int z, BlockState state);
    }

    /** What the world holds at a position this buffer has never seen. */
    @FunctionalInterface
    interface WorldView {
        BlockState at(BlockPos pos);
    }

    private static final class Section {
        private final BlockState[] blocks = new BlockState[SECTION_SIZE];
        /** AIR is still a write: it may need to erase terrain already present in the chunk. */
        private boolean written;
    }

    private final WriteLog log;
    private final WorldView world;
    private final int minY;
    private final int sections;
    private final int originX;
    private final int originZ;
    private final Section[] cache;

    /** Reused by {@link #fillWhere}'s fallback read; created on first use, never escapes. */
    private BlockPos.MutableBlockPos scratch;

    ChunkBuffer(WriteLog log, WorldView world, int minY, int maxY, int originX, int originZ) {
        this.log = log;
        this.world = world;
        this.minY = minY;
        this.sections = (maxY - minY) / SECTION_HEIGHT;
        this.originX = originX;
        this.originZ = originZ;
        this.cache = new Section[sections];
        clear();
    }

    /**
     * Puts one block.
     *
     * <p>Ignored when {@code state} is null - a caller could not resolve a palette character, and
     * "leave what is there" is the documented meaning - or when it is a structure void, which is the
     * asset format's alpha channel and means the same thing. Filtering both here rather than at each
     * call site is what stopped bulk fills placing literal {@code structure_void} blocks.</p>
     */
    void set(int x, int y, int z, BlockState state) {
        if (isTransparent(state)) {
            return;
        }
        int sectionIdx = (y - minY) / SECTION_HEIGHT;
        int idx = index(x, y, z);
        Section section = cache[sectionIdx];
        if (section.blocks[idx] != state) {
            section.blocks[idx] = state;
            section.written = true;
        }
        log.wrote(x, y, z, state);
    }

    /** Puts a vertical run, {@code y1} to {@code y2} inclusive. */
    void fill(int x, int z, int y1, int y2, BlockState state) {
        if (isTransparent(state)) {
            return;
        }
        for (int y = y1; y <= y2; y++) {
            int sectionIdx = (y - minY) / SECTION_HEIGHT;
            int idx = index(x, y, z);
            Section section = cache[sectionIdx];
            if (section.blocks[idx] != state) {
                section.blocks[idx] = state;
                section.written = true;
            }
            log.wrote(x, y, z, state);
        }
    }

    /**
     * Puts a vertical run only where {@code test} accepts what is already there.
     *
     * <p>Positions this chunk has not touched are read from the world rather than skipped. Skipping
     * them made this a no-op on virgin terrain: the highway clear-above passes test vanilla blocks,
     * which are never in the buffer, so highways were not cleared of terrain at all (issue #35).</p>
     */
    void fillWhere(int x, int z, int y1, int y2, BlockState state, Predicate<BlockState> test) {
        if (isTransparent(state)) {
            return;
        }
        for (int y = y1; y <= y2; y++) {
            int sectionIdx = (y - minY) / SECTION_HEIGHT;
            int idx = index(x, y, z);
            Section section = cache[sectionIdx];
            BlockState existing = section.blocks[idx];
            if (existing == null) {
                if (scratch == null) {
                    scratch = new BlockPos.MutableBlockPos();
                }
                existing = world.at(scratch.set(originX + (x & 0xf), y, originZ + (z & 0xf)));
            }
            if (existing != state && test.test(existing)) {
                section.blocks[idx] = state;
                section.written = true;
                log.wrote(x, y, z, state);
            }
        }
    }

    /** What this buffer holds at a position, or null if it has never seen it. */
    @Nullable
    BlockState get(int x, int y, int z) {
        return cache[(y - minY) / SECTION_HEIGHT].blocks[index(x, y, z)];
    }

    /**
     * Remembers what the world already holds at a position, without claiming this chunk wrote it.
     *
     * <p>The distinction is the whole reason this is not {@link #set}: {@code set} marks the section
     * written, so a section Urbex only <em>read</em> from was flushed back in full at the end of the
     * chunk - 4096 slots of terrain rewritten with the values they already had (issue #52).</p>
     */
    void remember(int x, int y, int z, BlockState state) {
        cache[(y - minY) / SECTION_HEIGHT].blocks[index(x, y, z)] = state;
    }

    /** Copies every written section into the world. */
    void flush(BulkSectionAccess bulk) {
        for (int si = 0; si < sections; si++) {
            Section section = cache[si];
            if (!section.written) {
                continue;
            }
            int cy = si * SECTION_HEIGHT + minY;
            LevelChunkSection target = bulk.getSection(new BlockPos(originX, cy, originZ));
            if (target == null) {
                throw new RuntimeException("This cannot happen: " + si);
            }
            int i = 0;
            for (int x = 0; x < SECTION_WIDTH; x++) {
                for (int y = 0; y < SECTION_HEIGHT; y++) {
                    for (int z = 0; z < SECTION_WIDTH; z++) {
                        BlockState state = section.blocks[i++];
                        if (state != null) {
                            target.setBlockState(x, y, z, state, false);
                        }
                    }
                }
            }
        }
    }

    void clear() {
        for (int si = 0; si < sections; si++) {
            cache[si] = new Section();
        }
    }

    /** Null and structure void both mean "leave whatever is already there". */
    private static boolean isTransparent(BlockState state) {
        return state == null || state.getBlock() instanceof StructureVoidBlock;
    }

    private static int index(int x, int y, int z) {
        return ((x & 0xf) << 8) + ((y & 0xf) << 4) + (z & 0xf);
    }
}

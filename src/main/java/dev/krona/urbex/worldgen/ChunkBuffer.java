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
    /**
     * The lowest and highest Y this buffer will accept a block at, inclusive.
     *
     * <p>Ordinarily the level's own bounds, in which case these reject nothing. A
     * {@link SiteBinding site} narrows them to its window, and this is where that window stops being
     * a convention and becomes a guarantee: every driver write in the mod passes through
     * {@link #set}, {@link #fill} or {@link #fillWhere}, so a pass that believes it may build to the
     * sky writes nothing above the window whatever it believes. The alternative - clamping at each
     * of the eighteen generation passes - is a rule someone has to keep, and the nineteenth pass
     * breaks it silently, in a world nobody is looking at, hundreds of blocks from the caller who
     * asked for a bunker.</p>
     */
    private final int writeMinY;
    private final int writeMaxY;
    /**
     * One slot per section, filled on first write to that section.
     *
     * <p>Lazily, and that is the point: a chunk touches a handful of the level's 24 sections, but
     * this used to allocate every one of them up front - 24 {@code BlockState[4096]} per chunk,
     * about 800 KiB, most of it never read. {@link #clear} allocated them all again, including the
     * call at the end of {@code actuallyGenerate} where the buffer is about to be discarded
     * (issue #132).</p>
     */
    private final Section[] cache;

    /** Reused by {@link #fillWhere}'s fallback read; created on first use, never escapes. */
    private BlockPos.MutableBlockPos scratch;

    /** A buffer that will write anywhere in the level. */
    ChunkBuffer(WriteLog log, WorldView world, int minY, int maxY, int originX, int originZ) {
        this(log, world, minY, maxY, originX, originZ, minY, maxY - 1);
    }

    /**
     * @param minY      the level's lowest block Y, which sizes the section table
     * @param maxY      one past the level's highest block Y, likewise
     * @param writeMinY the lowest Y this buffer accepts a write at, inclusive
     * @param writeMaxY the highest Y this buffer accepts a write at, inclusive
     */
    ChunkBuffer(WriteLog log, WorldView world, int minY, int maxY, int originX, int originZ,
                int writeMinY, int writeMaxY) {
        this.log = log;
        this.world = world;
        this.minY = minY;
        this.sections = (maxY - minY) / SECTION_HEIGHT;
        this.originX = originX;
        this.originZ = originZ;
        // Intersected with the level rather than trusted: a caller may name a window wider than the
        // dimension, and the section table is sized for the dimension.
        this.writeMinY = Math.max(writeMinY, minY);
        this.writeMaxY = Math.min(writeMaxY, maxY - 1);
        this.cache = new Section[sections];
    }

    /** Whether this buffer will accept a write at {@code y}. */
    private boolean inWindow(int y) {
        return y >= writeMinY && y <= writeMaxY;
    }

    /** The section holding {@code y}, created if this is the first write to it. */
    private Section sectionFor(int y) {
        int index = (y - minY) / SECTION_HEIGHT;
        Section section = cache[index];
        if (section == null) {
            section = new Section();
            cache[index] = section;
        }
        return section;
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
        if (isTransparent(state) || !inWindow(y)) {
            return;
        }
        int idx = index(x, y, z);
        Section section = sectionFor(y);
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
        // Clamped once rather than tested per block: a run reaching from a cellar floor to a roof
        // crosses the window boundary at most twice, and the loop is hot.
        for (int y = Math.max(y1, writeMinY), top = Math.min(y2, writeMaxY); y <= top; y++) {
            int idx = index(x, y, z);
            Section section = sectionFor(y);
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
        for (int y = Math.max(y1, writeMinY), top = Math.min(y2, writeMaxY); y <= top; y++) {
            int idx = index(x, y, z);
            Section section = sectionFor(y);
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

    /**
     * Whether this buffer has never touched the section holding {@code y} - neither written to it
     * nor remembered a world block in it.
     *
     * <p>Both count, and they are the same test because both go through {@link #sectionFor}: a
     * remembered block is a block this buffer is now the authority on, so a caller cannot conclude
     * anything about the section from the world alone once one is in there. Out-of-range y answers
     * false rather than throwing - the caller is scanning and a coordinate past the world is simply
     * not skippable.</p>
     */
    boolean sectionUntouched(int y) {
        int index = (y - minY) / SECTION_HEIGHT;
        return index >= 0 && index < sections && cache[index] == null;
    }

    /** The lowest {@code y} in the section holding {@code y}. */
    int sectionBottom(int y) {
        return minY + ((y - minY) / SECTION_HEIGHT) * SECTION_HEIGHT;
    }

    /** What this buffer holds at a position, or null if it has never seen it. */
    @Nullable
    BlockState get(int x, int y, int z) {
        Section section = cache[(y - minY) / SECTION_HEIGHT];
        return section == null ? null : section.blocks[index(x, y, z)];
    }

    /**
     * Remembers what the world already holds at a position, without claiming this chunk wrote it.
     *
     * <p>The distinction is the whole reason this is not {@link #set}: {@code set} marks the section
     * written, so a section Urbex only <em>read</em> from was flushed back in full at the end of the
     * chunk - 4096 slots of terrain rewritten with the values they already had (issue #52).</p>
     */
    void remember(int x, int y, int z, BlockState state) {
        // Windowed too, though a remembered block is a read rather than a write. A section that
        // straddles the boundary is flushed in full once anything inside the window writes to it,
        // and every non-null slot in a flushed section is copied out - so an out-of-window slot
        // holding a remembered value would be written back to the world. Harmless today, because
        // what it writes back is what the world already holds; not something the window's guarantee
        // should rest on. Dropping it costs a re-read at most.
        if (!inWindow(y)) {
            return;
        }
        sectionFor(y).blocks[index(x, y, z)] = state;
    }

    /** Copies every written section into the world. */
    void flush(BulkSectionAccess bulk) {
        for (int si = 0; si < sections; si++) {
            Section section = cache[si];
            if (section == null || !section.written) {
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

    /**
     * Forgets everything, for the mid-generation flush a part2 floor needs (issue #48).
     *
     * <p>Nulls the slots rather than replacing them with empty sections: a buffer that is cleared
     * and then discarded should cost nothing, and one that is cleared and reused allocates again
     * only for the sections it touches the second time round.</p>
     */
    void clear() {
        java.util.Arrays.fill(cache, null);
    }

    /** Null and structure void both mean "leave whatever is already there". */
    private static boolean isTransparent(BlockState state) {
        return state == null || state.getBlock() instanceof StructureVoidBlock;
    }

    private static int index(int x, int y, int z) {
        return ((x & 0xf) << 8) + ((y & 0xf) << 4) + (z & 0xf);
    }
}

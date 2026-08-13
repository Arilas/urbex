package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

/**
 * Where the cursor is, and what it writes there.
 *
 * <p>These were unobservable while the position was a field on {@link ChunkDriver}: asking what a
 * vertical run had placed meant generating a chunk and reading the world back (issue #52).</p>
 */
class BlockCursorTest {

    private final Map<BlockPos, BlockState> written = new HashMap<>();
    private final List<String> order = new ArrayList<>();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A cursor over the chunk at (2, -3), so the origin offset is not zero and cannot hide. */
    private BlockCursor cursor() {
        return new BlockCursor(new BlockCursor.Blocks() {
            @Override
            public void set(int x, int y, int z, BlockState state) {
                written.put(new BlockPos(x, y, z), state);
                order.add(x + "," + y + "," + z);
            }

            @Override
            public BlockState get(int x, int y, int z) {
                return written.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }, 2 << 4, -3 << 4);
    }

    @Test
    void aChunkLocalPositionIsOffsetByTheChunkOriginExactlyOnce() {
        BlockCursor cursor = cursor();

        cursor.at(5, 64, 9);

        assertEquals(2 * 16 + 5, cursor.x());
        assertEquals(64, cursor.y());
        assertEquals(-3 * 16 + 9, cursor.z());
    }

    @Test
    void anAbsolutePositionIsNotOffsetAtAll() {
        BlockCursor cursor = cursor();

        cursor.atAbsolute(new BlockPos(100, 70, -200));

        assertEquals(100, cursor.x());
        assertEquals(-200, cursor.z());
    }

    /** The vertical-run idiom the whole cursor exists for. */
    @Test
    void writingAndRisingBuildsAColumnUpwards() {
        BlockCursor cursor = cursor();

        cursor.at(0, 64, 0);
        cursor.writeAndRise(Blocks.STONE.defaultBlockState());
        cursor.writeAndRise(Blocks.DIRT.defaultBlockState());
        cursor.writeAndRise(Blocks.GRASS_BLOCK.defaultBlockState());

        assertIterableEquals(List.of("32,64,-48", "32,65,-48", "32,66,-48"), order);
        assertEquals(67, cursor.y(), "and the cursor is left above what it placed");
    }

    @Test
    void writingWithoutRisingStaysPut() {
        BlockCursor cursor = cursor();

        cursor.at(0, 64, 0);
        cursor.write(Blocks.STONE.defaultBlockState());
        cursor.write(Blocks.DIRT.defaultBlockState());

        assertIterableEquals(List.of("32,64,-48", "32,64,-48"), order);
        assertEquals(Blocks.DIRT.defaultBlockState(), cursor.read(), "the last write wins");
    }

    @Test
    void readBelowLooksOneBlockDownWithoutMoving() {
        BlockCursor cursor = cursor();
        cursor.at(0, 64, 0);
        cursor.write(Blocks.STONE.defaultBlockState());
        cursor.up();

        assertEquals(Blocks.STONE.defaultBlockState(), cursor.readBelow());
        assertEquals(65, cursor.y());
    }

    /**
     * The position is this cursor's own mutable field, so anything that keeps it sees the cursor
     * move. That is why {@code copy} exists, and the distinction is worth a test because the two
     * read identically at the call site.
     */
    @Test
    void aCopyIsDetachedAndThePositionIsNot() {
        BlockCursor cursor = cursor();
        cursor.at(0, 64, 0);
        BlockPos copied = cursor.copy();
        BlockPos live = cursor.position();

        cursor.up();

        assertEquals(64, copied.getY());
        assertEquals(65, live.getY());
    }
}

package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vertical window: what a buffer built for a {@link SiteBinding site} refuses.
 *
 * <p>A site names a range of Y and nothing it generates may leave it - a bunker that punches a
 * stairwell up through the surface is the failure this exists to make impossible. The refusal is
 * here, at the one point every driver write passes through, rather than at the eighteen generation
 * passes that write: a rule kept in eighteen places is a rule the nineteenth pass breaks.</p>
 *
 * <p>The unbounded case is tested alongside the bounded one on purpose. Every chunk Urbex has ever
 * generated goes through the unbounded path, so "this changed nothing for a normal world" is a
 * property worth asserting rather than inferring from the digests.</p>
 */
class ChunkBufferWindowTest {

    /** Low enough and high enough to sit inside one section, and to straddle a section boundary. */
    private static final int WINDOW_MIN = 10;
    private static final int WINDOW_MAX = 40;

    private final List<String> log = new ArrayList<>();

    private final BlockState stone = Blocks.STONE.defaultBlockState();
    private final BlockState dirt = Blocks.DIRT.defaultBlockState();
    private final BlockState air = Blocks.AIR.defaultBlockState();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private ChunkBuffer windowed(ChunkBuffer.WorldView world) {
        return new ChunkBuffer(
                (x, y, z, state) -> log.add(x + "," + y + "," + z + "="
                        + BuiltInRegistries.BLOCK.getKey(state.getBlock())),
                world, -64, 320, 0, 0, WINDOW_MIN, WINDOW_MAX);
    }

    /** Nothing in the buffer, so any read the buffer does not answer itself is a test failure. */
    private ChunkBuffer windowed() {
        return windowed(pos -> {
            throw new AssertionError("unexpected world read at " + pos);
        });
    }

    @Test
    void aWriteBelowTheWindowIsRefused() {
        ChunkBuffer buffer = windowed();

        buffer.set(1, WINDOW_MIN - 1, 1, stone);

        assertTrue(log.isEmpty(), "a block one below the window is outside it");
        assertNull(buffer.get(1, WINDOW_MIN - 1, 1));
    }

    @Test
    void aWriteAboveTheWindowIsRefused() {
        ChunkBuffer buffer = windowed();

        buffer.set(1, WINDOW_MAX + 1, 1, stone);

        assertTrue(log.isEmpty(), "a block one above the window is outside it");
        assertNull(buffer.get(1, WINDOW_MAX + 1, 1));
    }

    @Test
    void bothEdgesOfTheWindowAreInside() {
        ChunkBuffer buffer = windowed();

        buffer.set(1, WINDOW_MIN, 1, stone);
        buffer.set(1, WINDOW_MAX, 1, stone);

        assertIterableEquals(List.of("1," + WINDOW_MIN + ",1=minecraft:stone",
                "1," + WINDOW_MAX + ",1=minecraft:stone"), log,
                "the window is inclusive at both ends");
    }

    @Test
    void aFillIsClippedToTheWindowRatherThanRefused() {
        windowed().fill(1, 2, WINDOW_MIN - 5, WINDOW_MAX + 5, stone);

        assertEquals(WINDOW_MAX - WINDOW_MIN + 1, log.size(),
                "a run reaching past the window keeps the part of itself that is inside it");
        assertEquals("1," + WINDOW_MIN + ",2=minecraft:stone", log.get(0));
        assertEquals("1," + WINDOW_MAX + ",2=minecraft:stone", log.get(log.size() - 1));
    }

    @Test
    void aFillEntirelyOutsideTheWindowNeverRunsItsLoop() {
        windowed().fill(1, 2, WINDOW_MAX + 1, WINDOW_MAX + 100, stone);

        assertTrue(log.isEmpty(), "nothing in that run is inside the window");
    }

    /**
     * The conditional fill reads the world where the buffer holds nothing, so a refused position
     * must be refused before that read rather than after it. The world view throws, which is what
     * turns "before" into an assertion.
     */
    @Test
    void aConditionalFillOutsideTheWindowDoesNotEvenReadTheWorld() {
        ChunkBuffer buffer = windowed();

        buffer.fillWhere(1, 1, WINDOW_MAX + 1, WINDOW_MAX + 8, air, state -> true);

        assertTrue(log.isEmpty());
    }

    @Test
    void aConditionalFillIsClippedLikeAnUnconditionalOne() {
        ChunkBuffer buffer = windowed(pos -> {
            if (pos.getY() < WINDOW_MIN || pos.getY() > WINDOW_MAX) {
                throw new AssertionError("read the world outside the window at " + pos);
            }
            return stone;
        });

        buffer.fillWhere(1, 1, WINDOW_MIN - 3, WINDOW_MIN + 1, air, state -> state == stone);

        assertIterableEquals(List.of("1," + WINDOW_MIN + ",1=minecraft:air",
                "1," + (WINDOW_MIN + 1) + ",1=minecraft:air"), log);
    }

    /**
     * A section that straddles the window boundary is flushed in full once anything inside the
     * window writes to it, and every non-null slot in a flushed section is copied out. So a
     * remembered block above the boundary would reach the world through a write below it.
     */
    @Test
    void aStraddlingSectionFlushesOnlyItsInWindowBlocks() {
        ProtoChunk chunk = TestChunk.emptyChunk();
        BlockPos above = new BlockPos(1, WINDOW_MAX + 1, 1);
        chunk.setBlockState(above, stone, 0);
        LevelAccessor level = TestChunk.levelFor(chunk);
        ChunkBuffer buffer = windowed(level::getBlockState);

        buffer.remember(above.getX(), above.getY(), above.getZ(), dirt);
        buffer.set(1, WINDOW_MAX, 1, dirt);
        flush(buffer, level);

        assertEquals(dirt, chunk.getBlockState(new BlockPos(1, WINDOW_MAX, 1)),
                "the block inside the window is written");
        assertEquals(stone, chunk.getBlockState(above),
                "the block above it keeps what the world already held");
        assertEquals(WINDOW_MAX >> 4, above.getY() >> 4,
                "this proves nothing unless the two blocks share a section");
    }

    @Test
    void anUnboundedBufferAcceptsTheWholeLevel() {
        ChunkBuffer buffer = new ChunkBuffer(
                (x, y, z, state) -> log.add(x + "," + y + "," + z), pos -> stone, -64, 320, 0, 0);

        buffer.set(1, -64, 1, stone);
        buffer.set(1, 319, 1, stone);

        assertIterableEquals(List.of("1,-64,1", "1,319,1"), log,
                "the bottom and top blocks of the level are both writable when no window is named");
    }

    private static void flush(ChunkBuffer buffer, LevelAccessor level) {
        BulkSectionAccess bulk = new BulkSectionAccess(level);
        buffer.flush(bulk);
        bulk.close();
    }
}

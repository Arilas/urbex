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
 * What the write buffer accepts, what it reports, and what it flushes.
 *
 * <p>All three used to be answerable only by generating a chunk, because the buffer was an inner
 * class reading {@link ChunkDriver}'s cursor and calling back into it. Standing one up now needs a
 * write log and something that can answer a block read (issue #52).</p>
 */
class ChunkBufferTest {

    private final List<String> log = new ArrayList<>();

    // Instance fields, not constants: Blocks touches the registries, and a static initializer would
    // run before the @BeforeAll bootstrap that makes them legal to touch.
    private final BlockState stone = Blocks.STONE.defaultBlockState();
    private final BlockState dirt = Blocks.DIRT.defaultBlockState();
    private final BlockState air = Blocks.AIR.defaultBlockState();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private ChunkBuffer bufferOver(ChunkBuffer.WorldView world) {
        return new ChunkBuffer(
                (x, y, z, state) -> log.add(x + "," + y + "," + z + "="
                        + BuiltInRegistries.BLOCK.getKey(state.getBlock())),
                world, -64, 320, 0, 0);
    }

    /** Nothing in the buffer, so any read the buffer does not answer itself is a test failure. */
    private ChunkBuffer buffer() {
        return bufferOver(pos -> {
            throw new AssertionError("unexpected world read at " + pos);
        });
    }

    @Test
    void aWriteToASlotThatAlreadyHoldsThatStateStillClaimsThePosition() {
        ChunkBuffer buffer = buffer();

        buffer.set(1, 64, 1, stone);
        buffer.set(1, 64, 1, stone);

        assertEquals(2, log.size(),
                "the log is what this chunk placed, not which array slots changed - the corrections "
                        + "pass needs the position either way");
    }

    @Test
    void aFillClaimsEveryBlockInItsInclusiveRange() {
        buffer().fill(1, 2, 64, 66, stone);

        assertIterableEquals(List.of("1,64,2=minecraft:stone", "1,65,2=minecraft:stone", "1,66,2=minecraft:stone"), log);
    }

    @Test
    void nullAndStructureVoidAreLeftAlone() {
        ChunkBuffer buffer = buffer();

        buffer.set(1, 64, 1, null);
        buffer.set(1, 64, 1, Blocks.STRUCTURE_VOID.defaultBlockState());
        buffer.fill(1, 1, 64, 70, Blocks.STRUCTURE_VOID.defaultBlockState());

        assertTrue(log.isEmpty(), "the asset format's alpha channel keeps whatever is already there");
        assertNull(buffer.get(1, 64, 1));
    }

    @Test
    void aConditionalFillTestsTheWorldWhereTheBufferHasNothing() {
        ChunkBuffer buffer = bufferOver(pos -> stone);

        buffer.fillWhere(1, 1, 64, 64, air, BlockState::isAir);
        assertTrue(log.isEmpty(), "the world holds stone there, and the test only accepts air");

        buffer.fillWhere(1, 1, 64, 64, air, state -> state == stone);
        assertIterableEquals(List.of("1,64,1=minecraft:air"), log);
        assertEquals(air, buffer.get(1, 64, 1));
    }

    /**
     * The read-through path. Remembering a state the world does not hold is deliberate: if the flush
     * wrote it back, the assertion would see dirt.
     */
    @Test
    void aRememberedBlockIsNeverFlushed() {
        ProtoChunk chunk = TestChunk.emptyChunk();
        BlockPos read = new BlockPos(1, 64, 1);
        chunk.setBlockState(read, stone, 0);
        LevelAccessor level = TestChunk.levelFor(chunk);
        ChunkBuffer buffer = bufferOver(level::getBlockState);

        buffer.remember(read.getX(), read.getY(), read.getZ(), dirt);
        flush(buffer, level);

        assertTrue(log.isEmpty(), "a read is not a write");
        assertEquals(stone, chunk.getBlockState(read),
                "remember() must not mark the section for flushing");
    }

    @Test
    void aWrittenAirBlockIsFlushedOverExistingTerrain() {
        ProtoChunk chunk = TestChunk.emptyChunk();
        BlockPos cleared = new BlockPos(1, 64, 1);
        chunk.setBlockState(cleared, stone, 0);
        LevelAccessor level = TestChunk.levelFor(chunk);
        ChunkBuffer buffer = bufferOver(level::getBlockState);

        buffer.set(cleared.getX(), cleared.getY(), cleared.getZ(), air);
        flush(buffer, level);

        assertTrue(chunk.getBlockState(cleared).isAir(),
                "air is a write: it erases terrain the chunk already had");
    }

    private static void flush(ChunkBuffer buffer, LevelAccessor level) {
        BulkSectionAccess bulk = new BulkSectionAccess(level);
        buffer.flush(bulk);
        bulk.close();
    }
}

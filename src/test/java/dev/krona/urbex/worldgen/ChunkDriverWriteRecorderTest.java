package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the write recorder keeps, and when.
 *
 * <p>It publishes into a static map that only {@code /urbex digest} ever clears, so "when" is the
 * interesting half: a server that never runs the command must come out of a generation run with
 * nothing retained (issue #52).</p>
 */
class ChunkDriverWriteRecorderTest {

    private static final BlockPos PLACED = new BlockPos(3, 64, 3);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void forgetEarlierRuns() {
        ChunkDriver.stopRecordingWrites();
        ChunkDriver.clearRecordedWrites();
    }

    @Test
    void aRunNobodyAskedToRecordRetainsNothing() {
        driveOneBlock();

        assertEquals(0, ChunkDriver.recordedChunkCount(),
                "an unasked-for recording is a leak: nothing ever clears this map in normal play");
    }

    @Test
    void aRecordedRunPublishesTheBlockItWrote() {
        ChunkDriver.startRecordingWrites();
        try {
            driveOneBlock();
        } finally {
            ChunkDriver.stopRecordingWrites();
        }

        assertEquals(1, ChunkDriver.recordedChunkCount());
        assertArrayEquals(
                new long[]{BlockPos.asLong(PLACED.getX(), PLACED.getY(), PLACED.getZ())},
                ChunkDriver.recordedWrites(new ChunkPos(0, 0)));
        assertEquals(Blocks.STONE.defaultBlockState(),
                ChunkDriver.recordedState(new ChunkPos(0, 0),
                        BlockPos.asLong(PLACED.getX(), PLACED.getY(), PLACED.getZ())));
    }

    private static void driveOneBlock() {
        ProtoChunk chunk = TestChunk.emptyChunk();
        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        driver.current(PLACED.getX(), PLACED.getY(), PLACED.getZ())
                .block(Blocks.STONE.defaultBlockState());
        driver.actuallyGenerate(chunk);
    }
}

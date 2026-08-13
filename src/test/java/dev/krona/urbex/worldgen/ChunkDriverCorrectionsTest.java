package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which positions the end-of-chunk corrections pass visits, and what decides it.
 *
 * <p>The pass reads connection properties off finished neighbours and shape-updates them back, so
 * the set of positions it visits is part of what the mod generates - not a diagnostic. It must
 * therefore be the same set whether or not {@code /urbex digest} happens to be recording, which is
 * the property these pin (issue #52).</p>
 */
class ChunkDriverCorrectionsTest {

    private static final BlockPos NEIGHBOUR = new BlockPos(5, 64, 5);
    private static final BlockPos WALL = new BlockPos(6, 64, 5);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void aCursorWrittenWallConnectsToItsNeighbour() {
        assertEquals(WallSide.LOW, wallSideWest(false, driver ->
                        driver.current(WALL.getX(), WALL.getY(), WALL.getZ())
                                .block(Blocks.COBBLESTONE_WALL.defaultBlockState())),
                "the corrections pass exists to do exactly this");
    }

    /**
     * The bulk-fill path. This wrote the wall without ever putting it on the corrections worklist
     * unless the digest recorder was on, because {@code putRange} used the recorder's flag to decide
     * whether to note the position at all - and the worklist and the recorder were one field.
     */
    @Test
    void aBulkFilledWallConnectsTheSameWayTheCursorWrittenOneDoes() {
        assertEquals(WallSide.LOW, wallSideWest(false, driver ->
                driver.setBlockRange(WALL.getX(), WALL.getY(), WALL.getZ(), WALL.getY() + 1,
                        Blocks.COBBLESTONE_WALL.defaultBlockState())));
    }

    @Test
    void recordingTheDigestDoesNotChangeWhatIsGenerated() {
        assertEquals(
                wallSideWest(false, bulkFillTheWall()),
                wallSideWest(true, bulkFillTheWall()),
                "a digest that only agrees with itself measures the harness, not the mod");
    }

    private static java.util.function.Consumer<ChunkDriver> bulkFillTheWall() {
        return driver -> driver.setBlockRange(WALL.getX(), WALL.getY(), WALL.getZ(), WALL.getY() + 1,
                Blocks.COBBLESTONE_WALL.defaultBlockState());
    }

    /**
     * Places the wall the given way against a stone neighbour and reports the connection it ends up
     * with. The neighbour is written straight into the chunk rather than through the driver, so the
     * only position on the worklist is the wall's: a driver-written neighbour would be corrected too
     * and would shape-update the wall from the other side, which is a different path than the one
     * under test.
     */
    private static WallSide wallSideWest(boolean recording, java.util.function.Consumer<ChunkDriver> placeWall) {
        ProtoChunk chunk = TestChunk.emptyChunk();
        chunk.setBlockState(NEIGHBOUR, Blocks.STONE.defaultBlockState(), 0);

        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        if (recording) {
            ChunkDriver.startRecordingWrites();
        }
        try {
            placeWall.accept(driver);
            driver.actuallyGenerate(chunk);
        } finally {
            ChunkDriver.stopRecordingWrites();
            ChunkDriver.clearRecordedWrites();
        }
        return chunk.getBlockState(WALL).getValue(WallBlock.WEST);
    }
}

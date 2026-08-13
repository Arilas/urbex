package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDriverSectionCacheTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void airOnlySectionWritesAreFlushedOverExistingTerrain() {
        ProtoChunk chunk = TestChunk.emptyChunk();
        BlockPos cleared = new BlockPos(0, 64, 0);
        chunk.setBlockState(cleared, Blocks.STONE.defaultBlockState(), 0);

        ChunkDriver driver = new ChunkDriver();
        driver.setPrimer(TestChunk.levelFor(chunk), chunk);
        driver.current(0, cleared.getY(), 0).block(Blocks.AIR.defaultBlockState());
        driver.flushToChunk(chunk);

        assertTrue(chunk.getBlockState(cleared).isAir(),
                "an air-only cache section must still replace existing terrain");
    }
}

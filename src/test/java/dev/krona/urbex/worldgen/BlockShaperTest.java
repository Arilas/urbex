package dev.krona.urbex.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a placed block ends up connected to.
 *
 * <p>These were previously reachable only by generating a chunk through a {@link ChunkDriver}, so
 * the rules that decide a wall's four sides had no test of their own. The chunk here is a
 * {@link Map} (issue #52).</p>
 */
class BlockShaperTest {

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private ProtoChunk chunk;
    private BlockShaper shaper;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private BlockShaper shaper() {
        if (shaper == null) {
            chunk = TestChunk.emptyChunk();
            shaper = new BlockShaper(new BlockShaper.ChunkView() {
                @Override
                public BlockState get(BlockPos pos) {
                    return blocks.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
                }

                @Override
                public void set(BlockPos pos, BlockState state) {
                    blocks.put(pos.immutable(), state);
                }

                @Override
                public boolean contains(BlockPos pos) {
                    return (pos.getX() >> 4) == 0 && (pos.getZ() >> 4) == 0;
                }
            }, TestChunk.levelFor(chunk), 0L);
        }
        return shaper;
    }

    @Test
    void aWallConnectsOnlyOnTheSidesWithSomethingToConnectTo() {
        blocks.put(new BlockPos(5, 64, 5), Blocks.STONE.defaultBlockState());

        BlockState wall = shaper().correct(Blocks.COBBLESTONE_WALL.defaultBlockState(), 6, 64, 5);

        assertEquals(WallSide.LOW, wall.getValue(WallBlock.WEST), "stone sits to the west");
        assertEquals(WallSide.NONE, wall.getValue(WallBlock.EAST));
        assertEquals(WallSide.NONE, wall.getValue(WallBlock.NORTH));
        assertEquals(WallSide.NONE, wall.getValue(WallBlock.SOUTH));
    }

    /**
     * The neighbour is in the next chunk along, whose content depends on whether its features have
     * run - so it is deliberately unknown rather than read, and an unknown neighbour is no
     * connection. Vanilla postprocessing resolves it from final data later, which is what the mark
     * asserted below is for.
     */
    @Test
    void aNeighbourInAnotherChunkIsUnknownRatherThanRead() {
        blocks.put(new BlockPos(16, 64, 5), Blocks.STONE.defaultBlockState());

        BlockState wall = shaper().correct(Blocks.COBBLESTONE_WALL.defaultBlockState(), 15, 64, 5);

        assertEquals(WallSide.NONE, wall.getValue(WallBlock.EAST),
                "the stone is one chunk over and must not be consulted during generation");
        assertEquals(1, chunk.getPostProcessing()[chunk.getSectionIndex(64)].size(),
                "a border block is handed to vanilla postprocessing instead");
    }

    @Test
    void anIsolatedStairIsStraight() {
        BlockState stair = shaper().correct(Blocks.STONE_STAIRS.defaultBlockState(), 5, 64, 5);

        assertEquals(StairsShape.STRAIGHT, stair.getValue(StairBlock.SHAPE));
    }

    @Test
    void structureVoidMeansLeaveWhateverIsAlreadyThere() {
        assertNull(shaper().correct(Blocks.STRUCTURE_VOID.defaultBlockState(), 5, 64, 5),
                "the asset format's alpha channel");
    }

    @Test
    void anUnresolvedPaletteCharacterIsNotDereferenced() {
        assertNull(shaper().correct(null, 5, 64, 5));
    }
}

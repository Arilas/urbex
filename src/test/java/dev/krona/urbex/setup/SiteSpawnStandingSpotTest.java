package dev.krona.urbex.setup;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a claimed site spawn actually puts the player, given the blocks that are there.
 *
 * <p>Every fixture below is a column copied out of a world that got this wrong. A site claim yields
 * an anchor at a chunk's centre, and a chunk's centre is where Urbex puts a park's fountain, its
 * lamp post and its tree - so the search that read only the anchor's own column found the one block
 * in the chunk most likely to be occupied, abandoned the claim, and left the player on the surface
 * of a world whose whole point was that they would wake up underground.</p>
 */
class SiteSpawnStandingSpotTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * A chunk of park: grass at the floor, air above it, and whatever the caller stands in the
     * middle of it.
     *
     * <p>Deliberately not empty below the floor. A fixture that was air everywhere would pass a
     * search that ignored {@code isFaceSturdy} entirely.</p>
     */
    private static BlockGetter park(int floorY, BlockState... centreColumn) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                blocks.put(new BlockPos(x, floorY - 1, z), Blocks.STONE.defaultBlockState());
                blocks.put(new BlockPos(x, floorY, z), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        for (int i = 0; i < centreColumn.length; i++) {
            blocks.put(new BlockPos(8, floorY + i, 8), centreColumn[i]);
        }
        return new FakeChunk(blocks);
    }

    /** The anchor a claim yields: the centre of chunk 0,0 at the plan's ground floor. */
    private static BlockPos anchor(int floorY) {
        return new BlockPos(8, floorY, 8);
    }

    @Test
    void aLampPostOnTheAnchorDoesNotCostTheWholeSpawn() {
        // New World (18), anchor -200,-12,216: grass, then a lantern hanging in the anchor's column.
        BlockGetter chunk = park(-12, Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.LANTERN.defaultBlockState());

        BlockPos spot = SpawnPlacement.standingSpotIn(chunk, anchor(-12));

        assertNotNull(spot, "a park with one lamp post in it has 255 other columns to stand in");
        assertEquals(-11, spot.getY(), "still on the park's own floor, not on top of the lantern");
    }

    @Test
    void aPondOnTheAnchorDoesNotCostTheWholeSpawn() {
        // New World (11), anchor 136,-12,184: grass with water standing on it.
        BlockGetter chunk = park(-12, Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.WATER.defaultBlockState());

        BlockPos spot = SpawnPlacement.standingSpotIn(chunk, anchor(-12));

        assertNotNull(spot, "a park with a pond in it is not a park with nowhere to stand");
        assertEquals(-11, spot.getY());
    }

    @Test
    void aTreeOnTheAnchorDoesNotCostTheWholeSpawn() {
        // New World (19), anchor -232,-18,248: grass, a torch, then leaves overhead.
        BlockGetter chunk = park(-18, Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.TORCH.defaultBlockState(), Blocks.BIRCH_LEAVES.defaultBlockState());

        BlockPos spot = SpawnPlacement.standingSpotIn(chunk, anchor(-18));

        assertNotNull(spot);
        assertEquals(-17, spot.getY());
    }

    @Test
    void aClearAnchorIsStillUsedAsGiven() {
        // The case that always worked, and has to keep working: an ordinary street chunk, where the
        // centre is the answer and nothing else needs reading.
        BlockGetter chunk = park(-30);

        BlockPos spot = SpawnPlacement.standingSpotIn(chunk, anchor(-30));

        assertEquals(new BlockPos(8, -29, 8), spot, "the centre, unchanged, when the centre works");
    }

    @Test
    void theFloorIsExhaustedBeforeAnythingAboveIt() {
        // A wall column at the centre with a standable top. Column-major order would climb it and
        // spawn the player on top of the lamp post; level-major order finds the grass beside it.
        BlockGetter chunk = park(-12, Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.ANDESITE_WALL.defaultBlockState(), Blocks.STONE.defaultBlockState());

        BlockPos spot = SpawnPlacement.standingSpotIn(chunk, anchor(-12));

        assertNotNull(spot);
        assertEquals(-11, spot.getY(), "on the park's floor, not on top of its furniture");
    }

    @Test
    void theSearchStaysInsideTheAnchorsOwnChunk() {
        // Only the chunk the anchor names is this site chunk's plan; a neighbour may be a building,
        // a rim, or no site at all. So a chunk with nothing standable in it reports nothing, and the
        // claim moves on to the next chunk rather than wandering into someone else's.
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = -16; x < 32; x++) {
            for (int z = -16; z < 32; z++) {
                boolean inside = x >= 0 && x < 16 && z >= 0 && z < 16;
                blocks.put(new BlockPos(x, -12, z), inside
                        ? Blocks.WATER.defaultBlockState()
                        : Blocks.STONE.defaultBlockState());
            }
        }

        assertNull(SpawnPlacement.standingSpotIn(new FakeChunk(blocks), anchor(-12)));
    }

    @Test
    void everySpotFoundIsOneAPlayerFits() {
        BlockGetter chunk = park(-12, Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.LANTERN.defaultBlockState());

        BlockPos spot = SpawnPlacement.standingSpotIn(chunk, anchor(-12));

        assertNotNull(spot);
        assertTrue(chunk.getBlockState(spot).isAir(), "feet in air");
        assertTrue(chunk.getBlockState(spot.above()).isAir(), "head in air");
        assertTrue(SpawnPlacement.isValidStandingPosition(chunk, spot.below()),
                "standing on something solid");
    }

    /** Air everywhere the fixture did not say otherwise. */
    private record FakeChunk(Map<BlockPos, BlockState> blocks) implements BlockGetter {

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}

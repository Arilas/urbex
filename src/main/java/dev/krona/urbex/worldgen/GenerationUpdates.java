package dev.krona.urbex.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Generation notifications for a committed block, without rewriting its state or block entity.
 * Urbex runs before vanilla initializes chunk lighting; the later lighting and chunk-delivery
 * stages consume the final contents. This is not a live-world relighting operation.
 */
final class GenerationUpdates {

    @FunctionalInterface
    interface PoiRefresh {
        void update(BlockPos pos, BlockState oldState, BlockState newState);
    }

    private GenerationUpdates() {
    }

    /**
     * Retains the POI refresh and requested post-processing of the former AIR/state toggle while
     * preserving finalized marker NBT. All effects stay inside this generation's owning chunk and
     * write window; a shifted post-processing position never causes a neighboring chunk lookup.
     */
    static void apply(BlockGetter world, ChunkAccess ownerChunk, WriteWindow window,
                      BlockPos pos, int flags, PoiRefresh poiRefresh) {
        if (!contains(ownerChunk, window, pos)) {
            return;
        }
        BlockState state = ownerChunk.getBlockState(pos);
        if (state.isAir()) {
            return;
        }

        BlockState air = Blocks.AIR.defaultBlockState();
        poiRefresh.update(pos, state, air);
        poiRefresh.update(pos, air, state);

        if ((flags & Block.UPDATE_KNOWN_SHAPE) == 0) {
            BlockPos postProcessPos = state.getPostProcessPos(world, pos);
            if (postProcessPos != null && contains(ownerChunk, window, postProcessPos)) {
                ownerChunk.markPosForPostProcessing(postProcessPos);
            }
        }
    }

    private static boolean contains(ChunkAccess ownerChunk, WriteWindow window, BlockPos pos) {
        return (pos.getX() >> 4) == ownerChunk.getPos().x()
                && (pos.getZ() >> 4) == ownerChunk.getPos().z()
                && pos.getY() >= ownerChunk.getMinY()
                && pos.getY() <= ownerChunk.getMaxY()
                && window.contains(pos);
    }
}

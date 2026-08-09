package dev.krona.urbex.worldgen;

import dev.krona.urbex.varia.Rng;
import dev.krona.urbex.worldgen.lost.cityassets.LightPool;
import dev.krona.urbex.worldgen.lost.cityassets.OptionalLightPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Plans one context's deferred lights without exposing later markers to earlier placements. */
final class DeferredLightPlacer {

    record Planned(BlockPos pos, BlockState state) { }

    @FunctionalInterface
    interface AnchorSupport {
        boolean isPresent(BlockPos marker, Direction supportDirection,
                          Function<BlockPos, BlockState> stateAt);
    }

    @FunctionalInterface
    interface Survival {
        boolean canPlace(BlockPos marker, OptionalLightPlacer.Attempt attempt,
                         Function<BlockPos, BlockState> stateAt);
    }

    private DeferredLightPlacer() {
    }

    static List<Planned> plan(int ownerChunkX, int ownerChunkZ, long seed,
                              List<LightTodoQueue.Todo> todos,
                              Function<BlockPos, BlockState> stateAt,
                              AnchorSupport anchorSupport,
                              Survival survival) {
        Set<BlockPos> queuedMarkers = new HashSet<>();
        for (LightTodoQueue.Todo todo : todos) {
            BlockPos marker = todo.pos();
            if (!belongsTo(ownerChunkX, ownerChunkZ, marker)) {
                throw new IllegalArgumentException("Light marker " + marker + " does not belong to owner chunk "
                        + ownerChunkX + "," + ownerChunkZ);
            }
            queuedMarkers.add(marker);
        }

        Function<BlockPos, BlockState> snapshotStateAt = pos -> queuedMarkers.contains(pos)
                ? Blocks.AIR.defaultBlockState()
                : stateAt.apply(pos);
        List<Planned> planned = new ArrayList<>();
        for (LightTodoQueue.Todo todo : todos) {
            BlockPos marker = todo.pos();
            LightPool pool = todo.pool() == null ? LightPool.legacyTorch() : todo.pool();
            RandomSource random = Rng.atPos(seed, marker.getX(), marker.getY(), marker.getZ(),
                    Rng.Purpose.LIGHTING_VARIANT);
            OptionalLightPlacer.select(pool, random,
                            (placement, supportDirection) -> supportDirection == null
                                    || (belongsTo(ownerChunkX, ownerChunkZ, marker.relative(supportDirection))
                                    && anchorSupport.isPresent(marker, supportDirection, snapshotStateAt)),
                            attempt -> survival.canPlace(marker, attempt, snapshotStateAt))
                    .ifPresent(attempt -> planned.add(new Planned(marker, attempt.state())));
        }
        return List.copyOf(planned);
    }

    private static boolean belongsTo(int chunkX, int chunkZ, BlockPos pos) {
        return (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ;
    }
}

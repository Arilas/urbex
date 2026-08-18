package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class OptionalLightPlacer {

    private static final List<Opportunity> OPPORTUNITIES = List.of(
            new Opportunity(LightPool.Placement.FLOOR, Direction.DOWN),
            new Opportunity(LightPool.Placement.WALL, Direction.WEST),
            new Opportunity(LightPool.Placement.WALL, Direction.EAST),
            new Opportunity(LightPool.Placement.WALL, Direction.NORTH),
            new Opportunity(LightPool.Placement.WALL, Direction.SOUTH),
            new Opportunity(LightPool.Placement.CEILING, Direction.UP),
            new Opportunity(LightPool.Placement.FREE, null)
    );

    private OptionalLightPlacer() {
    }

    public record Attempt(BlockState state, LightPool.Placement placement,
                          @Nullable Direction supportDirection) { }

    @FunctionalInterface
    public interface Survival {
        boolean canPlace(Attempt attempt);
    }

    @FunctionalInterface
    public interface OpportunitySupport {
        boolean isPresent(LightPool.Placement placement, @Nullable Direction supportDirection);
    }

    public static Optional<Attempt> select(LightPool pool, long seed, BlockPos at, Survival survival) {
        return select(pool, seed, at, (placement, supportDirection) -> true, survival);
    }

    public static Optional<Attempt> select(LightPool pool, long seed, BlockPos at,
                                           OpportunitySupport support, Survival survival) {
        return select(pool, seed, at, support, survival, LightPool.Candidate::state, true);
    }

    /**
     * The first candidate that fits, in opportunity order, with its state read through
     * {@code stateOf}.
     *
     * <p>Off, the same search runs over the same stream with {@code stateOf} reading each
     * candidate's replacement instead of its light. That is what keeps a fixture in one place: the
     * marker that would hold a lit wall torch holds that torch's unlit form, and raising lighting
     * density lights the fixture that was already standing there rather than moving it.</p>
     *
     * <p>{@code WEIGHT.043}: the position is the marker's own, and it is passed rather than a
     * {@link net.minecraft.util.RandomSource} because a placement list is addressed and not drawn. One
     * consequence is worth stating, because it is a behaviour change and not only a refactor: an
     * opportunity that is supported but whose candidates the world rejects used to consume a draw and
     * shift every later opportunity's, so which light stood in a doorway depended on whether the floor
     * beneath it had been rejected first. It no longer does.</p>
     *
     * <p>The one asymmetry is {@code fallThrough}. Lit, a candidate the world will not accept hands
     * over to the next one in the list - a light is worth another try. Unlit, the first drawn
     * candidate is the answer even when its replacement is air, because falling through would put
     * <em>another</em> candidate's replacement at a position that candidate never won.</p>
     */
    public static Optional<Attempt> select(LightPool pool, long seed, BlockPos at,
                                           OpportunitySupport support, Survival survival,
                                           Function<LightPool.Candidate, BlockState> stateOf,
                                           boolean fallThrough) {
        for (Opportunity opportunity : OPPORTUNITIES) {
            if (!pool.hasCandidates(opportunity.placement())
                    || !support.isPresent(opportunity.placement(), opportunity.supportDirection())) {
                continue;
            }
            for (LightPool.Candidate candidate : pool.weightedOrder(opportunity.placement(), seed,
                    at.getX(), at.getY(), at.getZ())) {
                BlockState chosen = stateOf.apply(candidate);
                if (chosen == null || chosen.isAir()) {
                    return Optional.empty();
                }
                BlockState state = orient(chosen, opportunity.supportDirection());
                Attempt attempt = new Attempt(state, opportunity.placement(), opportunity.supportDirection());
                if (survival.canPlace(attempt)) {
                    return Optional.of(attempt);
                }
                if (!fallThrough) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static BlockState orient(BlockState state, @Nullable Direction supportDirection) {
        if (supportDirection == null) {
            return state;
        }
        Direction facing = supportDirection.getOpposite();
        if (state.hasProperty(BlockStateProperties.HANGING)) {
            state = state.setValue(BlockStateProperties.HANGING, supportDirection == Direction.UP);
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            state = state.setValue(BlockStateProperties.FACING, facing);
        } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        return state;
    }

    private record Opportunity(LightPool.Placement placement, @Nullable Direction supportDirection) { }
}

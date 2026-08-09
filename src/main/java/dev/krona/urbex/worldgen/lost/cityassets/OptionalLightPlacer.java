package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

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

    public static Optional<Attempt> select(LightPool pool, RandomSource random, Survival survival) {
        return select(pool, random, (placement, supportDirection) -> true, survival);
    }

    public static Optional<Attempt> select(LightPool pool, RandomSource random,
                                           OpportunitySupport support, Survival survival) {
        for (Opportunity opportunity : OPPORTUNITIES) {
            if (!pool.hasCandidates(opportunity.placement())
                    || !support.isPresent(opportunity.placement(), opportunity.supportDirection())) {
                continue;
            }
            for (LightPool.Candidate candidate : pool.weightedOrder(opportunity.placement(), random)) {
                BlockState state = orient(candidate.state(), opportunity.supportDirection());
                Attempt attempt = new Attempt(state, opportunity.placement(), opportunity.supportDirection());
                if (survival.canPlace(attempt)) {
                    return Optional.of(attempt);
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

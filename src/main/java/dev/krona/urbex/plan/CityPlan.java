package dev.krona.urbex.plan;

import dev.krona.urbex.plan.block.CityBlock;
import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.lot.Lot;
import dev.krona.urbex.plan.road.RoadGraph;

import java.util.List;
import java.util.Map;

/**
 * The assembled result of {@link Planner#plan}: one settlement's roads, blocks, district
 * assignments and lots, all in one immutable value.
 * <p>
 * Plain record {@code equals}/{@code hashCode} are correct here because {@link RoadGraph} now has
 * real value semantics of its own (see its class doc) — two structurally identical graphs from two
 * separate {@code ArterialGrowth.grow} calls compare equal, so two structurally identical plans do
 * too, which is what {@code PlannerTest.planningIsDeterministic} needs.
 */
public record CityPlan(
        Settlement settlement,
        RoadGraph roads,
        List<CityBlock> blocks,
        Map<Integer, District> districts,
        List<Lot> lots
) {

    public CityPlan {
        blocks = List.copyOf(blocks);
        districts = Map.copyOf(districts);
        lots = List.copyOf(lots);
    }
}

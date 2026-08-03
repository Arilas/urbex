package dev.krona.urbex.plan;

import dev.krona.urbex.plan.block.CityBlock;
import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.lot.Lot;
import dev.krona.urbex.plan.road.RoadGraph;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The assembled result of {@link Planner#plan}: one settlement's roads, blocks, district
 * assignments and lots, all in one immutable value.
 * <p>
 * {@code equals}/{@code hashCode} are hand-written rather than the record defaults because
 * {@link RoadGraph} has no value semantics of its own — it is a plain class with identity equality,
 * built fresh by {@link dev.krona.urbex.plan.road.ArterialGrowth} on every call. Two structurally
 * identical plans grown from the same seed would otherwise compare unequal because their
 * {@code RoadGraph} instances are different objects, and {@code PlannerTest.planningIsDeterministic}
 * would fail on a correct, deterministic planner. Comparing {@code roads.nodes()} and
 * {@code roads.edges()} instead — both lists of records — restores value semantics without touching
 * {@code RoadGraph} itself, which is out of scope for this task.
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CityPlan other)) {
            return false;
        }
        return settlement.equals(other.settlement)
                && roads.nodes().equals(other.roads.nodes())
                && roads.edges().equals(other.roads.edges())
                && blocks.equals(other.blocks)
                && districts.equals(other.districts)
                && lots.equals(other.lots);
    }

    @Override
    public int hashCode() {
        return Objects.hash(settlement, roads.nodes(), roads.edges(), blocks, districts, lots);
    }
}

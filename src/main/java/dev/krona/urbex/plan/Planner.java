package dev.krona.urbex.plan;

import dev.krona.urbex.plan.block.BlockExtractor;
import dev.krona.urbex.plan.block.CityBlock;
import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.district.DistrictMap;
import dev.krona.urbex.plan.lot.Lot;
import dev.krona.urbex.plan.lot.LotSubdivider;
import dev.krona.urbex.plan.road.ArterialGrowth;
import dev.krona.urbex.plan.road.RoadGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the whole pipeline: grow roads, extract blocks, assign districts, subdivide every block into
 * lots, drop any lot that overlaps one already kept, then rank the survivors by area across the
 * whole settlement to finish {@code sizeClass} and hand out final, sequential ids.
 * <p>
 * The area ranking is why lots are not finished inside {@link LotSubdivider}: a district's lots
 * being "small" or "large" is a statement about the settlement as a whole, and {@code subdivide}
 * only ever sees one block at a time. It returns lots with a placeholder id and {@code sizeClass};
 * this class is the only place that has seen every lot and can replace both with real values.
 * <p>
 * The overlap check belongs here for the same reason. {@code CityBlock} outlines are not
 * rectangular — spokes and rings meet at arbitrary bearings — so a wedge-shaped block's axis-aligned
 * bounding box can overlap a neighbour's across their shared boundary, and {@code LotSubdivider}'s
 * per-leaf centre check (brief §4 step 3) cannot see that neighbour to know its corners have
 * wandered into it. Rejecting every leaf whose corners are not all inside its own block was tried
 * first and discarded: it starves thin wedge blocks of lots entirely, because a target-size rect
 * rarely fits inside a triangle's bounding box with room to spare on every side. Deduplicating here,
 * after every block has been subdivided, is the only point that has enough information to tell a
 * genuine cross-block collision from a lot that simply reaches the edge of its own block.
 */
public final class Planner {

    private Planner() {
    }

    public static CityPlan plan(long seed, Settlement s, TerrainSampler terrain, PlanParams p) {
        RoadGraph roads = ArterialGrowth.grow(seed, s, terrain, p);
        List<CityBlock> blocks = BlockExtractor.extract(roads, p);

        Map<Integer, District> districts = new LinkedHashMap<>();
        for (CityBlock b : blocks) {
            districts.put(b.id(), DistrictMap.assign(b, s, terrain));
        }

        // Blocks are already in their stable, sorted id order (BlockExtractor's contract), so
        // concatenating each block's lots in that order is what "sequential ids in block order"
        // means once ids are handed out below.
        List<Lot> interim = new ArrayList<>();
        for (CityBlock b : blocks) {
            interim.addAll(LotSubdivider.subdivide(seed, b, districts.get(b.id()), roads, terrain, p));
        }

        List<Lot> lots = finalizeLots(deduplicateOverlaps(interim));
        return new CityPlan(s, roads, blocks, districts, lots);
    }

    /**
     * Keeps a lot only if its footprint does not intersect one already kept. Lots earlier in block
     * (then within-block leaf) order always win a conflict, which keeps the result deterministic —
     * both orderings are themselves deterministic functions of the seed, never of iteration over an
     * unordered collection.
     */
    private static List<Lot> deduplicateOverlaps(List<Lot> candidates) {
        List<Lot> kept = new ArrayList<>();
        for (Lot candidate : candidates) {
            boolean overlaps = false;
            for (Lot existing : kept) {
                if (candidate.footprint().intersects(existing.footprint())) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /**
     * Ranks every interim lot by footprint area to fill in {@code sizeClass} (0, 1 or 2, by
     * tertile), then reassigns sequential ids in the lots' original (block) order.
     * <p>
     * The ranking sorts a copy of the index list rather than the lots themselves, breaking ties by
     * original index, so two lots of identical area always land in the same relative order they
     * were produced in — itself deterministic, since block order and each block's internal split
     * order both are. That means this needs no {@code Map} keyed by {@code Lot} and no reliance on
     * {@link java.util.Collections#sort} being stable, though it is.
     */
    private static List<Lot> finalizeLots(List<Lot> interim) {
        int n = interim.size();
        if (n == 0) {
            return List.of();
        }

        Integer[] byArea = new Integer[n];
        for (int i = 0; i < n; i++) {
            byArea[i] = i;
        }
        Arrays.sort(byArea, (a, b) -> {
            int cmp = Integer.compare(interim.get(a).footprint().area(), interim.get(b).footprint().area());
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });

        int[] sizeClassOf = new int[n];
        for (int rank = 0; rank < n; rank++) {
            sizeClassOf[byArea[rank]] = Math.min(2, rank * 3 / n);
        }

        List<Lot> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Lot l = interim.get(i);
            result.add(new Lot(i, l.footprint(), l.district(), sizeClassOf[i],
                    l.frontingEdgeIndex(), l.groundHeight(), l.waterSides()));
        }
        return result;
    }
}

package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.terrain.CliffTerrain;
import dev.krona.urbex.plan.terrain.CoastTerrain;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.HillTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two graph-level invariants block extraction depends on and {@link ArterialGrowth}
 * doesn't get for free just by growing a network: that the graph is planar, and that no two edges
 * connect the same pair of nodes.
 * <p>
 * Both were violated in practice before {@link Planarizer} and the ring-edge dedup existed: ring
 * chords are straight lines between spoke nodes, and spokes curve under terrain avoidance, so a ring
 * could cross a spoke without a node at the crossing; separately, two rings could pick the same node
 * pair and emit the same edge twice. Either one makes face traversal undefined - the first swallows
 * neighbouring faces (a real seed extracted zero blocks against a circuit rank of 3), the second
 * makes face rotation undefined outright. This sweeps every settlement class against every synthetic
 * terrain so a regression in either one is caught here, not several phases downstream.
 */
class PlanarityTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final int SEEDS_PER_COMBINATION = 50;

    private static TerrainSampler[] terrains() {
        return new TerrainSampler[] {
                new FlatTerrain(64),
                new HillTerrain(64, 100, 300),
                new RiverTerrain(64, 0, 24),
                new CoastTerrain(64, 200),
                new CliffTerrain(64, 40, 0)
        };
    }

    @Test
    void theGraphIsPlanar() {
        for (SettlementClass cls : SettlementClass.values()) {
            for (TerrainSampler terrain : terrains()) {
                for (long seed = 0; seed < SEEDS_PER_COMBINATION; seed++) {
                    RoadGraph g = ArterialGrowth.grow(seed, new Settlement(cls, 0, 0), terrain, P);
                    int crossings = countProperCrossings(g);
                    assertTrue(crossings == 0, cls + "/" + terrain.getClass().getSimpleName()
                            + " seed " + seed + ": " + crossings + " edge pair(s) properly cross");
                }
            }
        }
    }

    @Test
    void noRingNodePairIsDuplicated() {
        for (SettlementClass cls : SettlementClass.values()) {
            for (TerrainSampler terrain : terrains()) {
                for (long seed = 0; seed < SEEDS_PER_COMBINATION; seed++) {
                    RoadGraph g = ArterialGrowth.grow(seed, new Settlement(cls, 0, 0), terrain, P);
                    Map<Long, Integer> counts = new HashMap<>();
                    for (RoadEdge e : g.edges()) {
                        counts.merge(edgeKey(e.fromId(), e.toId()), 1, Integer::sum);
                    }
                    for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
                        assertTrue(entry.getValue() <= 1, cls + "/" + terrain.getClass().getSimpleName()
                                + " seed " + seed + ": node pair connected by " + entry.getValue() + " edges");
                    }
                }
            }
        }
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    /**
     * Independent of {@link Planarizer}'s own intersection math on purpose: reusing its algorithm
     * here would let a shared bug in that math pass both creating the graph and verifying it. This
     * uses the standard orientation (cross-product sign) test for two segments straddling each
     * other, rather than Planarizer's parametric line-intersection approach.
     */
    private static int countProperCrossings(RoadGraph g) {
        var edges = g.edges();
        int count = 0;
        for (int i = 0; i < edges.size(); i++) {
            RoadEdge e1 = edges.get(i);
            for (int j = i + 1; j < edges.size(); j++) {
                RoadEdge e2 = edges.get(j);
                if (e1.fromId() == e2.fromId() || e1.fromId() == e2.toId()
                        || e1.toId() == e2.fromId() || e1.toId() == e2.toId()) {
                    continue; // sharing an endpoint is not a crossing
                }
                Vec2 a1 = g.nodeAt(e1.fromId()).pos();
                Vec2 b1 = g.nodeAt(e1.toId()).pos();
                Vec2 a2 = g.nodeAt(e2.fromId()).pos();
                Vec2 b2 = g.nodeAt(e2.toId()).pos();
                if (properlyCross(a1, b1, a2, b2)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean properlyCross(Vec2 a1, Vec2 b1, Vec2 a2, Vec2 b2) {
        double d1 = orientation(a1, b1, a2);
        double d2 = orientation(a1, b1, b2);
        double d3 = orientation(a2, b2, a1);
        double d4 = orientation(a2, b2, b1);
        boolean straddle1 = (d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0);
        boolean straddle2 = (d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0);
        return straddle1 && straddle2;
    }

    /** Signed area x2 of (o, a, b); sign gives which side of o-a the point b falls on. */
    private static double orientation(Vec2 o, Vec2 a, Vec2 b) {
        return (double) (a.x() - o.x()) * (b.z() - o.z()) - (double) (a.z() - o.z()) * (b.x() - o.x());
    }
}

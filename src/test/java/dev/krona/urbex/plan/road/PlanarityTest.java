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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the graph-level invariants block extraction — and the lot-frontage assignment after it —
 * depend on and {@link ArterialGrowth} doesn't get for free just by growing a network: that the
 * graph is planar, that no two edges connect the same pair of nodes, and that no two edges sharing a
 * node retrace the same tarmac.
 * <p>
 * All three were violated in practice before {@link Planarizer} and the ring-edge dedup existed:
 * ring chords are straight lines between spoke nodes, and spokes curve under terrain avoidance, so a
 * ring could cross a spoke without a node at the crossing, or run collinear from a shared node along
 * part of a spoke it's attached to (almost always a ring edge retracing a spoke's first segment or
 * more); separately, two rings could pick the same node pair and emit the same edge twice. All three
 * make face traversal undefined or the graph ambiguous downstream - the first swallows neighbouring
 * faces (a real seed extracted zero blocks against a circuit rank of 3), the second leaves two edges
 * that a lot could legitimately front onto for the same stretch of road, the third makes face
 * rotation undefined outright. This sweeps every settlement class against every synthetic terrain so
 * a regression in any of the three is caught here, not several phases downstream.
 * <p>
 * {@link #nonIncidentCollinearOverlapIsAMeasuredResidualNotAGoal} covers the one gap that remains:
 * two edges sharing <em>no</em> node, lying exactly on the same line and overlapping along it, are
 * never split (see {@link Planarizer}'s class javadoc for why) - though in practice, every instance
 * of this ever measured turned out to be a downstream consequence of the shared-endpoint case (a ring
 * edge retracing a multi-segment spoke chain, non-incident only with the chain's later links), so
 * fixing that case resolved every measured instance of this one too. The rate is pinned regardless,
 * because that's an empirical fact about what {@link ArterialGrowth} currently produces, not a
 * property {@link Planarizer} enforces - so a future change to either should still be a deliberate,
 * reviewed change to this test, not a silent side effect nobody notices.
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

    /**
     * Growth can emit a ring edge that shares a node with a spoke edge and runs collinear from it in
     * the same direction - one road laid along a prefix of another from the same node. {@link
     * Planarizer} resolves this (see its class javadoc), so this asserts zero across the same sweep
     * {@link #theGraphIsPlanar()} uses. Before the fix this affected 440 of 1250 graphs here (79 CITY,
     * 119 METROPOLIS at a 5,000-graph scale; zero at HAMLET/VILLAGE/TOWN), so this is a real
     * regression guard, not a tautology - it would have caught the defect the day it was introduced.
     */
    @Test
    void noEndpointSharingEdgesOverlapCollinearly() {
        int graphsWithOverlap = 0;
        int totalGraphs = 0;
        for (SettlementClass cls : SettlementClass.values()) {
            for (TerrainSampler terrain : terrains()) {
                for (long seed = 0; seed < SEEDS_PER_COMBINATION; seed++) {
                    RoadGraph g = ArterialGrowth.grow(seed, new Settlement(cls, 0, 0), terrain, P);
                    totalGraphs++;
                    if (anyEndpointSharingCollinearOverlap(g)) {
                        graphsWithOverlap++;
                    }
                }
            }
        }
        assertEquals(1250, totalGraphs, "the sweep size changed; the pinned count below no longer applies as-is");
        assertEquals(0, graphsWithOverlap,
                "a ring edge is retracing part of a spoke it shares a node with - Planarizer should "
                        + "have resolved this, and previously did across the same sweep");
    }

    /**
     * {@link Planarizer} does not detect or fix collinear overlap between two edges sharing no
     * endpoint - see its class javadoc for why. This is not a correctness bug: {@code BlockExtractor}
     * has been swept against it and degrades gracefully rather than throwing. It is, however, a real
     * gap in "the graph is planar" as a blanket claim, so this pins the exact count over the same
     * sweep {@link #theGraphIsPlanar()} uses. It currently measures **zero** - not because
     * {@link Planarizer} handles this case, it still doesn't, but because every instance measured so
     * far turned out to be a ring edge retracing a multi-segment spoke chain: fixing the first
     * (shared-endpoint) link in the chain cascades through {@link Planarizer}'s convergence loop and
     * resolves the later, non-incident links too, since each resolution can turn a non-incident pair
     * into a shared-endpoint one by introducing a new edge that starts exactly where the chain
     * continues. If a future change to {@code ArterialGrowth} produces a genuinely unrelated
     * non-incident overlap - two edges that were never connected through a common node - this
     * assertion will fail, which is the point: that's the real residual reappearing; before the
     * shared-endpoint fix it was 274 of 1250 graphs here (down from 374 before the duplicate-edge fix,
     * which removes one common source: two ring edges occupying the literal same segment).
     */
    @Test
    void nonIncidentCollinearOverlapIsAMeasuredResidualNotAGoal() {
        int graphsWithOverlap = 0;
        int totalGraphs = 0;
        for (SettlementClass cls : SettlementClass.values()) {
            for (TerrainSampler terrain : terrains()) {
                for (long seed = 0; seed < SEEDS_PER_COMBINATION; seed++) {
                    RoadGraph g = ArterialGrowth.grow(seed, new Settlement(cls, 0, 0), terrain, P);
                    totalGraphs++;
                    if (anyNonIncidentCollinearOverlap(g)) {
                        graphsWithOverlap++;
                    }
                }
            }
        }
        assertEquals(1250, totalGraphs, "the sweep size changed; the pinned count below no longer applies as-is");
        assertEquals(0, graphsWithOverlap,
                "non-incident collinear overlap reappeared - confirm whether this is a genuinely new, "
                        + "unrelated overlap (Planarizer still won't fix it, extend it or accept the "
                        + "residual and update this number) or another shared-endpoint chain that "
                        + "should have cascaded away; either way it deserves a look");
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
     * other, rather than Planarizer's parametric line-intersection approach - and, like Planarizer's
     * own predicate, does the sign arithmetic in {@code long} rather than {@code double}: the deltas
     * and their products are always exactly integer-valued, so there's no reason to let a floating
     * mantissa's range be the thing exactness quietly depends on.
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
        long d1 = orientation(a1, b1, a2);
        long d2 = orientation(a1, b1, b2);
        long d3 = orientation(a2, b2, a1);
        long d4 = orientation(a2, b2, b1);
        boolean straddle1 = (d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0);
        boolean straddle2 = (d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0);
        return straddle1 && straddle2;
    }

    /** Signed area x2 of (o, a, b); sign gives which side of o-a the point b falls on. */
    private static long orientation(Vec2 o, Vec2 a, Vec2 b) {
        return (long) (a.x() - o.x()) * (b.z() - o.z()) - (long) (a.z() - o.z()) * (b.x() - o.x());
    }

    /**
     * Whether any two edges in {@code g} sharing exactly one endpoint run collinear from it in the
     * same direction, with different lengths - i.e. one is laid along a prefix of the other from
     * their shared node. Independent of {@link Planarizer}'s own {@code findEndpointOverlap} for the
     * same reason {@link #countProperCrossings} is independent of its crossing predicate: reusing the
     * implementation under test to verify it would let a shared bug pass on both sides.
     */
    private static boolean anyEndpointSharingCollinearOverlap(RoadGraph g) {
        var edges = g.edges();
        for (int i = 0; i < edges.size(); i++) {
            RoadEdge e1 = edges.get(i);
            for (int j = i + 1; j < edges.size(); j++) {
                RoadEdge e2 = edges.get(j);
                int hub;
                int far1;
                int far2;
                if (e1.fromId() == e2.fromId()) {
                    hub = e1.fromId();
                    far1 = e1.toId();
                    far2 = e2.toId();
                } else if (e1.fromId() == e2.toId()) {
                    hub = e1.fromId();
                    far1 = e1.toId();
                    far2 = e2.fromId();
                } else if (e1.toId() == e2.fromId()) {
                    hub = e1.toId();
                    far1 = e1.fromId();
                    far2 = e2.toId();
                } else if (e1.toId() == e2.toId()) {
                    hub = e1.toId();
                    far1 = e1.fromId();
                    far2 = e2.fromId();
                } else {
                    continue; // no shared endpoint at all
                }
                if (far1 == far2) {
                    continue; // an exact duplicate pair, not this check's concern
                }

                Vec2 posHub = g.nodeAt(hub).pos();
                Vec2 posFar1 = g.nodeAt(far1).pos();
                Vec2 posFar2 = g.nodeAt(far2).pos();
                long d1x = posFar1.x() - posHub.x();
                long d1z = posFar1.z() - posHub.z();
                long d2x = posFar2.x() - posHub.x();
                long d2z = posFar2.z() - posHub.z();

                if ((d1x == 0 && d1z == 0) || (d2x == 0 && d2z == 0)) {
                    continue; // a zero-length edge
                }
                if (d1x * d2z - d1z * d2x != 0) {
                    continue; // not collinear
                }
                if (d1x * d2x + d1z * d2z <= 0) {
                    continue; // opposite directions: an ordinary through-node, not an overlap
                }
                long len1Sq = d1x * d1x + d1z * d1z;
                long len2Sq = d2x * d2x + d2z * d2z;
                if (len1Sq == len2Sq) {
                    continue; // far1 and far2 coincide positionally
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any two non-incident edges in {@code g} lie exactly on the same line and overlap along
     * it by more than a shared endpoint. Independent of {@link Planarizer} for the same reason
     * {@link #countProperCrossings} is.
     */
    private static boolean anyNonIncidentCollinearOverlap(RoadGraph g) {
        var edges = g.edges();
        for (int i = 0; i < edges.size(); i++) {
            RoadEdge e1 = edges.get(i);
            Vec2 a1 = g.nodeAt(e1.fromId()).pos();
            Vec2 b1 = g.nodeAt(e1.toId()).pos();
            for (int j = i + 1; j < edges.size(); j++) {
                RoadEdge e2 = edges.get(j);
                if (e1.fromId() == e2.fromId() || e1.fromId() == e2.toId()
                        || e1.toId() == e2.fromId() || e1.toId() == e2.toId()) {
                    continue;
                }
                Vec2 a2 = g.nodeAt(e2.fromId()).pos();
                Vec2 b2 = g.nodeAt(e2.toId()).pos();
                if (collinearOverlap(a1, b1, a2, b2)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean collinearOverlap(Vec2 a1, Vec2 b1, Vec2 a2, Vec2 b2) {
        long d1x = b1.x() - a1.x();
        long d1z = b1.z() - a1.z();
        long d2x = b2.x() - a2.x();
        long d2z = b2.z() - a2.z();

        if (d1x * d2z - d1z * d2x != 0) {
            return false; // not even parallel
        }

        long ex = a2.x() - a1.x();
        long ez = a2.z() - a1.z();
        if (ex * d1z - ez * d1x != 0) {
            return false; // parallel, but on a different line
        }

        // Collinear: project both segments onto d1's direction and check the 1D intervals overlap
        // by more than a single point.
        long length1Squared = d1x * d1x + d1z * d1z;
        long t2a = (a2.x() - a1.x()) * d1x + (a2.z() - a1.z()) * d1z;
        long t2b = (b2.x() - a1.x()) * d1x + (b2.z() - a1.z()) * d1z;
        long lo2 = Math.min(t2a, t2b);
        long hi2 = Math.max(t2a, t2b);
        long overlapLo = Math.max(0, lo2);
        long overlapHi = Math.min(length1Squared, hi2);
        return overlapHi > overlapLo;
    }
}

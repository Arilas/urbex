package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.terrain.CliffTerrain;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpineGrowthTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final Settlement HAMLET = new Settlement(SettlementClass.HAMLET, 0, 0);
    private static final Settlement VILLAGE = new Settlement(SettlementClass.VILLAGE, 0, 0);

    @Test
    void everySmallSettlementActuallyGrowsARoad() {
        // The bug this task exists to fix: both classes previously emitted a lone centre node.
        for (long seed = 0; seed < 100; seed++) {
            assertTrue(SpineGrowth.grow(seed, HAMLET, new FlatTerrain(64), P).edges().size() >= 1,
                    "hamlet seed " + seed + " grew no road at all");
            assertTrue(SpineGrowth.grow(seed, VILLAGE, new FlatTerrain(64), P).edges().size() >= 2,
                    "village seed " + seed + " grew fewer than two roads");
        }
    }

    @Test
    void growthIsDeterministic() {
        assertEquals(SpineGrowth.grow(7L, VILLAGE, new FlatTerrain(64), P),
                SpineGrowth.grow(7L, VILLAGE, new FlatTerrain(64), P));
    }

    @Test
    void differentSeedsGrowDifferentSpines() {
        assertTrue(!SpineGrowth.grow(1L, VILLAGE, new FlatTerrain(64), P)
                .equals(SpineGrowth.grow(2L, VILLAGE, new FlatTerrain(64), P)));
    }

    @Test
    void theSpineIsConnected() {
        for (long seed = 0; seed < 100; seed++) {
            assertTrue(SpineGrowth.grow(seed, VILLAGE, new FlatTerrain(64), P).isConnected(),
                    "village seed " + seed + " grew a disconnected spine");
        }
    }

    @Test
    void everyNodeStaysInsideTheSettlement() {
        for (long seed = 0; seed < 100; seed++) {
            RoadGraph g = SpineGrowth.grow(seed, VILLAGE, new FlatTerrain(64), P);
            Vec2 c = VILLAGE.centerBlock();
            int r = VILLAGE.radiusBlocks();
            for (RoadNode n : g.nodes()) {
                assertTrue(Math.abs(n.pos().x() - c.x()) <= r && Math.abs(n.pos().z() - c.z()) <= r,
                        "seed " + seed + " put node " + n.pos() + " outside the settlement");
            }
        }
    }

    @Test
    void aVillageOutgrowsAHamlet() {
        int hamlet = 0;
        int village = 0;
        for (long seed = 0; seed < 50; seed++) {
            hamlet += SpineGrowth.grow(seed, HAMLET, new FlatTerrain(64), P).edges().size();
            village += SpineGrowth.grow(seed, VILLAGE, new FlatTerrain(64), P).edges().size();
        }
        assertTrue(village > hamlet, "village total " + village + " did not exceed hamlet " + hamlet);
    }

    @Test
    void aSpineHasNoCycles() {
        // A spine is a tree by design. If it ever closes a loop, block extraction would start
        // finding faces and the roadside lot path would silently stop being the right one.
        for (long seed = 0; seed < 100; seed++) {
            RoadGraph g = SpineGrowth.grow(seed, VILLAGE, new FlatTerrain(64), P);
            assertEquals(g.nodes().size() - 1, g.edges().size(),
                    "village seed " + seed + " is not a tree: "
                            + g.nodes().size() + " nodes, " + g.edges().size() + " edges");
        }
    }

    @Test
    void noSpineRoadClimbsACliff() {
        CliffTerrain cliff = new CliffTerrain(64, 40, 0);
        for (long seed = 0; seed < 50; seed++) {
            RoadGraph g = SpineGrowth.grow(seed, VILLAGE, cliff, P);
            for (RoadEdge e : g.edges()) {
                if (e.bridge()) {
                    continue;
                }
                Vec2 a = g.nodeAt(e.fromId()).pos();
                Vec2 b = g.nodeAt(e.toId()).pos();
                assertTrue(Math.abs(cliff.heightAt(a.x(), a.z()) - cliff.heightAt(b.x(), b.z()))
                                <= P.maxSlopePerSegment(),
                        "seed " + seed + " ran a spine road up a cliff");
            }
        }
    }

    /**
     * {@code aSpineHasNoCycles} only counts nodes and edges, which held true even before
     * {@code crossesExistingEdge} existed (a graph can be a tree by that count and still cross itself
     * geometrically - a branch curving back across the spine it grew from adds no node or edge, just
     * an X in the middle of nowhere). This is the test that actually exercises the crossing rejection:
     * for every pair of edges that don't already share a node - sharing one is an ordinary junction,
     * not a crossing - assert they don't properly intersect. Independently re-derived (not calling
     * {@code SpineGrowth}'s own {@code crossesExistingEdge}) so a bug in that method's own segment math
     * couldn't hide from its own test.
     */
    @Test
    void noTwoNonAdjacentSpineEdgesCross() {
        for (Settlement s : new Settlement[]{HAMLET, VILLAGE}) {
            for (long seed = 0; seed < 300; seed++) {
                RoadGraph g = SpineGrowth.grow(seed, s, new FlatTerrain(64), P);
                var edges = g.edges();
                for (int i = 0; i < edges.size(); i++) {
                    for (int j = i + 1; j < edges.size(); j++) {
                        RoadEdge e1 = edges.get(i);
                        RoadEdge e2 = edges.get(j);
                        if (shareNode(e1, e2)) {
                            continue;
                        }
                        Vec2 a1 = g.nodeAt(e1.fromId()).pos();
                        Vec2 b1 = g.nodeAt(e1.toId()).pos();
                        Vec2 a2 = g.nodeAt(e2.fromId()).pos();
                        Vec2 b2 = g.nodeAt(e2.toId()).pos();
                        assertTrue(!segmentsIntersect(a1, b1, a2, b2),
                                s.cls() + " seed " + seed + ": edges " + i + " (" + a1 + "->" + b1
                                        + ") and " + j + " (" + a2 + "->" + b2
                                        + ") cross without sharing a node");
                    }
                }
            }
        }
    }

    private static boolean shareNode(RoadEdge a, RoadEdge b) {
        return a.fromId() == b.fromId() || a.fromId() == b.toId()
                || a.toId() == b.fromId() || a.toId() == b.toId();
    }

    /** General-position segment intersection, exact in long arithmetic; touching counts as crossing. */
    private static boolean segmentsIntersect(Vec2 p1, Vec2 p2, Vec2 p3, Vec2 p4) {
        long d1 = cross(p3, p4, p1);
        long d2 = cross(p3, p4, p2);
        long d3 = cross(p1, p2, p3);
        long d4 = cross(p1, p2, p4);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        if (d1 == 0 && onSegment(p3, p4, p1)) {
            return true;
        }
        if (d2 == 0 && onSegment(p3, p4, p2)) {
            return true;
        }
        if (d3 == 0 && onSegment(p1, p2, p3)) {
            return true;
        }
        return d4 == 0 && onSegment(p1, p2, p4);
    }

    private static long cross(Vec2 a, Vec2 b, Vec2 c) {
        return (long) (b.x() - a.x()) * (c.z() - a.z()) - (long) (b.z() - a.z()) * (c.x() - a.x());
    }

    private static boolean onSegment(Vec2 a, Vec2 b, Vec2 p) {
        return Math.min(a.x(), b.x()) <= p.x() && p.x() <= Math.max(a.x(), b.x())
                && Math.min(a.z(), b.z()) <= p.z() && p.z() <= Math.max(a.z(), b.z());
    }

    @Test
    void spineBridgesAreStillDerived() {
        for (long seed = 0; seed < 50; seed++) {
            RoadGraph g = SpineGrowth.grow(seed, VILLAGE, new FlatTerrain(64), P);
            for (RoadEdge e : g.edges()) {
                assertTrue(!e.bridge(), "seed " + seed + " built a spine bridge over dry ground");
            }
        }
        RiverTerrain river = new RiverTerrain(64, 0, 20);
        boolean sawBridge = false;
        for (long seed = 0; seed < 200 && !sawBridge; seed++) {
            for (RoadEdge e : SpineGrowth.grow(seed, VILLAGE, river, P).edges()) {
                sawBridge |= e.bridge();
            }
        }
        assertTrue(sawBridge, "no village spine ever bridged a river running through it");
    }
}

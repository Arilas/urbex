package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeDetectorTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final Settlement TOWN = new Settlement(SettlementClass.TOWN, 0, 0);

    @Test
    void everyEdgeCrossingTheRiverIsABridge() {
        RiverTerrain river = new RiverTerrain(64, 0, 24);
        for (long seed = 0; seed < 25; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, TOWN, river, P);
            for (RoadEdge e : g.edges()) {
                Vec2 a = g.nodeAt(e.fromId()).pos();
                Vec2 b = g.nodeAt(e.toId()).pos();
                if (river.crosses(a.x(), b.x())) {
                    assertTrue(e.bridge(),
                            "seed " + seed + ": edge " + a + " -> " + b + " crosses the river but is not a bridge");
                }
            }
        }
    }

    @Test
    void nothingIsABridgeOnDryGround() {
        for (long seed = 0; seed < 25; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, TOWN, new FlatTerrain(64), P);
            for (RoadEdge e : g.edges()) {
                assertTrue(!e.bridge(), "seed " + seed + " built a bridge over dry ground");
            }
        }
    }

    /**
     * The next four tests build graphs directly through {@link RoadGraph#builder()} rather than
     * growing one, so each behaviour of {@link BridgeDetector#mark} is isolated and its numbers are
     * exact and hand-checkable, instead of being incidental facts about whatever
     * {@link ArterialGrowth} happened to produce for some seed.
     */
    @Test
    void anEdgeCrossingARiverOfKnownWidthReportsAConsistentSpan() {
        // Water where |x| < 12 (a 24-wide channel straddling x=0). Samples run every 4 blocks from
        // x=-40 to x=40: ...,-12,-8,-4,0,4,8,12,... of which -8,-4,0,4,8 are wet (-12 and 12 are not,
        // since the channel test is a strict "<"). That is 5 consecutive wet samples -> 5 * 4 = 20.
        RiverTerrain river = new RiverTerrain(64, 0, 24);
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(-40, 0))
                .node(new Vec2(40, 0))
                .edge(0, 1, RoadClass.ARTERIAL)
                .build();

        RoadGraph marked = BridgeDetector.mark(g, river, P, 0);

        assertEquals(1, marked.edges().size());
        RoadEdge e = marked.edges().get(0);
        assertTrue(e.bridge(), "an edge crossing the channel should be marked as a bridge");
        assertEquals(20, e.waterSpanBlocks());
    }

    @Test
    void theLongestChannelWinsOverTheSumAndOverTheFirst() {
        // Two disjoint wet bands along the same edge: a short one (a single 4-block-wide slice at
        // x in [10,14), one wet sample) and a longer one (x in [50,74), a run of 6 consecutive wet
        // samples = 24 blocks). The edge must report the longer run (24), not their sum (28) and not
        // the first one encountered (4).
        TerrainSampler twoChannels = new TerrainSampler() {
            @Override public int heightAt(int x, int z) {
                return isWaterAt(x, z) ? 60 : 64;
            }
            @Override public boolean isWaterAt(int x, int z) {
                return (x >= 10 && x < 14) || (x >= 50 && x < 74);
            }
        };
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0))
                .node(new Vec2(100, 0))
                .edge(0, 1, RoadClass.ARTERIAL)
                .build();

        RoadGraph marked = BridgeDetector.mark(g, twoChannels, P, 0);

        assertEquals(1, marked.edges().size());
        RoadEdge e = marked.edges().get(0);
        assertTrue(e.bridge());
        assertEquals(24, e.waterSpanBlocks(), "should report the longer of the two channels, not 28 (sum) or 4 (first)");
    }

    @Test
    void anEdgeExceedingTheMaxBridgeSpanIsRejectedNotMarked() {
        // A 200-wide channel is far more than maxBridgeSpanBlocks (64) can cover in one edge: this
        // is not a bridge, it's a mistake, and BridgeDetector must drop the edge outright rather than
        // hand back an edge with bridge=true and an oversized span.
        RiverTerrain wideRiver = new RiverTerrain(64, 0, 200);
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(-100, 0))
                .node(new Vec2(100, 0))
                .edge(0, 1, RoadClass.ARTERIAL)
                .build();

        RoadGraph marked = BridgeDetector.mark(g, wideRiver, P, 0);

        assertTrue(marked.edges().isEmpty(), "an edge whose water run exceeds maxBridgeSpanBlocks must be rejected, not marked");
    }

    @Test
    void rejectingAnEdgeKeepsTheCentresComponentNotTheLargestOne() {
        // centre(0) -- A(1) -- B(2) -- C(3) -- D(4) -- E(5) -- F(6) -- G(7), a straight chain. The
        // A-B edge crosses a 300-wide impassable channel (from x=850 to x=1150) entirely, so it gets
        // rejected. That splits the graph into a near side {centre, A} (2 nodes) and a far side
        // {B, C, D, E, F, G} (6 nodes) - the far side is strictly larger. A rule that kept the
        // *largest* component would keep the far side and lose the centre; the correct rule keeps
        // whichever component the centre is actually in.
        RiverTerrain band = new RiverTerrain(64, 1000, 300); // wet where |x - 1000| < 150, i.e. (850, 1150)
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(0, 0))     // 0 centre
                .node(new Vec2(700, 0))   // 1 A - dry, this side of the channel
                .node(new Vec2(1300, 0))  // 2 B - dry, far side of the channel
                .node(new Vec2(1350, 0))  // 3 C
                .node(new Vec2(1400, 0))  // 4 D
                .node(new Vec2(1450, 0))  // 5 E
                .node(new Vec2(1500, 0))  // 6 F
                .node(new Vec2(1550, 0))  // 7 G
                .edge(0, 1, RoadClass.ARTERIAL)
                .edge(1, 2, RoadClass.ARTERIAL)  // spans the whole 300-wide channel: rejected
                .edge(2, 3, RoadClass.ARTERIAL)
                .edge(3, 4, RoadClass.ARTERIAL)
                .edge(4, 5, RoadClass.ARTERIAL)
                .edge(5, 6, RoadClass.ARTERIAL)
                .edge(6, 7, RoadClass.ARTERIAL)
                .build();

        int nearSideSize = 2;  // {centre, A}
        int farSideSize = 6;   // {B, C, D, E, F, G}
        assertTrue(farSideSize > nearSideSize, "the far side must be strictly larger for this test to prove anything");

        RoadGraph marked = BridgeDetector.mark(g, band, P, 0);

        assertEquals(nearSideSize, marked.nodes().size(),
                "must keep the centre's (smaller) component, not the (larger) far side");
        assertEquals(1, marked.edges().size());
        assertTrue(marked.isConnected());
        for (RoadNode n : marked.nodes()) {
            assertTrue(n.pos().x() <= 700, "a node from the far side survived: " + n);
        }
    }

    @Test
    void markingTwiceIsIdempotent() {
        RiverTerrain river = new RiverTerrain(64, 0, 24);
        RoadGraph g = RoadGraph.builder()
                .node(new Vec2(-40, 0))
                .node(new Vec2(40, 0))
                .edge(0, 1, RoadClass.ARTERIAL)
                .build();

        RoadGraph markedOnce = BridgeDetector.mark(g, river, P, 0);
        RoadGraph markedTwice = BridgeDetector.mark(markedOnce, river, P, 0);

        assertEquals(markedOnce.nodes(), markedTwice.nodes());
        assertEquals(markedOnce.edges(), markedTwice.edges());
    }
}

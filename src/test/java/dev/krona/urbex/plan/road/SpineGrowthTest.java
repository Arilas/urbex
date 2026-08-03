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

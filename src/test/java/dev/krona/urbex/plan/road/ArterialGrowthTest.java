package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.terrain.CliffTerrain;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArterialGrowthTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final Settlement TOWN = new Settlement(SettlementClass.TOWN, 0, 0);

    @Test
    void growthIsDeterministic() {
        RoadGraph a = ArterialGrowth.grow(1L, TOWN, new FlatTerrain(64), P);
        RoadGraph b = ArterialGrowth.grow(1L, TOWN, new FlatTerrain(64), P);
        assertEquals(a.edges(), b.edges());
        assertEquals(a.nodes(), b.nodes());
    }

    @Test
    void differentSeedsGrowDifferentNetworks() {
        RoadGraph a = ArterialGrowth.grow(1L, TOWN, new FlatTerrain(64), P);
        RoadGraph b = ArterialGrowth.grow(2L, TOWN, new FlatTerrain(64), P);
        assertTrue(!a.edges().equals(b.edges()), "two seeds grew the identical network");
    }

    @Test
    void theNetworkIsConnected() {
        for (long seed = 0; seed < 25; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, TOWN, new FlatTerrain(64), P);
            assertTrue(g.isConnected(), "seed " + seed + " produced a disconnected network");
        }
    }

    @Test
    void everyNodeStaysInsideTheSettlement() {
        int r = TOWN.radiusBlocks();
        Vec2 c = TOWN.centerBlock();
        for (long seed = 0; seed < 25; seed++) {
            for (RoadNode n : ArterialGrowth.grow(seed, TOWN, new FlatTerrain(64), P).nodes()) {
                assertTrue(Math.abs(n.pos().x() - c.x()) <= r && Math.abs(n.pos().z() - c.z()) <= r,
                        "seed " + seed + " put node " + n.pos() + " outside the settlement");
            }
        }
    }

    @Test
    void aBiggerClassGrowsABiggerNetwork() {
        int town = ArterialGrowth.grow(5L, TOWN, new FlatTerrain(64), P).edges().size();
        int city = ArterialGrowth.grow(5L, new Settlement(SettlementClass.CITY, 0, 0),
                new FlatTerrain(64), P).edges().size();
        assertTrue(city > town, "a city (" + city + " edges) should out-grow a town (" + town + ")");
    }

    @Test
    void noRoadClimbsACliff() {
        TerrainSampler cliff = new CliffTerrain(64, 40, 0);
        for (long seed = 0; seed < 25; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, TOWN, cliff, P);
            for (RoadEdge e : g.edges()) {
                if (e.bridge()) {
                    continue;
                }
                Vec2 a = g.nodeAt(e.fromId()).pos();
                Vec2 b = g.nodeAt(e.toId()).pos();
                int slope = Math.abs(cliff.heightAt(a.x(), a.z()) - cliff.heightAt(b.x(), b.z()));
                assertTrue(slope <= P.maxSlopePerSegment(),
                        "seed " + seed + " ran a road up a slope of " + slope);
            }
        }
    }

    @Test
    void roadsDoNotRunAlongARiverbed() {
        RiverTerrain river = new RiverTerrain(64, 0, 24);
        for (long seed = 0; seed < 25; seed++) {
            RoadGraph g = ArterialGrowth.grow(seed, TOWN, river, P);
            for (RoadEdge e : g.edges()) {
                Vec2 a = g.nodeAt(e.fromId()).pos();
                Vec2 b = g.nodeAt(e.toId()).pos();
                boolean bothInWater = river.isWaterAt(a.x(), a.z()) && river.isWaterAt(b.x(), b.z());
                assertTrue(!bothInWater,
                        "seed " + seed + " ran a road along the riverbed: " + a + " -> " + b);
            }
        }
    }
}

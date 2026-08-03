package dev.krona.urbex.plan.road;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

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
}

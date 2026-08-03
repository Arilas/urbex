package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.RoadGraph;
import dev.krona.urbex.plan.road.SpineGrowth;
import dev.krona.urbex.plan.terrain.FlatTerrain;
import dev.krona.urbex.plan.terrain.RiverTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadsideLotsTest {

    private static final PlanParams P = PlanParams.defaults();
    private static final Settlement VILLAGE = new Settlement(SettlementClass.VILLAGE, 0, 0);

    private static List<Lot> lots(long seed) {
        FlatTerrain flat = new FlatTerrain(64);
        RoadGraph g = SpineGrowth.grow(seed, VILLAGE, flat, P);
        return RoadsideLots.place(seed, g, VILLAGE, flat, P);
    }

    @Test
    void aVillageGetsLots() {
        for (long seed = 0; seed < 50; seed++) {
            assertTrue(!lots(seed).isEmpty(), "village seed " + seed + " produced no lots");
        }
    }

    @Test
    void lotsNeverOverlap() {
        for (long seed = 0; seed < 50; seed++) {
            List<Lot> l = lots(seed);
            for (int i = 0; i < l.size(); i++) {
                for (int j = i + 1; j < l.size(); j++) {
                    assertTrue(!l.get(i).footprint().intersects(l.get(j).footprint()),
                            "seed " + seed + ": lots " + i + " and " + j + " overlap");
                }
            }
        }
    }

    @Test
    void everyLotFrontsARealRoad() {
        for (long seed = 0; seed < 50; seed++) {
            RoadGraph g = SpineGrowth.grow(seed, VILLAGE, new FlatTerrain(64), P);
            for (Lot lot : RoadsideLots.place(seed, g, VILLAGE, new FlatTerrain(64), P)) {
                assertTrue(lot.frontingEdgeIndex() >= 0
                                && lot.frontingEdgeIndex() < g.edges().size(),
                        "seed " + seed + ": lot " + lot.id() + " fronts onto no road");
            }
        }
    }

    @Test
    void noLotSitsOnTheRoadItFronts() {
        // The setback is what stops a building being placed in the carriageway.
        for (long seed = 0; seed < 50; seed++) {
            RoadGraph g = SpineGrowth.grow(seed, VILLAGE, new FlatTerrain(64), P);
            for (Lot lot : RoadsideLots.place(seed, g, VILLAGE, new FlatTerrain(64), P)) {
                var e = g.edges().get(lot.frontingEdgeIndex());
                Vec2 a = g.nodeAt(e.fromId()).pos();
                Vec2 b = g.nodeAt(e.toId()).pos();
                assertTrue(distanceToSegment(lot.footprint().center(), a, b) >= P.roadsideSetbackBlocks(),
                        "seed " + seed + ": lot " + lot.id() + " sits on its own road");
            }
        }
    }

    @Test
    void noLotSitsUnderWater() {
        RiverTerrain river = new RiverTerrain(64, 0, 20);
        for (long seed = 0; seed < 50; seed++) {
            RoadGraph g = SpineGrowth.grow(seed, VILLAGE, river, P);
            for (Lot lot : RoadsideLots.place(seed, g, VILLAGE, river, P)) {
                Vec2 c = lot.footprint().center();
                assertTrue(!river.isWaterAt(c.x(), c.z()),
                        "seed " + seed + ": lot " + lot.id() + " sits in the river");
            }
        }
    }

    @Test
    void placementIsDeterministic() {
        assertEquals(lots(9L), lots(9L));
    }

    private static double distanceToSegment(Vec2 p, Vec2 a, Vec2 b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len2 = dx * dx + dz * dz;
        if (len2 == 0) {
            return Math.hypot(p.x() - a.x(), p.z() - a.z());
        }
        double t = Math.max(0, Math.min(1, ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / len2));
        return Math.hypot(p.x() - (a.x() + t * dx), p.z() - (a.z() + t * dz));
    }
}

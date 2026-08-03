package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.SettlementClass;
import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.RoadEdge;
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

    /**
     * The setback plus the road's own half-width is what stops a building being placed in - or too
     * close to - the carriageway, for every road in the settlement, not just the one a lot fronts.
     * <p>
     * Whole-branch review (C1) found this test's original form only ever checked the fronting edge,
     * which was exactly what production did too and so could never have caught it doing so: measured
     * over 100 flat-terrain seeds, 63.2% of hamlet lots and 64.3% of village lots sat closer than the
     * setback to some <em>other</em> road, 20.8%/29.3% with a road actually running through the
     * footprint (concretely, HAMLET seed 1 lot 0 was crossed by an edge it didn't front at all). This
     * checks every edge in the graph, each against its own required clearance -
     * {@link PlanParams#roadHalfWidthBlocks} for every edge, plus {@link PlanParams#roadsideSetbackBlocks()}
     * for the one this lot fronts - matching {@code RoadClearance.clearsEveryRoad}'s own rule exactly,
     * but re-derived here rather than calling it, so a bug in that method's math could not hide from
     * its own test.
     */
    @Test
    void noLotSitsOnAnyRoad() {
        for (long seed = 0; seed < 50; seed++) {
            RoadGraph g = SpineGrowth.grow(seed, VILLAGE, new FlatTerrain(64), P);
            List<RoadEdge> edges = g.edges();
            for (Lot lot : RoadsideLots.place(seed, g, VILLAGE, new FlatTerrain(64), P)) {
                for (int i = 0; i < edges.size(); i++) {
                    RoadEdge e = edges.get(i);
                    Vec2 a = g.nodeAt(e.fromId()).pos();
                    Vec2 b = g.nodeAt(e.toId()).pos();
                    double required = P.roadHalfWidthBlocks(e.cls())
                            + (i == lot.frontingEdgeIndex() ? P.roadsideSetbackBlocks() : 0);
                    double distance = distanceToFootprint(a, b, lot.footprint());
                    assertTrue(distance >= required,
                            "seed " + seed + ": lot " + lot.id() + " footprint " + lot.footprint()
                                    + " is only " + distance + " blocks from road " + i + " (" + e.cls()
                                    + (i == lot.frontingEdgeIndex() ? ", fronted" : "") + "), needs >= "
                                    + required);
                }
            }
        }
    }

    @Test
    void noLotSitsUnderWater() {
        // A lot is an area, not its centre point: checking only the centre missed a thin wet strip
        // hugging a lot's edge, which the sparse (9-point) old dryness probe missed too - the same
        // sample points, the same blind spot. This checks every block of the footprint, which is what
        // "no lot sits under water" has to mean once "sits" is read honestly.
        RiverTerrain river = new RiverTerrain(64, 0, 20);
        for (long seed = 0; seed < 50; seed++) {
            RoadGraph g = SpineGrowth.grow(seed, VILLAGE, river, P);
            for (Lot lot : RoadsideLots.place(seed, g, VILLAGE, river, P)) {
                Rect fp = lot.footprint();
                for (int x = fp.minX(); x <= fp.maxX(); x++) {
                    for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                        assertTrue(!river.isWaterAt(x, z),
                                "seed " + seed + ": lot " + lot.id() + " footprint " + fp
                                        + " contains a wet block at " + x + "," + z);
                    }
                }
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

    /**
     * The exact minimum distance between segment {@code a-b} and axis-aligned rectangle
     * {@code r}, independently derived from {@code RoadsideLots}' own placement logic so this test
     * actually verifies something rather than restating the production code back at itself.
     * <p>
     * Zero if they touch or overlap at all. Otherwise, since both the segment and the rectangle are
     * convex, the minimum separation is always achieved at one of: a segment endpoint's distance to
     * the rectangle, or a rectangle corner's distance to the segment - six candidate distances in
     * total, whichever is smallest.
     */
    private static double distanceToFootprint(Vec2 a, Vec2 b, Rect r) {
        if (segmentIntersectsRect(a, b, r)) {
            return 0.0;
        }
        double best = Math.min(distanceToRect(a, r), distanceToRect(b, r));
        Vec2[] corners = {
                new Vec2(r.minX(), r.minZ()), new Vec2(r.maxX(), r.minZ()),
                new Vec2(r.maxX(), r.maxZ()), new Vec2(r.minX(), r.maxZ())
        };
        for (Vec2 c : corners) {
            best = Math.min(best, distanceToSegment(c, a, b));
        }
        return best;
    }

    private static double distanceToRect(Vec2 p, Rect r) {
        double dx = Math.max(Math.max(r.minX() - p.x(), 0), p.x() - r.maxX());
        double dz = Math.max(Math.max(r.minZ() - p.z(), 0), p.z() - r.maxZ());
        return Math.hypot(dx, dz);
    }

    private static boolean segmentIntersectsRect(Vec2 a, Vec2 b, Rect r) {
        if (r.contains(a) || r.contains(b)) {
            return true;
        }
        Vec2 topLeft = new Vec2(r.minX(), r.minZ());
        Vec2 topRight = new Vec2(r.maxX(), r.minZ());
        Vec2 bottomRight = new Vec2(r.maxX(), r.maxZ());
        Vec2 bottomLeft = new Vec2(r.minX(), r.maxZ());
        return segmentsIntersect(a, b, topLeft, topRight)
                || segmentsIntersect(a, b, topRight, bottomRight)
                || segmentsIntersect(a, b, bottomRight, bottomLeft)
                || segmentsIntersect(a, b, bottomLeft, topLeft);
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
}

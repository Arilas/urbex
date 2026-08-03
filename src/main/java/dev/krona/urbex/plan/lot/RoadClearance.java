package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.RoadEdge;
import dev.krona.urbex.plan.road.RoadGraph;

/**
 * Whether a footprint clears every road in a settlement's graph - not just the one it fronts.
 * <p>
 * Whole-branch review (C1) found {@code RoadsideLots} checked a candidate lot against only the edge
 * it was offered on: 63.2% of hamlet lots and 64.3% of village lots sat closer than the setback to
 * some <em>other</em> road, 20.8%/29.3% with a road actually running through the footprint (measured
 * over 100 flat-terrain seeds). The same review (I2) found {@code LotSubdivider} checked against no
 * road at all: {@code CityBlock}'s outline <em>is</em> the road centreline, and the old flat 1-block
 * shrink gave a lot as little as 0.011 real blocks of clearance from it (measured over 40 TOWN seeds).
 * <p>
 * Required clearance is per-edge, from {@link PlanParams#roadHalfWidthBlocks}, since an ARTERIAL
 * carriageway is wider than a LOCAL one: judging every edge against one shared distance would let a
 * footprint sitting safely clear of a wide road still be crossed by a narrower one nearby, or force
 * every lot in the settlement to keep the widest road's clearance from roads that are actually
 * narrow.
 * <p>
 * {@code frontingExtraSetback} - a building-to-kerb gap, e.g. {@link PlanParams#roadsideSetbackBlocks()}
 * - applies only to {@code frontingEdgeIndex}, not every edge. A lot's relationship to the road it
 * fronts is different from its relationship to a road it merely happens to be near: the fronting
 * setback is a frontage convention (a front yard), but a lot's side or rear does not owe a road it
 * isn't presented toward the same margin, only enough clearance not to physically overlap the
 * carriageway. Applying the full frontage setback to every nearby edge - not just the one being
 * fronted - measured as the dominant cause of small settlements losing most of their buildable lots
 * once real road widths existed: {@code SpineGrowth} branches grow perpendicular to the spine, the
 * same direction a roadside lot's depth extends, so a branch a short way along the spine from a lot's
 * own position sits exactly where that lot's depth reaches - close enough to violate a full frontage
 * setback from a road never fronted, while still leaving room enough to clear its bare carriageway
 * width. Pass -1 for {@code frontingEdgeIndex} (as {@code LotSubdivider} does) when no edge should get
 * the extra - {@link RoadEdge} ids are never negative, so it can never accidentally match one.
 */
final class RoadClearance {

    private RoadClearance() {
    }

    /**
     * True only if {@code footprint} clears every edge of {@code g} by that edge's own required
     * distance - {@link PlanParams#roadHalfWidthBlocks} for every edge, plus
     * {@code frontingExtraSetback} for {@code frontingEdgeIndex} alone.
     */
    static boolean clearsEveryRoad(RoadGraph g, Rect footprint, int frontingEdgeIndex,
                                    double frontingExtraSetback, PlanParams p) {
        var edges = g.edges();
        for (int i = 0; i < edges.size(); i++) {
            RoadEdge e = edges.get(i);
            double extra = i == frontingEdgeIndex ? frontingExtraSetback : 0.0;
            double required = p.roadHalfWidthBlocks(e.cls()) + extra;
            Vec2 a = g.nodeAt(e.fromId()).pos();
            Vec2 b = g.nodeAt(e.toId()).pos();
            if (distanceToFootprint(a, b, footprint) < required) {
                return false;
            }
        }
        return true;
    }

    /**
     * The exact minimum distance between segment {@code a-b} and axis-aligned rectangle
     * {@code footprint}. Zero if they touch or overlap at all. Otherwise, since both a segment and an
     * axis-aligned rectangle are convex, the true minimum separation is always achieved at one of six
     * candidates: each segment endpoint's distance to the rectangle, or each rectangle corner's
     * distance to the segment.
     */
    static double distanceToFootprint(Vec2 a, Vec2 b, Rect footprint) {
        if (segmentIntersectsRect(a, b, footprint)) {
            return 0.0;
        }
        double best = Math.min(distanceToRect(a, footprint), distanceToRect(b, footprint));
        Vec2[] corners = {
                new Vec2(footprint.minX(), footprint.minZ()), new Vec2(footprint.maxX(), footprint.minZ()),
                new Vec2(footprint.maxX(), footprint.maxZ()), new Vec2(footprint.minX(), footprint.maxZ())
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

    private static double distanceToSegment(Vec2 p, Vec2 a, Vec2 b) {
        double ax = a.x(), az = a.z();
        double dx = b.x() - ax, dz = b.z() - az;
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq == 0
                ? 0
                : Math.max(0.0, Math.min(1.0, ((p.x() - ax) * dx + (p.z() - az) * dz) / lengthSq));
        double cx = ax + t * dx, cz = az + t * dz;
        double ddx = p.x() - cx, ddz = p.z() - cz;
        return Math.sqrt(ddx * ddx + ddz * ddz);
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

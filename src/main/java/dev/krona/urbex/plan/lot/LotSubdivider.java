package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.Hash;
import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.PlanPurpose;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.block.CityBlock;
import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.geom.Polygon;
import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.RoadEdge;
import dev.krona.urbex.plan.road.RoadGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts one {@link CityBlock} into buildable {@link Lot}s.
 * <p>
 * The block's bounding rect is split recursively wherever it is more than twice the district's
 * target lot size along its longer side, each cut jittered around the midpoint and addressed by the
 * rect being cut — so the split of one sub-rect can never perturb its sibling's.
 * <p>
 * A block is not rectangular — spokes and rings meet at arbitrary bearings — so a target-size leaf
 * cannot simply be trusted once it reaches the usual size threshold: its corners may reach past the
 * block's own (possibly wedge-shaped) boundary, which for a block that shares that boundary with a
 * neighbour means straying into the neighbour's territory. The same is true of water: a leaf wide
 * enough to span a river can have a dry centre and dry corners while its middle sits in the channel.
 * {@link #refineToFit} is what checks for both properly (every corner of the leaf's would-be
 * footprint against the outline, and every block of it against {@link FootprintDryness#isFullyDry})
 * and, if a leaf fails either, keeps splitting it — past the district's normal target size if it has
 * to — rather than discarding it outright. That is what keeps a thin, wedge-shaped block, or a block
 * that only grazes a riverbank, from losing its lots wholesale: the boundary gets approximated by a
 * "staircase" of smaller-than-target lots hugging it, instead of one oversized candidate that doesn't
 * fit and is thrown away.
 */
public final class LotSubdivider {

    /** How far the split position may drift from the midpoint, as a fraction of the side being cut. */
    private static final double SPLIT_JITTER_FRACTION = 0.2;

    /** A leaf narrower than this on either axis cannot survive the 1-block shrink on every side. */
    private static final int MIN_LEAF_SIDE_BLOCKS = 3;

    /**
     * How far below the district's target size {@link #refineToFit} is allowed to keep cutting a
     * leaf that does not fit. Without a floor tied to the target size, refinement chases a diagonal
     * boundary all the way down to {@link #MIN_LEAF_SIDE_BLOCKS} — measured on a real settlement,
     * that produced a lot count 8-17x higher than before this fix, and most of it (55%) was lots
     * under 3x3. That is not "recovering the wedge's area", it is a staircase fine enough to be
     * mostly noise: a building needs a plausible footprint, not a pixel of dry, roadside, off-outline
     * ground. At 0.75 — measured on the same settlements — under 4% of lots come out under 3x3, the
     * lot count only roughly doubles or triples rather than jumping by an order of magnitude, and
     * {@code Planner}'s cross-block dedup pass still finds nothing to remove: the floor trims how far
     * refinement chases a boundary, not whether the result actually fits.
     */
    private static final double REFINEMENT_FLOOR_FRACTION = 0.75;

    private LotSubdivider() {
    }

    public static List<Lot> subdivide(long seed, CityBlock b, District d, RoadGraph g, TerrainSampler t,
                                       PlanParams p) {
        double targetSize = targetLotSize(d, p);

        List<Rect> leaves = new ArrayList<>();
        splitRect(b.boundingBox(), targetSize, seed, b, t, leaves);

        // Every leaf here is already guaranteed, by construction, to fit entirely inside b's outline
        // and off the water once shrunk (refineToFit only ever emits leaves that pass fits). Only
        // whether a road is close enough to front remains to be checked.
        List<Lot> lots = new ArrayList<>();
        for (Rect leaf : leaves) {
            Vec2 centre = leaf.center();

            int frontingEdgeIndex = -1;
            double bestDistance = Double.MAX_VALUE;
            List<RoadEdge> edges = g.edges();
            for (int i = 0; i < edges.size(); i++) {
                RoadEdge edge = edges.get(i);
                Vec2 from = g.nodeAt(edge.fromId()).pos();
                Vec2 to = g.nodeAt(edge.toId()).pos();
                double distance = distanceToSegment(centre, from, to);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    frontingEdgeIndex = i;
                }
            }
            if (frontingEdgeIndex < 0 || bestDistance > p.maxLotDepthBlocks()) {
                continue;
            }

            Rect footprint = shrink(leaf);
            // Step 4b of the brief. Shared with RoadsideLots - see WaterFrontage's doc for why this
            // is a full per-side scan rather than the three-point probe that used to live here.
            int waterSides = WaterFrontage.sidesOf(footprint, t, p.probeDistanceBlocks());
            int groundHeight = t.heightAt(centre.x(), centre.z());

            // Placeholder id and sizeClass: Planner rebuilds every lot once it has seen the whole
            // settlement, since sizeClass is an area tertile across all of it, not just this block.
            lots.add(new Lot(0, footprint, d, 0, frontingEdgeIndex, groundHeight, waterSides));
        }
        return lots;
    }

    /**
     * Step 1 of the brief: {@code coreLotSizeBlocks} for {@code CORE}, interpolating linearly to
     * {@code fringeLotSizeBlocks} for {@code FRINGE} across the four concentric bands, in order.
     * {@code WATERFRONT} takes {@code OUTER}'s size.
     */
    private static double targetLotSize(District d, PlanParams p) {
        double core = p.coreLotSizeBlocks();
        double fringe = p.fringeLotSizeBlocks();
        int rank = switch (d) {
            case CORE -> 0;
            case INNER -> 1;
            case OUTER, WATERFRONT -> 2;
            case FRINGE -> 3;
        };
        double t = rank / 3.0;
        return core + (fringe - core) * t;
    }

    /**
     * Recursively halves {@code r} while its longer side exceeds twice {@code targetSize}. Once a
     * branch reaches that scale, {@link #refineToFit} takes over to make sure what comes out
     * actually belongs to {@code b} and is dry.
     */
    private static void splitRect(Rect r, double targetSize, long seed, CityBlock b, TerrainSampler t,
                                   List<Rect> out) {
        int longer = Math.max(r.width(), r.depth());
        if (longer <= 2.0 * targetSize) {
            refineToFit(r, targetSize, seed, b, t, out);
            return;
        }
        Rect[] children = cut(r, seed);
        splitRect(children[0], targetSize, seed, b, t, out);
        splitRect(children[1], targetSize, seed, b, t, out);
    }

    /**
     * {@code r} is already at or under the district's target lot scale. If the footprint it would
     * become (r shrunk by 1 block on every side) lies entirely inside {@code b}'s outline and is
     * entirely dry ({@link FootprintDryness#isFullyDry}, shared with {@code RoadsideLots} - see its
     * doc for why this used to be a sparse probe and no longer is), keep it. Otherwise either the
     * block's boundary cuts through {@code r} or water does — cut {@code r} again with the same
     * jittered rule and try each half independently. That continues only while {@code r}'s longer
     * side is still above {@code targetSize * REFINEMENT_FLOOR_FRACTION}; once a branch reaches the
     * floor and still does not fit, it is dropped rather than cut smaller still, so a diagonal
     * boundary is approximated by a handful of smaller-than-usual lots, not a fine staircase of them.
     */
    private static void refineToFit(Rect r, double targetSize, long seed, CityBlock b, TerrainSampler t,
                                     List<Rect> out) {
        if (r.width() < MIN_LEAF_SIDE_BLOCKS || r.depth() < MIN_LEAF_SIDE_BLOCKS) {
            return;
        }
        Rect footprint = shrink(r);
        if (liesFullyInside(footprint, b) && FootprintDryness.isFullyDry(footprint, t)) {
            out.add(r);
            return;
        }
        if (Math.max(r.width(), r.depth()) <= targetSize * REFINEMENT_FLOOR_FRACTION) {
            return;
        }
        Rect[] children = cut(r, seed);
        refineToFit(children[0], targetSize, seed, b, t, out);
        refineToFit(children[1], targetSize, seed, b, t, out);
    }

    /**
     * Cuts {@code r} perpendicular to its longer side (ties go to X) at a position jittered ±20%
     * around the midpoint, drawn from {@code PlanPurpose.BLOCK_SPLIT_POS} addressed at {@code r}'s
     * own origin — so the split of one sub-rect can never perturb its sibling's.
     */
    private static Rect[] cut(Rect r, long seed) {
        int width = r.width();
        int depth = r.depth();
        long h = Hash.at(seed, r.minX(), r.minZ(), PlanPurpose.BLOCK_SPLIT_POS.key());
        double jitter = (Hash.unit(h) * 2.0 - 1.0) * SPLIT_JITTER_FRACTION;

        if (width >= depth) {
            double mid = (r.minX() + r.maxX()) / 2.0;
            int splitAt = clamp((int) Math.round(mid + jitter * width), r.minX() + 1, r.maxX() - 1);
            return new Rect[]{
                    new Rect(r.minX(), r.minZ(), splitAt - 1, r.maxZ()),
                    new Rect(splitAt, r.minZ(), r.maxX(), r.maxZ())
            };
        } else {
            double mid = (r.minZ() + r.maxZ()) / 2.0;
            int splitAt = clamp((int) Math.round(mid + jitter * depth), r.minZ() + 1, r.maxZ() - 1);
            return new Rect[]{
                    new Rect(r.minX(), r.minZ(), r.maxX(), splitAt - 1),
                    new Rect(r.minX(), splitAt, r.maxX(), r.maxZ())
            };
        }
    }

    private static Rect shrink(Rect r) {
        return new Rect(r.minX() + 1, r.minZ() + 1, r.maxX() - 1, r.maxZ() - 1);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Whether {@code rect} lies entirely within {@code b}'s outline: every corner (and the centre)
     * inside by {@link Polygon#contains}, and no outline edge crossing any of {@code rect}'s four
     * boundary edges. The corner check alone is not enough for a concave outline — an edge could dip
     * into the rect's interior and back out without ever crossing a corner — so both are needed.
     */
    private static boolean liesFullyInside(Rect rect, CityBlock b) {
        Polygon outline = b.outline();
        Vec2 topLeft = new Vec2(rect.minX(), rect.minZ());
        Vec2 topRight = new Vec2(rect.maxX(), rect.minZ());
        Vec2 bottomRight = new Vec2(rect.maxX(), rect.maxZ());
        Vec2 bottomLeft = new Vec2(rect.minX(), rect.maxZ());
        if (!outline.contains(rect.center())
                || !outline.contains(topLeft) || !outline.contains(topRight)
                || !outline.contains(bottomRight) || !outline.contains(bottomLeft)) {
            return false;
        }

        List<Vec2> ring = outline.ring();
        for (int i = 0; i < ring.size(); i++) {
            Vec2 a = ring.get(i);
            Vec2 bPoint = ring.get((i + 1) % ring.size());
            if (segmentCrossesRectBoundary(a, bPoint, topLeft, topRight, bottomRight, bottomLeft)) {
                return false;
            }
        }
        return true;
    }

    /** Whether segment {@code (a, b)} crosses any of the rect's four boundary edges. */
    private static boolean segmentCrossesRectBoundary(Vec2 a, Vec2 b, Vec2 topLeft, Vec2 topRight,
                                                        Vec2 bottomRight, Vec2 bottomLeft) {
        return segmentsIntersect(a, b, topLeft, topRight)
                || segmentsIntersect(a, b, topRight, bottomRight)
                || segmentsIntersect(a, b, bottomRight, bottomLeft)
                || segmentsIntersect(a, b, bottomLeft, topLeft);
    }

    /** General-position segment intersection test, exact in long arithmetic; touching counts as crossing. */
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
}

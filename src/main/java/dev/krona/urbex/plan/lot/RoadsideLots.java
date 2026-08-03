package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.district.DistrictMap;
import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.RoadEdge;
import dev.krona.urbex.plan.road.RoadGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives lots for a spine settlement straight from road frontage instead of subdividing an enclosed
 * block: {@link dev.krona.urbex.plan.road.SpineGrowth} grows a tree, which encloses no faces, so
 * {@code BlockExtractor}/{@code LotSubdivider} have nothing to work with.
 * <p>
 * For each edge, this walks its length in strides of the lot width and offers a candidate lot on each
 * side. A candidate is dropped outright - never shrunk - if it leaves the settlement bounds, is not
 * fully dry, overlaps a lot already kept, or (see {@link #placeAlongEdge}) does not actually clear the
 * road it fronts once measured for real. Because a lot is placed directly against the edge it was
 * offered on, {@code frontingEdgeIndex} is known by construction - the one thing this path doesn't
 * have to search for, unlike {@link LotSubdivider}, which has to find the nearest edge to every leaf
 * after the fact.
 * <p>
 * <b>On the footprint's shape.</b> {@link dev.krona.urbex.plan.geom.Rect} is axis-aligned, and stays
 * that way here: Minecraft buildings are axis-aligned too, so a rotated footprint could never have
 * held one anyway. An earlier version of this class built a true rectangle rotated to match the
 * road, then took its bounding box - which inflated every lot's real area well past its nominal size
 * (up to +227% at 45 degrees) and, worse, let the setback the rotated rectangle respected evaporate
 * in the conversion, since a bounding box's corners can reach back past the rotated rectangle's own
 * near edge and into the carriageway. This version never builds a rotated rectangle in the first
 * place: it chooses the lot's width and depth along whichever of X or Z the road segment runs closer
 * to (see {@link #placeAlongEdge}), so the shape offered is the shape kept.
 */
public final class RoadsideLots {

    /**
     * {@code sizeClass} for every roadside lot. {@code Planner.finalizeLots} ranks block-subdivision
     * lots into tertiles because that pipeline actually produces a range of areas (a block's
     * recursive split yields leaves of genuinely different sizes). Every roadside lot, by contrast,
     * is cut from the same {@code lotWidth} x {@code roadsideLotDepthBlocks} target - review found
     * that ranking these by area was ranking bounding-box inflation (which varies with a lot's road's
     * bearing) rather than plot size, since the old rotated-then-bbox construction was the only source
     * of area variance at all. Axis-aligned construction removes even that: real footprint area now
     * only ever differs between lots by a block or so of rounding. Encoding rounding noise as a
     * meaningful size tier would be worse than not encoding a tier at all, so every roadside lot gets
     * the same, middle {@code sizeClass} instead of a fabricated ranking.
     */
    private static final int SIZE_CLASS = 1;

    private RoadsideLots() {
    }

    /**
     * {@code seed} is part of the contract for symmetry with the rest of the road/lot pipeline, but
     * placement here is pure geometry - walking a known edge at a known width - so nothing in this
     * class actually draws from it.
     */
    public static List<Lot> place(long seed, RoadGraph g, Settlement s, TerrainSampler t, PlanParams p) {
        Vec2 centre = s.centerBlock();
        int radius = s.radiusBlocks();
        double lotWidth = lotWidthFor(p);

        List<Lot> lots = new ArrayList<>();
        List<RoadEdge> edges = g.edges();
        for (int i = 0; i < edges.size(); i++) {
            RoadEdge edge = edges.get(i);
            Vec2 a = g.nodeAt(edge.fromId()).pos();
            Vec2 b = g.nodeAt(edge.toId()).pos();
            placeAlongEdge(i, a, b, lotWidth, centre, radius, t, p, lots);
        }
        return assignIds(lots);
    }

    /**
     * Lot width, in blocks, along the road - derived from {@link PlanParams#spineSegmentLengthBlocks()}
     * rather than the block-subdivision pipeline's {@link PlanParams#coreLotSizeBlocks()} /
     * {@link PlanParams#fringeLotSizeBlocks()} (a spine settlement has no concentric bands for those to
     * describe, and it briefly used to anyway - see {@code spineSegmentLengthBlocks}' own doc for why
     * that direction of coupling was wrong). Deriving width from segment length instead means a class
     * whose spine takes short, frequent steps gets correspondingly small plots and a class with longer
     * steps gets bigger ones - a hamlet plot ends up smaller than a city's block-subdivided one, which
     * is exactly the relationship a hamlet's buildings should have anyway.
     * <p>
     * The 2-block margin below the raw segment length isn't arbitrary: {@code SpineGrowth}'s per-step
     * rounding of (dx, dz) to the nearest block means a real edge's length can land a fraction under
     * its nominal {@code spineSegmentLengthBlocks}. Without margin, a lot sized to exactly that nominal
     * length could occasionally find no along-position satisfies "starts before the edge's real end"
     * and lose an edge's only candidate to a sub-block rounding difference.
     */
    private static double lotWidthFor(PlanParams p) {
        return Math.max(1, p.spineSegmentLengthBlocks() - 2);
    }

    /**
     * Walks edge {@code a-b} in strides of {@code lotWidth}, offering one candidate lot per side at
     * each stride. Rather than building a rotated rectangle and taking its bounding box (the bug
     * review caught - see the class doc), each candidate is axis-aligned from the start: whichever of
     * X or Z the edge's own displacement is larger along becomes the lot's "along-road" axis (its
     * width, {@code lotWidth}), and the other becomes its "away-from-road" axis (its depth, from
     * {@code roadsideSetbackBlocks} to {@code roadsideSetbackBlocks + roadsideLotDepthBlocks}).
     * <p>
     * The away-from-road offset has to account for two things, not just the edge's steepness: a
     * purely axis-aligned offset from a single reference point only gives the right perpendicular
     * distance to the road <em>at that point</em>. Away from it, the road drifts along the
     * non-dominant axis while the lot's near edge stays a straight (non-drifting) line, so on a
     * diagonal edge one end of that near edge creeps back toward the road even as the other end pulls
     * further away - and with {@code lotWidth} close to a whole segment's length, that drift is not a
     * rounding-scale correction, it is comparable to the offset itself. {@code perpOffset}/
     * {@code perpFar} below add {@code halfWidth} scaled by the edge's non-dominant-axis ratio to
     * cover the worst case across the lot's whole width, not just its centre. {@link
     * #realSetbackClears} still re-measures the real, final distance and rejects outright if this
     * construction falls short anyway (integer rounding, mostly) - the guarantee comes from that
     * check, this is what keeps it from firing on nearly everything.
     */
    private static void placeAlongEdge(int edgeIndex, Vec2 a, Vec2 b, double lotWidth, Vec2 centre, int radius,
                                        TerrainSampler t, PlanParams p, List<Lot> lots) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len = Math.hypot(dx, dz);
        if (len < 1.0) {
            // A degenerate (zero-length) edge has no frontage to walk. Not expected from
            // SpineGrowth, but staying safe costs nothing.
            return;
        }

        boolean dominantX = Math.abs(dx) >= Math.abs(dz);
        double dominantRatio = (dominantX ? Math.abs(dx) : Math.abs(dz)) / len;    // in [1/sqrt2, 1]
        double nonDominantRatio = (dominantX ? Math.abs(dz) : Math.abs(dx)) / len; // in [0, 1/sqrt2]

        double setback = p.roadsideSetbackBlocks();
        double depth = p.roadsideLotDepthBlocks();
        double halfWidth = lotWidth / 2.0;
        // See the method doc: the halfWidth * nonDominantRatio term covers the road's drift across
        // the lot's whole along-edge extent, not just its centre reference point.
        double perpOffset = (setback + halfWidth * nonDominantRatio) / dominantRatio;
        double perpFar = (setback + depth + halfWidth * nonDominantRatio) / dominantRatio;

        for (double alongDist = lotWidth / 2.0; alongDist < len; alongDist += lotWidth) {
            double alongX = a.x() + (dx / len) * alongDist;
            double alongZ = a.z() + (dz / len) * alongDist;

            for (int side = -1; side <= 1; side += 2) {
                Rect footprint = axisAlignedFootprint(alongX, alongZ, halfWidth, side * perpOffset,
                        side * perpFar, dominantX);

                // Step 3 of the brief: reject, never shrink, so "lots never overlap" and "every lot
                // is dry" stay properties of the construction rather than something checked after.
                if (!liesWithinSettlement(footprint, centre, radius)) {
                    continue;
                }
                if (!FootprintDryness.isFullyDry(footprint, t)) {
                    continue;
                }
                if (overlapsAny(footprint, lots)) {
                    continue;
                }
                // The authoritative check: the real distance from the road segment to the finished
                // rectangle's nearest point, not the intermediate offset used to build it.
                if (!realSetbackClears(a, b, footprint, setback)) {
                    continue;
                }

                // Step 5 of the brief: the exact water-side scan LotSubdivider uses, shared rather
                // than copied - see WaterFrontage's doc for what the copy here used to get wrong.
                int waterSides = WaterFrontage.sidesOf(footprint, t, p.probeDistanceBlocks());
                Vec2 lotCentre = footprint.center();
                int groundHeight = t.heightAt(lotCentre.x(), lotCentre.z());
                // Step 1: OUTER unless the lot is actually near water, in which case WATERFRONT. Lot
                // width no longer comes from District at all (see lotWidthFor), so this only ever sets
                // the tag. "Near water" uses DistrictMap's own threshold and probe (any corner within
                // WATERFRONT_RADIUS_BLOCKS), not the narrower probeDistanceBlocks waterSides uses, so a
                // spine settlement and a block-subdivided one agree on what the WATERFRONT tag means -
                // see DistrictMap.WATERFRONT_RADIUS_BLOCKS's doc.
                District district = isNearWater(footprint, t) ? District.WATERFRONT : District.OUTER;

                lots.add(new Lot(0, footprint, district, SIZE_CLASS, edgeIndex, groundHeight, waterSides));
            }
        }
    }

    /**
     * Builds the axis-aligned candidate directly: {@code lotWidth} wide along whichever axis is
     * dominant, from {@code nearPerp} to {@code farPerp} (in either order) along the other.
     */
    private static Rect axisAlignedFootprint(double alongX, double alongZ, double halfWidth,
                                              double nearPerp, double farPerp, boolean dominantX) {
        double loPerp = Math.min(nearPerp, farPerp);
        double hiPerp = Math.max(nearPerp, farPerp);

        if (dominantX) {
            return new Rect(
                    (int) Math.round(alongX - halfWidth), (int) Math.round(alongZ + loPerp),
                    (int) Math.round(alongX + halfWidth), (int) Math.round(alongZ + hiPerp));
        }
        return new Rect(
                (int) Math.round(alongX + loPerp), (int) Math.round(alongZ - halfWidth),
                (int) Math.round(alongX + hiPerp), (int) Math.round(alongZ + halfWidth));
    }

    private static boolean liesWithinSettlement(Rect footprint, Vec2 centre, int radius) {
        return footprint.minX() >= centre.x() - radius && footprint.maxX() <= centre.x() + radius
                && footprint.minZ() >= centre.z() - radius && footprint.maxZ() <= centre.z() + radius;
    }

    private static boolean overlapsAny(Rect footprint, List<Lot> lots) {
        for (Lot existing : lots) {
            if (footprint.intersects(existing.footprint())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code footprint}'s nearest point to segment {@code a-b} is at least {@code setback}
     * away - the real check the brief's step 2 asks for, independent of whatever offset the
     * construction above used to get there. False (rejected) if the segment touches or crosses the
     * footprint at all. Otherwise, since both a segment and an axis-aligned rectangle are convex, the
     * true minimum separation is always achieved at one of six candidates: each segment endpoint's
     * distance to the rectangle, or each rectangle corner's distance to the segment.
     */
    private static boolean realSetbackClears(Vec2 a, Vec2 b, Rect footprint, double setback) {
        if (segmentIntersectsRect(a, b, footprint)) {
            return false;
        }
        double best = Math.min(distanceToRect(a, footprint), distanceToRect(b, footprint));
        Vec2[] corners = {
                new Vec2(footprint.minX(), footprint.minZ()), new Vec2(footprint.maxX(), footprint.minZ()),
                new Vec2(footprint.maxX(), footprint.maxZ()), new Vec2(footprint.minX(), footprint.maxZ())
        };
        for (Vec2 c : corners) {
            best = Math.min(best, distanceToSegment(c, a, b));
        }
        return best >= setback;
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

    /**
     * Whether any corner of {@code footprint} has open water within
     * {@link DistrictMap#WATERFRONT_RADIUS_BLOCKS}, using {@link DistrictMap#waterWithin} directly
     * rather than a second copy of the same disc scan at a second magic-number radius - see the class
     * doc on why the two settlement shapes have to agree on this distance.
     */
    private static boolean isNearWater(Rect footprint, TerrainSampler t) {
        Vec2[] corners = {
                new Vec2(footprint.minX(), footprint.minZ()), new Vec2(footprint.maxX(), footprint.minZ()),
                new Vec2(footprint.maxX(), footprint.maxZ()), new Vec2(footprint.minX(), footprint.maxZ())
        };
        for (Vec2 c : corners) {
            if (DistrictMap.waterWithin(c, t, DistrictMap.WATERFRONT_RADIUS_BLOCKS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hands out final, sequential ids in construction order. Unlike {@code Planner.finalizeLots},
     * there is no area-tertile ranking to duplicate here any more - see {@link #SIZE_CLASS}'s doc for
     * why a roadside lot's {@code sizeClass} is a constant rather than a computed rank.
     */
    private static List<Lot> assignIds(List<Lot> interim) {
        List<Lot> result = new ArrayList<>(interim.size());
        for (int i = 0; i < interim.size(); i++) {
            Lot l = interim.get(i);
            result.add(new Lot(i, l.footprint(), l.district(), l.sizeClass(),
                    l.frontingEdgeIndex(), l.groundHeight(), l.waterSides()));
        }
        return result;
    }
}

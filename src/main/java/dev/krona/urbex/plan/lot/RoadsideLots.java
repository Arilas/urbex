package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;
import dev.krona.urbex.plan.road.RoadEdge;
import dev.krona.urbex.plan.road.RoadGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Derives lots for a spine settlement straight from road frontage instead of subdividing an enclosed
 * block: {@link dev.krona.urbex.plan.road.SpineGrowth} grows a tree, which encloses no faces, so
 * {@code BlockExtractor}/{@code LotSubdivider} have nothing to work with.
 * <p>
 * For each edge, this walks its length in steps of the lot width and offers a candidate lot on each
 * side. A candidate is dropped outright - never shrunk - if it leaves the settlement bounds, is not
 * fully dry, or overlaps a lot already kept; see the brief's rationale for why rejecting keeps the
 * "lots never overlap" invariant structural rather than something to patch up afterwards. Because a
 * lot is placed directly against the edge it was offered on, {@code frontingEdgeIndex} is known by
 * construction - the one thing this path doesn't have to search for, unlike
 * {@link LotSubdivider}, which has to find the nearest edge to every leaf after the fact.
 */
public final class RoadsideLots {

    /** Same three offsets {@link LotSubdivider} uses for both its dryness and water-side probes. */
    private static final double[] SAMPLE_FRACTIONS = {0.2, 0.5, 0.8};

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
        double lotWidth = outerLotSize(p);

        List<Lot> interim = new ArrayList<>();
        List<RoadEdge> edges = g.edges();
        for (int i = 0; i < edges.size(); i++) {
            RoadEdge edge = edges.get(i);
            Vec2 a = g.nodeAt(edge.fromId()).pos();
            Vec2 b = g.nodeAt(edge.toId()).pos();
            placeAlongEdge(i, a, b, lotWidth, centre, radius, t, p, interim);
        }
        return finalizeLots(interim);
    }

    /**
     * Step 1 of the brief: a spine settlement is {@code OUTER} throughout unless a lot turns out to
     * front water, so every lot uses {@code OUTER}'s target size regardless of which district it
     * ends up tagged with - {@code LotSubdivider.targetLotSize} maps {@code WATERFRONT} to the same
     * rank as {@code OUTER} for exactly this reason.
     */
    private static double outerLotSize(PlanParams p) {
        double core = p.coreLotSizeBlocks();
        double fringe = p.fringeLotSizeBlocks();
        double t = 2.0 / 3.0; // OUTER/WATERFRONT is rank 2 of {CORE, INNER, OUTER, FRINGE}.
        return core + (fringe - core) * t;
    }

    /**
     * Walks edge {@code a-b} in strides of {@code lotWidth}, offering one candidate lot per side at
     * each stride. The candidate rectangle is built in the edge's own (tangent, normal) frame - its
     * near side {@code roadsideSetbackBlocks} off the centreline, its far side
     * {@code roadsideLotDepthBlocks} beyond that, {@code lotWidth} wide along the edge - then
     * converted to an axis-aligned {@link Rect} by taking the bounding box of its four corners, since
     * {@link Rect} cannot represent a rotated rectangle and a spine edge is rarely axis-aligned.
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
        double tx = dx / len;
        double tz = dz / len;
        // The left-hand normal; the loop below tries both this and its negation.
        double nx = -tz;
        double nz = tx;

        double halfWidth = lotWidth / 2.0;
        double setback = p.roadsideSetbackBlocks();
        double depth = p.roadsideLotDepthBlocks();

        for (double alongDist = lotWidth / 2.0; alongDist < len; alongDist += lotWidth) {
            double px = a.x() + tx * alongDist;
            double pz = a.z() + tz * alongDist;

            for (int side = -1; side <= 1; side += 2) {
                double ux = nx * side;
                double uz = nz * side;

                Rect footprint = boundingBoxOf(
                        corner(px, pz, tx, tz, ux, uz, -halfWidth, setback),
                        corner(px, pz, tx, tz, ux, uz, halfWidth, setback),
                        corner(px, pz, tx, tz, ux, uz, halfWidth, setback + depth),
                        corner(px, pz, tx, tz, ux, uz, -halfWidth, setback + depth));

                // Step 3 of the brief: reject, never shrink, so "lots never overlap" and "every lot
                // is dry" stay properties of the construction rather than something checked after.
                if (!liesWithinSettlement(footprint, centre, radius)) {
                    continue;
                }
                if (!isFullyDry(footprint, t)) {
                    continue;
                }
                if (overlapsAny(footprint, lots)) {
                    continue;
                }

                int waterSides = computeWaterSides(footprint, t, p.probeDistanceBlocks());
                Vec2 lotCentre = footprint.center();
                int groundHeight = t.heightAt(lotCentre.x(), lotCentre.z());
                // Step 1: OUTER unless the lot actually fronts water, in which case WATERFRONT -
                // both map to the same target size, so this only changes the district tag, not
                // the width already chosen above.
                District district = waterSides != 0 ? District.WATERFRONT : District.OUTER;

                // Placeholder id and sizeClass: finalizeLots below fills both in once every
                // candidate for the whole settlement has been collected, exactly as Planner does
                // for the block-subdivision path.
                lots.add(new Lot(0, footprint, district, 0, edgeIndex, groundHeight, waterSides));
            }
        }
    }

    private static Vec2 corner(double px, double pz, double tx, double tz, double ux, double uz,
                                double alongOffset, double acrossOffset) {
        double x = px + tx * alongOffset + ux * acrossOffset;
        double z = pz + tz * alongOffset + uz * acrossOffset;
        return new Vec2((int) Math.round(x), (int) Math.round(z));
    }

    private static Rect boundingBoxOf(Vec2 c0, Vec2 c1, Vec2 c2, Vec2 c3) {
        int minX = Math.min(Math.min(c0.x(), c1.x()), Math.min(c2.x(), c3.x()));
        int maxX = Math.max(Math.max(c0.x(), c1.x()), Math.max(c2.x(), c3.x()));
        int minZ = Math.min(Math.min(c0.z(), c1.z()), Math.min(c2.z(), c3.z()));
        int maxZ = Math.max(Math.max(c0.z(), c1.z()), Math.max(c2.z(), c3.z()));
        return new Rect(minX, minZ, maxX, maxZ);
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

    /** The same 3x3-grid dryness probe {@code LotSubdivider.isFullyDry} uses. */
    private static boolean isFullyDry(Rect r, TerrainSampler t) {
        for (double fx : SAMPLE_FRACTIONS) {
            int x = (int) Math.round(r.minX() + fx * (r.maxX() - r.minX()));
            for (double fz : SAMPLE_FRACTIONS) {
                int z = (int) Math.round(r.minZ() + fz * (r.maxZ() - r.minZ()));
                if (t.isWaterAt(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Step 5 of the brief: the exact water-side probe {@code LotSubdivider.computeWaterSides} uses. */
    private static int computeWaterSides(Rect footprint, TerrainSampler t, int probeDistance) {
        int mask = 0;
        if (sideTouchesWater(footprint, t, probeDistance, 0, -1)) {
            mask |= WaterShape.NORTH;
        }
        if (sideTouchesWater(footprint, t, probeDistance, 1, 0)) {
            mask |= WaterShape.EAST;
        }
        if (sideTouchesWater(footprint, t, probeDistance, 0, 1)) {
            mask |= WaterShape.SOUTH;
        }
        if (sideTouchesWater(footprint, t, probeDistance, -1, 0)) {
            mask |= WaterShape.WEST;
        }
        return mask;
    }

    private static boolean sideTouchesWater(Rect footprint, TerrainSampler t, int probeDistance, int dx, int dz) {
        if (dz != 0) {
            int z = dz < 0 ? footprint.minZ() - probeDistance : footprint.maxZ() + probeDistance;
            for (double frac : SAMPLE_FRACTIONS) {
                int x = (int) Math.round(footprint.minX() + frac * (footprint.maxX() - footprint.minX()));
                if (t.isWaterAt(x, z)) {
                    return true;
                }
            }
        } else {
            int x = dx < 0 ? footprint.minX() - probeDistance : footprint.maxX() + probeDistance;
            for (double frac : SAMPLE_FRACTIONS) {
                int z = (int) Math.round(footprint.minZ() + frac * (footprint.maxZ() - footprint.minZ()));
                if (t.isWaterAt(x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Same ranking {@code Planner.finalizeLots} performs for the block-subdivision path: rank every
     * candidate by footprint area (ties broken by construction order, itself deterministic) into
     * tertiles for {@code sizeClass}, then hand out final sequential ids in that same construction
     * order. Duplicated rather than shared because {@code Planner}'s version is private and this
     * class produces {@link Lot}s directly rather than handing interim ones back to a caller that
     * will finish them.
     */
    private static List<Lot> finalizeLots(List<Lot> interim) {
        int n = interim.size();
        if (n == 0) {
            return List.of();
        }

        Integer[] byArea = new Integer[n];
        for (int i = 0; i < n; i++) {
            byArea[i] = i;
        }
        Arrays.sort(byArea, (a, b) -> {
            int cmp = Integer.compare(interim.get(a).footprint().area(), interim.get(b).footprint().area());
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });

        int[] sizeClassOf = new int[n];
        for (int rank = 0; rank < n; rank++) {
            sizeClassOf[byArea[rank]] = Math.min(2, rank * 3 / n);
        }

        List<Lot> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Lot l = interim.get(i);
            result.add(new Lot(i, l.footprint(), l.district(), sizeClassOf[i],
                    l.frontingEdgeIndex(), l.groundHeight(), l.waterSides()));
        }
        return result;
    }
}

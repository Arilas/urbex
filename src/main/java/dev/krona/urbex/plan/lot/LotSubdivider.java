package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.Hash;
import dev.krona.urbex.plan.PlanParams;
import dev.krona.urbex.plan.PlanPurpose;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.block.CityBlock;
import dev.krona.urbex.plan.district.District;
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
 * rect being cut — so the split of one sub-rect can never perturb its sibling's. A leaf survives
 * only if its centre lands inside the block's outline, on dry ground, and within
 * {@code maxLotDepthBlocks} of a road; the id assigned here is a placeholder, and {@code sizeClass}
 * is filled in with 0 — both are only meaningful once {@code Planner} has seen every lot in the
 * settlement and can rank them.
 * <p>
 * This checks only the leaf's centre against the outline, exactly as specified — not every corner.
 * A block is not rectangular (spokes and rings meet at arbitrary bearings), so a wedge-shaped
 * block's axis-aligned bounding box can overlap a neighbour's across their shared boundary, and a
 * centre-only check lets a leaf's far corners land in that neighbour's territory. Rejecting on
 * every corner instead was tried and measured worse: it starves thin wedge blocks of lots entirely,
 * because a target-size rect rarely fits inside a triangle's bounding box with room to spare on
 * every side. {@link dev.krona.urbex.plan.Planner} resolves the resulting cross-block overlaps once
 * it can see every block's lots side by side, which is the only place that information exists — see
 * its {@code deduplicateOverlaps}.
 */
public final class LotSubdivider {

    /** How far the split position may drift from the midpoint, as a fraction of the side being cut. */
    private static final double SPLIT_JITTER_FRACTION = 0.2;

    /** Three points per side, offset in from the corners so no sample lands exactly on another side's probe. */
    private static final double[] SIDE_SAMPLE_FRACTIONS = {0.2, 0.5, 0.8};

    /** A leaf narrower than this on either axis cannot survive the 1-block shrink on every side. */
    private static final int MIN_LEAF_SIDE_BLOCKS = 3;

    private LotSubdivider() {
    }

    public static List<Lot> subdivide(long seed, CityBlock b, District d, RoadGraph g, TerrainSampler t,
                                       PlanParams p) {
        double targetSize = targetLotSize(d, p);

        List<Rect> leaves = new ArrayList<>();
        splitRect(b.boundingBox(), targetSize, seed, leaves);

        List<Lot> lots = new ArrayList<>();
        for (Rect leaf : leaves) {
            if (leaf.width() < MIN_LEAF_SIDE_BLOCKS || leaf.depth() < MIN_LEAF_SIDE_BLOCKS) {
                continue;
            }

            // Step 3: discard a leaf whose centre falls outside the block or over water. The shrink
            // (below) never moves the centre — minX+1 and maxX-1 shift the sum they're averaged
            // from by zero — so checking it before or after shrinking gives the same answer.
            Vec2 centre = leaf.center();
            if (!b.outline().contains(centre)) {
                continue;
            }
            if (t.isWaterAt(centre.x(), centre.z())) {
                continue;
            }

            // Step 4: discard a leaf with no road within maxLotDepthBlocks; otherwise remember which
            // edge it fronts.
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

            // Shrink by 1 block on every side so adjacent lots never share a boundary block.
            Rect footprint = new Rect(leaf.minX() + 1, leaf.minZ() + 1, leaf.maxX() - 1, leaf.maxZ() - 1);
            int waterSides = computeWaterSides(footprint, t, p.probeDistanceBlocks());
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
     * Recursively halves {@code r} while its longer side exceeds twice {@code targetSize}, cutting
     * perpendicular to that side at a jittered position addressed by the rect's own origin.
     */
    private static void splitRect(Rect r, double targetSize, long seed, List<Rect> out) {
        int width = r.width();
        int depth = r.depth();
        int longer = Math.max(width, depth);
        if (longer <= 2.0 * targetSize) {
            out.add(r);
            return;
        }

        long h = Hash.at(seed, r.minX(), r.minZ(), PlanPurpose.BLOCK_SPLIT_POS.key());
        double jitter = (Hash.unit(h) * 2.0 - 1.0) * SPLIT_JITTER_FRACTION;

        if (width >= depth) {
            double mid = (r.minX() + r.maxX()) / 2.0;
            int splitAt = clamp((int) Math.round(mid + jitter * width), r.minX() + 1, r.maxX() - 1);
            splitRect(new Rect(r.minX(), r.minZ(), splitAt - 1, r.maxZ()), targetSize, seed, out);
            splitRect(new Rect(splitAt, r.minZ(), r.maxX(), r.maxZ()), targetSize, seed, out);
        } else {
            double mid = (r.minZ() + r.maxZ()) / 2.0;
            int splitAt = clamp((int) Math.round(mid + jitter * depth), r.minZ() + 1, r.maxZ() - 1);
            splitRect(new Rect(r.minX(), r.minZ(), r.maxX(), splitAt - 1), targetSize, seed, out);
            splitRect(new Rect(r.minX(), splitAt, r.maxX(), r.maxZ()), targetSize, seed, out);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
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

    /**
     * Step 4b of the brief: probes three points along each of the footprint's four sides,
     * {@code probeDistance} blocks beyond that side, and sets the side's bit if any sample is water.
     * Three samples rather than one so a river meeting the lot at an angle, touching only part of a
     * side, is still caught.
     */
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

    /** {@code (dx, dz)} points outward from the side being probed: north is -z, east is +x, etc. */
    private static boolean sideTouchesWater(Rect footprint, TerrainSampler t, int probeDistance, int dx, int dz) {
        if (dz != 0) {
            int z = dz < 0 ? footprint.minZ() - probeDistance : footprint.maxZ() + probeDistance;
            for (double frac : SIDE_SAMPLE_FRACTIONS) {
                int x = (int) Math.round(footprint.minX() + frac * (footprint.maxX() - footprint.minX()));
                if (t.isWaterAt(x, z)) {
                    return true;
                }
            }
        } else {
            int x = dx < 0 ? footprint.minX() - probeDistance : footprint.maxX() + probeDistance;
            for (double frac : SIDE_SAMPLE_FRACTIONS) {
                int z = (int) Math.round(footprint.minZ() + frac * (footprint.maxZ() - footprint.minZ()));
                if (t.isWaterAt(x, z)) {
                    return true;
                }
            }
        }
        return false;
    }
}

package dev.krona.urbex.plan.district;

import dev.krona.urbex.plan.Settlement;
import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.block.CityBlock;
import dev.krona.urbex.plan.geom.Vec2;

/**
 * Assigns each block a {@link District}: a water check first, then a concentric band by distance
 * from the settlement centre.
 * <p>
 * The water check comes first so a coastal core is reported as waterfront rather than core — the
 * two are not layered, {@code WATERFRONT} simply pre-empts the ring the block would otherwise fall
 * into.
 */
public final class DistrictMap {

    /** How close, in blocks, any outline vertex must come to open water to count as waterfront. */
    private static final int WATERFRONT_RADIUS_BLOCKS = 24;

    private static final double CORE_FRACTION = 0.2;
    private static final double INNER_FRACTION = 0.45;
    private static final double OUTER_FRACTION = 0.75;

    private DistrictMap() {
    }

    public static District assign(CityBlock b, Settlement s, TerrainSampler t) {
        if (isNearWater(b, t)) {
            return District.WATERFRONT;
        }

        Vec2 centre = b.boundingBox().center();
        double distance = Math.sqrt((double) centre.distanceSquaredTo(s.centerBlock()));
        double fraction = distance / s.radiusBlocks();

        if (fraction < CORE_FRACTION) {
            return District.CORE;
        }
        if (fraction < INNER_FRACTION) {
            return District.INNER;
        }
        if (fraction < OUTER_FRACTION) {
            return District.OUTER;
        }
        return District.FRINGE;
    }

    /** True if any of the block's outline vertices has open water within {@link #WATERFRONT_RADIUS_BLOCKS}. */
    private static boolean isNearWater(CityBlock b, TerrainSampler t) {
        for (Vec2 vertex : b.outline().ring()) {
            if (waterWithin(vertex, t, WATERFRONT_RADIUS_BLOCKS)) {
                return true;
            }
        }
        return false;
    }

    /** Exhaustive disc scan, exact to the block: a settlement-scale radius of 24 is cheap to search fully. */
    private static boolean waterWithin(Vec2 centre, TerrainSampler t, int radius) {
        long radiusSq = (long) radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            long remainder = radiusSq - (long) dz * dz;
            if (remainder < 0) {
                continue;
            }
            int maxDx = (int) Math.sqrt((double) remainder);
            for (int dx = -maxDx; dx <= maxDx; dx++) {
                if (t.isWaterAt(centre.x() + dx, centre.z() + dz)) {
                    return true;
                }
            }
        }
        return false;
    }
}

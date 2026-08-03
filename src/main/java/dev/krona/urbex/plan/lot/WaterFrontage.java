package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Rect;

/**
 * One shared definition of "which of this footprint's sides face water" for {@link LotSubdivider}
 * and {@link RoadsideLots}, scanning each side's full length rather than three points on it.
 * <p>
 * Both pipelines used to carry their own copy of a three-sample probe (20/50/80% along each side),
 * the same shape of sparse check that {@link FootprintDryness} replaced for dryness. It survived
 * review the first time round because, unlike the dryness probe, no measurement showed it doing
 * harm - and it could not have, because every water terrain in the suite ({@code RiverTerrain},
 * {@code CoastTerrain}) puts its bank on an axis, and a bank parallel to the side being probed
 * either covers that side's whole probe line or none of it. Measured since, on terrain whose
 * boundary is not axis-aligned ({@code MeanderTerrain}, an angled coast and value-noise lakes,
 * across HAMLET/VILLAGE/TOWN/CITY and 60 runs each): the three-point probe got the mask wrong on
 * 38.8% of water-adjacent lots (324 of 835) and reported no frontage at all on 16.9% of them, while
 * on the suite's straight terrains it disagreed with this scan on 0 of 524. The bit it misses is
 * always one it should have set - its samples are a subset of this scan's - and the mistake reaches
 * P3, which picks a building's frontage piece from {@link Lot#waterShape()}: the common failure was
 * STRAIGHT where the truth is CORNER (180 lots), then INLAND where the truth is STRAIGHT (114).
 * <p>
 * A side is tens of blocks long, so scanning all of it costs a fraction of the per-block footprint
 * scan {@link FootprintDryness} already runs on the same candidate.
 * <p>
 * <b>What this deliberately does not change.</b> A side is probed at exactly
 * {@code probeDistance} blocks out, as before - not swept across every depth from 1 to
 * {@code probeDistance}. Sweeping is the more natural reading of "faces water within reach" and was
 * measured alongside this scan, but it changed the answer for 2 of those 835 lots (0.2%), which does
 * not earn redefining what {@code probeDistanceBlocks} means in the same change that fixes a
 * measured 38.8%.
 */
final class WaterFrontage {

    private WaterFrontage() {
    }

    /**
     * The {@link WaterShape} bit mask for {@code footprint}: one bit per side whose probe line, laid
     * {@code probeDistance} blocks beyond that side, crosses any water.
     */
    static int sidesOf(Rect footprint, TerrainSampler t, int probeDistance) {
        int mask = 0;
        if (sideFacesWater(footprint, t, probeDistance, 0, -1)) {
            mask |= WaterShape.NORTH;
        }
        if (sideFacesWater(footprint, t, probeDistance, 1, 0)) {
            mask |= WaterShape.EAST;
        }
        if (sideFacesWater(footprint, t, probeDistance, 0, 1)) {
            mask |= WaterShape.SOUTH;
        }
        if (sideFacesWater(footprint, t, probeDistance, -1, 0)) {
            mask |= WaterShape.WEST;
        }
        return mask;
    }

    /** {@code (dx, dz)} points outward from the side being probed: north is -z, east is +x, etc. */
    private static boolean sideFacesWater(Rect footprint, TerrainSampler t, int probeDistance,
                                          int dx, int dz) {
        if (dz != 0) {
            int z = dz < 0 ? footprint.minZ() - probeDistance : footprint.maxZ() + probeDistance;
            for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                if (t.isWaterAt(x, z)) {
                    return true;
                }
            }
            return false;
        }
        int x = dx < 0 ? footprint.minX() - probeDistance : footprint.maxX() + probeDistance;
        for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
            if (t.isWaterAt(x, z)) {
                return true;
            }
        }
        return false;
    }
}

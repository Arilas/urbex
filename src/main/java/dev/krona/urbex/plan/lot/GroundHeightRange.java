package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Rect;

/**
 * The lowest and highest ground height anywhere under a footprint, shared between {@link LotSubdivider}
 * and {@link RoadsideLots}.
 * <p>
 * {@link Lot#minGroundHeight()}/{@link Lot#maxGroundHeight()} exist because a single {@code
 * heightAt(footprint.center())} sample - what both producers used to take - cannot describe a range,
 * and {@link Lot}'s own doc says the field "is what lets P4 hand vanilla a {@code TerrainAdjustment}
 * box": a box needs a height range, not a point. Measured on {@code HillTerrain(64, 24, 96)} across
 * 664 lots: mean height spread within a single footprint was 7.79 blocks, max 10, and every one of
 * them spanned more than 4 - a single centre sample was never going to be enough for P4 to know how
 * much fill or excavation a lot's own footprint actually needs.
 */
final class GroundHeightRange {

    private GroundHeightRange() {
    }

    record Range(int min, int max) {
    }

    /** Scans every block of {@code r}, not a sample of it - the footprint is small enough that this is cheap. */
    static Range of(Rect r, TerrainSampler t) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = r.minX(); x <= r.maxX(); x++) {
            for (int z = r.minZ(); z <= r.maxZ(); z++) {
                int h = t.heightAt(x, z);
                if (h < min) {
                    min = h;
                }
                if (h > max) {
                    max = h;
                }
            }
        }
        return new Range(min, max);
    }
}

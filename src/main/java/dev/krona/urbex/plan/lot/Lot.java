package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.geom.Rect;

/**
 * One buildable plot.
 * <p>
 * This record is the contract P3's asset model must satisfy. Three fields do specific future work:
 * {@code frontingEdgeIndex} is what lets a building be oriented so its entrance faces its road, and
 * {@code minGroundHeight}/{@code maxGroundHeight} are what let P4 hand vanilla a
 * {@code TerrainAdjustment} box - a box needs a range, which is why this is two fields measured over
 * the whole footprint ({@link GroundHeightRange}) rather than one sample at its centre; see that
 * class's doc for how much a single point sample used to miss.
 */
public record Lot(
        int id,
        Rect footprint,
        District district,
        int sizeClass,
        int frontingEdgeIndex,
        int minGroundHeight,
        int maxGroundHeight,
        int waterSides
) {

    /** North, east, south, west as bits 0-3 of {@link #waterSides}. */
    public WaterShape waterShape() {
        return WaterShape.of(waterSides);
    }
}

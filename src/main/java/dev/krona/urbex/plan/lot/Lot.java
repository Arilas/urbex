package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.district.District;
import dev.krona.urbex.plan.geom.Rect;

/**
 * One buildable plot.
 * <p>
 * This record is the contract P3's asset model must satisfy. Two fields do specific future work:
 * {@code frontingEdgeIndex} is what lets a building be oriented so its entrance faces its road,
 * and {@code groundHeight} is what lets P4 hand vanilla a {@code TerrainAdjustment} box.
 */
public record Lot(
        int id,
        Rect footprint,
        District district,
        int sizeClass,
        int frontingEdgeIndex,
        int groundHeight,
        int waterSides
) {

    /** North, east, south, west as bits 0-3 of {@link #waterSides}. */
    public WaterShape waterShape() {
        return WaterShape.of(waterSides);
    }
}

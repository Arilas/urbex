package dev.krona.urbex.plan.block;

import dev.krona.urbex.plan.geom.Polygon;
import dev.krona.urbex.plan.geom.Rect;

/**
 * One enclosed face of the road network: the land bounded by roads on every side, before it is cut
 * into lots.
 * <p>
 * {@code outline} is always wound counter-clockwise and always encloses a positive area — the outer
 * face and every degenerate walk are dropped by {@link BlockExtractor} before a {@code CityBlock}
 * exists. The ring may still contain consecutive collinear points, because a road junction that
 * happens to sit mid-edge is a real junction and later phases want to know where it is.
 * <p>
 * {@code id} is an index into the extracted list, assigned after the faces have been sorted
 * geometrically, so it is stable for a given graph and carries no information about traversal order.
 */
public record CityBlock(int id, Polygon outline) {

    public Rect boundingBox() {
        return outline.boundingBox();
    }

    /** Twice the enclosed area, unsigned. Compare against {@code minBlockAreaBlocks * 2}. */
    public long areaDoubled() {
        return Math.abs(outline.signedDoubleArea());
    }
}

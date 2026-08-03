package dev.krona.urbex.plan.lot;

import dev.krona.urbex.plan.TerrainSampler;
import dev.krona.urbex.plan.geom.Rect;

/**
 * One shared, exhaustive definition of "this footprint is dry" for {@link LotSubdivider} and
 * {@link RoadsideLots} - review found both pipelines had grown their own sparse probe (a 3x3 grid at
 * 20/50/80% fractions) for the same question, and both had the same class of blind spot: a thin wet
 * strip - a riverbank hugging one edge of a footprint - can sit entirely between sample points and
 * never be seen. Measured before this class existed: up to 64.4% of {@code RoadsideLots}' HAMLET
 * lots and 3.56% of {@code LotSubdivider}'s TOWN lots contained a wet block despite passing their own
 * probe.
 * <p>
 * A lot's footprint is small enough - tens to low hundreds of blocks - that scanning every one of
 * them, rather than sampling a handful, costs nothing worth trading correctness for. This is called
 * only for candidates that have already survived cheaper checks (bounds, overlap, or - in
 * {@code LotSubdivider}'s case - the block outline), so the exhaustive scan never runs more than it
 * has to.
 */
final class FootprintDryness {

    private FootprintDryness() {
    }

    /** Whether every block of {@code r} is dry. */
    static boolean isFullyDry(Rect r, TerrainSampler t) {
        for (int x = r.minX(); x <= r.maxX(); x++) {
            for (int z = r.minZ(); z <= r.maxZ(); z++) {
                if (t.isWaterAt(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }
}

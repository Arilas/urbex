package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/**
 * A river meandering along z: its centreline is a sum of two sines, so its banks meet a lot's side
 * at a continuously varying angle instead of running parallel to it.
 * <p>
 * {@link RiverTerrain} and {@link CoastTerrain} both put their water boundary on an axis, which is
 * the one case where sampling a footprint's edge sparsely and sampling it exhaustively can hardly
 * ever disagree: a bank parallel to the side it is being probed from either covers the whole probe
 * line or none of it. Review of {@code LotSubdivider}'s water-side probe found exactly that blind
 * spot in the suite - a sparse probe passed every straight-terrain test while getting 38.8% of
 * water-adjacent lots wrong on terrain like this one. Anything measuring how a check behaves at a
 * water boundary needs a boundary that is not axis-aligned; that is what this is for.
 */
public record MeanderTerrain(int groundHeight, int centreX, double amplitude, double wavelength,
                             int width) implements TerrainSampler {

    @Override
    public int heightAt(int x, int z) {
        return isWaterAt(x, z) ? groundHeight - 4 : groundHeight;
    }

    @Override
    public boolean isWaterAt(int x, int z) {
        // The second, shorter sine is what stops the banks being locally straight for long stretches:
        // one sine alone turns slowly enough that a lot-sized span of it is nearly a straight line.
        double centre = centreX
                + amplitude * Math.sin(z / wavelength)
                + amplitude * 0.4 * Math.sin(z / (wavelength * 0.37) + 1.7);
        return Math.abs(x - centre) * 2 < width;
    }
}

package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/** A straight river of {@code width} blocks running along the z axis at x = {@code atX}. */
public record RiverTerrain(int groundHeight, int atX, int width) implements TerrainSampler {
    @Override public int heightAt(int x, int z) {
        return isWaterAt(x, z) ? groundHeight - 4 : groundHeight;
    }
    @Override public boolean isWaterAt(int x, int z) {
        return Math.abs(x - atX) * 2 < width;
    }
    /** True when the segment from a to b crosses the river channel. */
    public boolean crosses(int x1, int x2) {
        return (x1 < atX) != (x2 < atX);
    }
}

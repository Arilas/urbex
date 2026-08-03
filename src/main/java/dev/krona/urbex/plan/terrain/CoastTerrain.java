package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/** Land where x is below {@code shoreX}, open water beyond it. */
public record CoastTerrain(int groundHeight, int shoreX) implements TerrainSampler {
    @Override public int heightAt(int x, int z) {
        return x < shoreX ? groundHeight : groundHeight - 8;
    }
    @Override public boolean isWaterAt(int x, int z) { return x >= shoreX; }
}

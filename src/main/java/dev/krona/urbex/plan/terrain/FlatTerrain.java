package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/** Featureless ground at a constant height. The control case. */
public record FlatTerrain(int height) implements TerrainSampler {
    @Override public int heightAt(int x, int z) { return height; }
    @Override public boolean isWaterAt(int x, int z) { return false; }
}

package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/** A sheer step of {@code rise} blocks at x = {@code atX}. Nothing may road up it. */
public record CliffTerrain(int lowHeight, int rise, int atX) implements TerrainSampler {
    @Override public int heightAt(int x, int z) {
        return x < atX ? lowHeight : lowHeight + rise;
    }
    @Override public boolean isWaterAt(int x, int z) { return false; }
}

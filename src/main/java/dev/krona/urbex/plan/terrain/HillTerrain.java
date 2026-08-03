package dev.krona.urbex.plan.terrain;

import dev.krona.urbex.plan.TerrainSampler;

/** A single smooth hill centred at the origin, falling off linearly to {@code base}. */
public record HillTerrain(int base, int peak, int radius) implements TerrainSampler {
    @Override public int heightAt(int x, int z) {
        double d = Math.sqrt((double) x * x + (double) z * z);
        if (d >= radius) {
            return base;
        }
        return base + (int) ((peak - base) * (1.0 - d / radius));
    }
    @Override public boolean isWaterAt(int x, int z) { return false; }
}

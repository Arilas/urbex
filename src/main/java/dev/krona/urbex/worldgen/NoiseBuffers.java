package dev.krona.urbex.worldgen;

/**
 * The scratch buffers a single chunk's generation needs. Owned by {@link ChunkGenContext} rather
 * than by the shared feature, so two threads generating different chunks cannot overwrite each
 * other's noise.
 */
public final class NoiseBuffers {
    public double[] rubble = new double[256];
    public double[] leaves = new double[256];
    public double[] ruin = new double[256];
    public double[] bottomLayer = new double[256];
}

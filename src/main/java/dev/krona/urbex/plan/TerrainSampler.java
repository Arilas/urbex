package dev.krona.urbex.plan;

/**
 * The only thing the planner may ask about the world.
 * <p>
 * Deliberately two methods. A real implementation must answer from the terrain <em>function</em> —
 * the noise or heightmap query, a pure function of the world seed — and never from placed blocks.
 * Reading placed blocks is the mechanism behind issue #18, where vanilla vegetation bleeding across
 * a chunk border changed what a fill loop saw; the same mistake at the planning layer would make
 * whole road networks depend on chunk generation order.
 */
public interface TerrainSampler {

    int heightAt(int x, int z);

    boolean isWaterAt(int x, int z);
}

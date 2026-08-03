package dev.krona.urbex.plan;

import dev.krona.urbex.plan.geom.Rect;
import dev.krona.urbex.plan.geom.Vec2;

/** One placed settlement. Its bounds are a square of {@code extentChunks} centred on its centre chunk. */
public record Settlement(SettlementClass cls, int centerChunkX, int centerChunkZ) {

    public int extentChunks() {
        return cls.extentChunks();
    }

    public Rect boundsChunks() {
        int half = cls.extentChunks() / 2;
        return new Rect(centerChunkX - half, centerChunkZ - half,
                centerChunkX + half, centerChunkZ + half);
    }

    public Vec2 centerBlock() {
        return new Vec2(centerChunkX * 16 + 8, centerChunkZ * 16 + 8);
    }

    public int radiusBlocks() {
        return (cls.extentChunks() * 16) / 2;
    }
}

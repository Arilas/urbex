package dev.krona.urbex.plan.geom;

/** An immutable integer point in the XZ plane. Block coordinates, not chunk coordinates. */
public record Vec2(int x, int z) {

    public Vec2 plus(int dx, int dz) {
        return new Vec2(x + dx, z + dz);
    }

    public long distanceSquaredTo(Vec2 other) {
        long dx = (long) x - other.x;
        long dz = (long) z - other.z;
        return dx * dx + dz * dz;
    }
}

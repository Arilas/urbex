package dev.krona.urbex.plan.geom;

/** An immutable axis-aligned rectangle in block coordinates. Bounds are inclusive. */
public record Rect(int minX, int minZ, int maxX, int maxZ) {

    public Rect {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("inverted rect: " + minX + "," + minZ + " -> " + maxX + "," + maxZ);
        }
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public int area() {
        return width() * depth();
    }

    public boolean contains(Vec2 p) {
        return p.x() >= minX && p.x() <= maxX && p.z() >= minZ && p.z() <= maxZ;
    }

    public boolean intersects(Rect other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public Vec2 center() {
        return new Vec2((minX + maxX) / 2, (minZ + maxZ) / 2);
    }
}

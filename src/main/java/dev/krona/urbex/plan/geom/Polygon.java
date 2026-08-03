package dev.krona.urbex.plan.geom;

import java.util.List;

/**
 * An immutable closed ring of points. The ring is implicitly closed — the last point connects back
 * to the first, and must not repeat it.
 */
public record Polygon(List<Vec2> ring) {

    public Polygon {
        if (ring.size() < 3) {
            throw new IllegalArgumentException("polygon needs at least 3 points, got " + ring.size());
        }
        ring = List.copyOf(ring);
    }

    /** Twice the signed area. Positive is counter-clockwise. Exact in long arithmetic. */
    public long signedDoubleArea() {
        long total = 0;
        for (int i = 0; i < ring.size(); i++) {
            Vec2 a = ring.get(i);
            Vec2 b = ring.get((i + 1) % ring.size());
            total += (long) a.x() * b.z() - (long) b.x() * a.z();
        }
        return total;
    }

    public boolean isCounterClockwise() {
        return signedDoubleArea() > 0;
    }

    /** Even-odd ray casting. Points exactly on an edge are not guaranteed either way. */
    public boolean contains(Vec2 p) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            Vec2 a = ring.get(i);
            Vec2 b = ring.get(j);
            if ((a.z() > p.z()) != (b.z() > p.z())) {
                long cross = (long) (b.x() - a.x()) * (p.z() - a.z())
                        - (long) (p.x() - a.x()) * (b.z() - a.z());
                boolean bAbove = b.z() > a.z();
                if ((cross > 0) == bAbove) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    public Rect boundingBox() {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vec2 p : ring) {
            minX = Math.min(minX, p.x());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
        }
        return new Rect(minX, minZ, maxX, maxZ);
    }
}

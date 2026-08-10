package dev.krona.urbex.plan;

/** A short access road. The origin lies on an existing primary or secondary road and is not itself tertiary. */
public record TertiarySegment(long id, int originX, int originZ, RoadDirection direction, int length) {

    public boolean contains(int chunkX, int chunkZ) {
        int dx = chunkX - originX;
        int dz = chunkZ - originZ;
        int distance = dx * direction.stepX() + dz * direction.stepZ();
        return distance >= 1 && distance <= length
                && dx * direction.stepZ() == dz * direction.stepX();
    }
}

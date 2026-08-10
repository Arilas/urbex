package dev.krona.urbex.plan;

/**
 * Where roads are. The single seam between road planning and everything that renders or reacts to
 * roads.
 *
 * <p>Implementations must be pure functions of their construction parameters and the queried
 * coordinate: no world state, no mutable random source, no dependence on query order. Callers clip
 * the returned field to the city mask via {@link EffectiveRoad}; a field never knows about cities.
 */
public interface RoadField {

    RoadCell at(int chunkX, int chunkZ);

    /**
     * Just the road class at a chunk, for the many callers that need nothing else. Implementations
     * that can answer this more cheaply than building a whole {@link RoadCell} should override it;
     * the answer must be identical to {@code at(chunkX, chunkZ).type()} either way.
     */
    default RoadType typeAt(int chunkX, int chunkZ) {
        return at(chunkX, chunkZ).type();
    }
}

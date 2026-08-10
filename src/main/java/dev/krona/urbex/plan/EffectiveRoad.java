package dev.krona.urbex.plan;

/** The pure rule turning a raw road field into the roads a city actually renders. */
public final class EffectiveRoad {

    private EffectiveRoad() {
    }

    /**
     * @param hasConnectedCityNeighbour whether at least one chunk this road connects to is also raw city;
     *                                  this is what removes isolated one-chunk stubs at city protrusions
     */
    public static RoadType resolve(RoadType raw, boolean isCity,
                                   boolean hasConnectedCityNeighbour, boolean overridden) {
        if (!isCity || !hasConnectedCityNeighbour || overridden) {
            return RoadType.NONE;
        }
        return raw;
    }
}

package dev.krona.urbex.config;

import dev.krona.urbex.plan.RoadType;

import java.util.Locale;

/** How an accepted random multi-building resolves against a planned road under its footprint. */
public enum MultiBuildingStreetConflict {
    /** Any planned road under the footprint rejects the candidate. */
    BLOCK_ALL,
    /** Only a primary road rejects; accepted complexes suppress secondary and tertiary roads. */
    OVERRIDE_MINOR,
    /** No road rejects; every covered road is suppressed after acceptance. */
    OVERRIDE_ALL;

    public boolean roadBlocks(RoadType roadType) {
        return switch (this) {
            case BLOCK_ALL -> roadType != RoadType.NONE;
            case OVERRIDE_MINOR -> roadType == RoadType.PRIMARY;
            case OVERRIDE_ALL -> false;
        };
    }

    public static MultiBuildingStreetConflict byName(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown multiBuildingStreetConflict '" + name
                    + "'. Valid values: BLOCK_ALL, OVERRIDE_MINOR, OVERRIDE_ALL", e);
        }
    }
}

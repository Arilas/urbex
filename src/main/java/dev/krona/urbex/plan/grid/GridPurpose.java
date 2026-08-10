package dev.krona.urbex.plan.grid;

/**
 * Named keys for the independent hash streams inside {@link GridRoadField}, replacing upstream's
 * loose salt constants.
 *
 * <p>Same discipline as {@code Rng.Purpose}: two logically independent decisions taken at the same
 * address under the same key get the identical stream and silently correlate. Give a new decision a
 * new constant rather than reusing a neighbour's.
 *
 * <p>Keys occupy their own band, clear of {@code Rng.Purpose} (from 0) and of the plan-side band at
 * 2000, so no shipped stream can collide with another module's.
 */
public enum GridPurpose {
    PRIMARY_X_OFFSET,
    PRIMARY_Z_OFFSET,
    PRIMARY_X_ACTIVATION,
    PRIMARY_Z_ACTIVATION,
    DENSITY,
    SECONDARY_X_COUNT,
    SECONDARY_Z_COUNT,
    SECONDARY_X_POSITION,
    SECONDARY_Z_POSITION,
    TERTIARY_CHANCE,
    TERTIARY_SIDE,
    TERTIARY_ORIGIN,
    TERTIARY_LENGTH,
    OPEN_LOT_PARK,
    PLANNED_BRIDGE;

    private static final long OFFSET = 3000L;

    public long key() {
        return OFFSET + ordinal();
    }
}

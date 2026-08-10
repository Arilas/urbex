package dev.krona.urbex.plan.grid;

/**
 * Named keys for the independent hash streams inside {@link GridRoadField}, replacing upstream's
 * loose salt constants.
 *
 * <p>Same discipline as {@code Rng.Purpose}: two logically independent decisions taken at the same
 * address under the same key get the identical stream and silently correlate. Give a new decision a
 * new constant rather than reusing a neighbour's.
 *
 * <p>Keys occupy their own band, clear of {@code Rng.Purpose} (from 0) and of the plan-side band the
 * parked P3 branch defined at offset 1000, so a parked branch returning later cannot collide with a
 * shipped stream.
 *
 * <p>{@link #OPEN_LOT_PARK} and {@link #PLANNED_BRIDGE} are not grid concepts - an open lot and a
 * planned bridge are decided by {@code ChunkContentResolver} and {@code PrimaryBridgePlanner}, in
 * {@code worldgen.lost}, not by anything in this package. They live here anyway, and must stay here,
 * because every ordinal in this enum is address-stable: moving either constant to a "more correct"
 * home changes the ordinal of everything declared after it, which changes {@link #key()} for every
 * one of them, which reseeds every world already generated with this branch. Add new keys at the
 * end for the same reason.
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

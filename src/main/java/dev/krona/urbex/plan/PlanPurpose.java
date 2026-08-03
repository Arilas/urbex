package dev.krona.urbex.plan;

/**
 * Named keys for independent random streams inside the planner.
 * <p>
 * Same discipline as {@code Rng.Purpose}: two logically independent decisions taken at the same
 * address under the same key get the identical stream and silently correlate. Give a new decision
 * a new constant rather than reusing a neighbour's.
 * <p>
 * Keys are offset well clear of {@code Rng.Purpose}'s range so that a plan stream and a
 * world-generation stream can never coincide even where their coordinate spaces overlap.
 */
public enum PlanPurpose {
    SETTLEMENT_EXISTS,
    SETTLEMENT_JITTER_X,
    SETTLEMENT_JITTER_Z,
    SETTLEMENT_STYLE,
    SPOKE_COUNT,
    SPOKE_ANGLE,
    SPOKE_STEP,
    RING_COUNT,
    RING_RADIUS,
    BLOCK_SPLIT_AXIS,
    BLOCK_SPLIT_POS,
    LOT_SIZE,
    LOT_JITTER,
    DISTRICT_NOISE;

    private static final long OFFSET = 1000L;

    public long key() {
        return OFFSET + ordinal();
    }
}

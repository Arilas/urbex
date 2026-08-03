package dev.krona.urbex.plan;

import org.junit.jupiter.api.Test;

import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ports {@code RngTest.theEnumIsAppendOnly}'s guard to {@link PlanPurpose} (whole-branch review, I5):
 * {@link PlanPurpose#key()} returns {@code 1000 + ordinal()}, so deleting, inserting or reordering a
 * constant renumbers every constant after it and silently reseeds every decision addressed under
 * those keys, in every world that already exists. Appending is the only safe edit - which is also why
 * the five presently-unused constants ({@code SETTLEMENT_STYLE}, {@code BLOCK_SPLIT_AXIS},
 * {@code LOT_SIZE}, {@code LOT_JITTER}, {@code DISTRICT_NOISE}) stay in place rather than being
 * cleaned up: removing any of them would renumber every constant after it exactly as harmfully as
 * removing a constant still in use.
 */
class PlanPurposeTest {

    @Test
    void theEnumIsAppendOnly() {
        // A failure here is either a genuine append - update PURPOSE_COUNT, LAST_PURPOSE and
        // PURPOSE_ORDER, and say so in the commit - or a reorder/removal that must be undone.
        assertEquals(PURPOSE_COUNT, PlanPurpose.values().length, "PlanPurpose constant count changed");
        assertEquals(PURPOSE_COUNT - 1, LAST_PURPOSE.ordinal(), LAST_PURPOSE + " is no longer last");

        // The count and the last constant alone would still miss a swap in the middle, so pin the
        // whole order. This is the only assertion that catches every reorder.
        StringJoiner actual = new StringJoiner(",");
        for (PlanPurpose purpose : PlanPurpose.values()) {
            actual.add(purpose.name());
        }
        assertEquals(PURPOSE_ORDER, actual.toString(), "PlanPurpose order changed");
    }

    private static final PlanPurpose LAST_PURPOSE = PlanPurpose.DISTRICT_NOISE;

    private static final int PURPOSE_COUNT = 14;

    private static final String PURPOSE_ORDER =
            "SETTLEMENT_EXISTS,SETTLEMENT_JITTER_X,SETTLEMENT_JITTER_Z,SETTLEMENT_STYLE,SPOKE_COUNT,"
                    + "SPOKE_ANGLE,SPOKE_STEP,RING_COUNT,RING_RADIUS,BLOCK_SPLIT_AXIS,BLOCK_SPLIT_POS,"
                    + "LOT_SIZE,LOT_JITTER,DISTRICT_NOISE";
}

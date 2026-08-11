package dev.krona.urbex.varia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code getMostOccuring()} used to break ties on {@code HashMap} iteration order, which depends
 * on each key's hash bucket - so renaming a key (nothing about its vote count) could silently flip
 * which tied entry won. {@code BuildingInfo}'s 3x3-neighbour cityStyle vote is the one call site,
 * and an even split is the ordinary case at a style boundary, so this pins the tie-break to a
 * stated rule (lexicographically lowest key) instead.
 */
class CounterTest {

    @Test
    void theUniqueHighestCountWins() {
        Counter<String> counter = new Counter<>();
        counter.add("urbex:citystyle_common");
        counter.add("urbex:citystyle_common");
        counter.add("urbex:citystyle_border");

        assertEquals("urbex:citystyle_common", counter.getMostOccuring());
    }

    @Test
    void aTieBreaksOnTheLexicographicallyLowestKeyRegardlessOfInsertionOrder() {
        Counter<String> ascending = new Counter<>();
        ascending.add("urbex:citystyle_border");
        ascending.add("urbex:citystyle_standard");

        Counter<String> descending = new Counter<>();
        descending.add("urbex:citystyle_standard");
        descending.add("urbex:citystyle_border");

        assertEquals("urbex:citystyle_border", ascending.getMostOccuring());
        assertEquals("urbex:citystyle_border", descending.getMostOccuring(),
                "the winner must not depend on which order the tied keys were added in");
    }

    @Test
    void aThreeWayTieAlsoPicksTheLexicographicallyLowestKey() {
        Counter<String> counter = new Counter<>();
        counter.add("urbex:citystyle_standard");
        counter.add("urbex:citystyle_desert");
        counter.add("urbex:citystyle_border");

        assertEquals("urbex:citystyle_border", counter.getMostOccuring());
    }
}

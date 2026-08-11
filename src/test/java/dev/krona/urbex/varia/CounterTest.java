package dev.krona.urbex.varia;

import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code getMostOccuring()} used to break ties on {@code HashMap} iteration order, which depends
 * on each key's hash bucket - so renaming a key (nothing about its vote count) could silently flip
 * which tied entry won. {@code BuildingInfo}'s 3x3-neighbour cityStyle vote is the one call site,
 * and an even split is the ordinary case at a style boundary, so this pins the tie-break to a
 * stated rule (lexicographically lowest {@code tieBreakKey.apply(key)}) instead.
 * <p>
 * The tie-break key is a mandatory parameter, not a default {@code String.valueOf(key)}: a key
 * type with no meaningful {@code toString()} would make {@code String.valueOf} embed an identity
 * hash, which looks deterministic while actually varying run to run - the same bug this class
 * exists to rule out, just hidden one layer down. Forcing every caller to name a stable key (an id,
 * typically) makes that impossible to do by accident.
 */
class CounterTest {

    private static final Function<String, String> IDENTITY = s -> s;

    @Test
    void theUniqueHighestCountWins() {
        Counter<String> counter = new Counter<>();
        counter.add("urbex:citystyle_common");
        counter.add("urbex:citystyle_common");
        counter.add("urbex:citystyle_border");

        assertEquals("urbex:citystyle_common", counter.getMostOccuring(IDENTITY));
    }

    @Test
    void aTieBreaksOnTheLexicographicallyLowestKeyRegardlessOfInsertionOrder() {
        Counter<String> ascending = new Counter<>();
        ascending.add("urbex:citystyle_border");
        ascending.add("urbex:citystyle_standard");

        Counter<String> descending = new Counter<>();
        descending.add("urbex:citystyle_standard");
        descending.add("urbex:citystyle_border");

        assertEquals("urbex:citystyle_border", ascending.getMostOccuring(IDENTITY));
        assertEquals("urbex:citystyle_border", descending.getMostOccuring(IDENTITY),
                "the winner must not depend on which order the tied keys were added in");
    }

    @Test
    void aThreeWayTieAlsoPicksTheLexicographicallyLowestKey() {
        Counter<String> counter = new Counter<>();
        counter.add("urbex:citystyle_standard");
        counter.add("urbex:citystyle_desert");
        counter.add("urbex:citystyle_border");

        assertEquals("urbex:citystyle_border", counter.getMostOccuring(IDENTITY));
    }

    /** A record with no id field at all - {@code toString()} still lists every component, so it
     *  stays a stable, content-based tie-break key even though it is not an asset with an id. */
    private record Widget(String label, int size) {
    }

    @Test
    void theTieBreakKeyCanBeDerivedFromAnythingStableRatherThanTheKeyItself() {
        Counter<Widget> counter = new Counter<>();
        Widget a = new Widget("b-widget", 1);
        Widget b = new Widget("a-widget", 1);
        counter.add(a);
        counter.add(b);

        // Deliberately not Widget::toString (a record's default happens to be stable, but that is
        // not the point being tested): the caller picks whatever field is actually the identity.
        assertEquals(b, counter.getMostOccuring(Widget::label));
    }
}

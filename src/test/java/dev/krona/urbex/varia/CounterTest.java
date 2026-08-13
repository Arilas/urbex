package dev.krona.urbex.varia;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code getMostOccuring()} used to break ties on {@code HashMap} iteration order, which depends
 * on each key's hash bucket - so renaming a key (nothing about its vote count) could silently flip
 * which tied entry won. {@code ChunkPlan}'s 3x3-neighbour cityStyle vote is the one call site,
 * and an even split is the ordinary case at a style boundary, so this pins the tie-break to a
 * stated rule (the lowest key under the caller's comparator) instead.
 * <p>
 * The tie-break is a mandatory parameter, not a default {@code String.valueOf(key)}: a key type
 * with no meaningful {@code toString()} would make {@code String.valueOf} embed an identity hash,
 * which looks deterministic while actually varying run to run - the same bug this class exists to
 * rule out, just hidden one layer down. Forcing every caller to name a stable order makes that
 * impossible to do by accident.
 * <p>
 * It is a {@code Comparator} and not a {@code Function<T, String>} because the real call site
 * counts {@code Identifier}s, whose own order is path-first-then-namespace - see
 * {@link #anIdentifierTieBreaksOnItsOwnOrderNotOnItsToString()}, the case a string key could not
 * express.
 */
class CounterTest {

    private static final Comparator<String> NATURAL = Comparator.naturalOrder();

    @Test
    void theUniqueHighestCountWins() {
        Counter<String> counter = new Counter<>();
        counter.add("urbex:citystyle_common");
        counter.add("urbex:citystyle_common");
        counter.add("urbex:citystyle_border");

        assertEquals("urbex:citystyle_common", counter.getMostOccuring(NATURAL));
    }

    @Test
    void aTieBreaksOnTheLexicographicallyLowestKeyRegardlessOfInsertionOrder() {
        Counter<String> ascending = new Counter<>();
        ascending.add("urbex:citystyle_border");
        ascending.add("urbex:citystyle_standard");

        Counter<String> descending = new Counter<>();
        descending.add("urbex:citystyle_standard");
        descending.add("urbex:citystyle_border");

        assertEquals("urbex:citystyle_border", ascending.getMostOccuring(NATURAL));
        assertEquals("urbex:citystyle_border", descending.getMostOccuring(NATURAL),
                "the winner must not depend on which order the tied keys were added in");
    }

    @Test
    void aThreeWayTieAlsoPicksTheLexicographicallyLowestKey() {
        Counter<String> counter = new Counter<>();
        counter.add("urbex:citystyle_standard");
        counter.add("urbex:citystyle_desert");
        counter.add("urbex:citystyle_border");

        assertEquals("urbex:citystyle_border", counter.getMostOccuring(NATURAL));
    }

    /**
     * The reason the parameter is a comparator. {@code ChunkPlan} counts {@code Identifier}s and
     * breaks ties on {@code Identifier}'s natural order, which compares the path before the
     * namespace - the same order {@code MultiChunk} sorts city styles by. Tie-breaking on
     * {@code toString()} would compare the namespace first and pick the other one here, so the two
     * places that order city styles would silently disagree once a second namespace ships one.
     */
    @Test
    void anIdentifierTieBreaksOnItsOwnOrderNotOnItsToString() {
        Identifier thirdPartyBorder = Identifier.fromNamespaceAndPath("urbexmt", "citystyle_border");
        Identifier ownStandard = Identifier.fromNamespaceAndPath("urbex", "citystyle_standard");

        Counter<Identifier> counter = new Counter<>();
        counter.add(thirdPartyBorder);
        counter.add(ownStandard);

        assertEquals(thirdPartyBorder, counter.getMostOccuring(Comparator.naturalOrder()),
                "path first: 'citystyle_border' < 'citystyle_standard'");
        assertEquals(ownStandard, counter.getMostOccuring(Comparator.comparing(Identifier::toString)),
                "namespace first would have picked the other one - that is the disagreement being ruled out");
    }

    /** A record with no id field at all - {@code toString()} still lists every component, so it
     *  stays a stable, content-based tie-break key even though it is not an asset with an id. */
    private record Widget(String label, int size) {
    }

    @Test
    void theTieBreakCanBeDerivedFromAnythingStableRatherThanTheKeyItself() {
        Counter<Widget> counter = new Counter<>();
        Widget a = new Widget("b-widget", 1);
        Widget b = new Widget("a-widget", 1);
        counter.add(a);
        counter.add(b);

        // Deliberately not Widget::toString (a record's default happens to be stable, but that is
        // not the point being tested): the caller picks whatever field is actually the identity.
        assertEquals(b, counter.getMostOccuring(Comparator.comparing(Widget::label)));
    }
}

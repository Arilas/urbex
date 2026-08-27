package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Rule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dense marker remap: {@code CHAR.030} and {@code CHAR.031}.
 * <p>
 * The measurement behind both is in {@code 06-characters.md} §1. Zombie Apocalypse Essentials ships 244
 * distinct markers of which 162 are non-ASCII, "and every one of those 162 markers misses the ASCII fast
 * path used to resolve a marker to a block, falling back to a hashed lookup on the per-block generation
 * path". So the tests here are about the non-ASCII half: an emoji has to cost what {@code 'X'} costs.
 */
class MarkerIndexTest {

    /**
     * {@code CHAR.030}: a marker resolves by array index, for every marker in the domain.
     * <p>
     * Asserted as a property of the mapping rather than by timing it: every marker in a palette that
     * sweeps ASCII, Greek, Cyrillic and one astral codepoint gets a distinct index in
     * {@code [0, size)}, every codepoint the palette does not define answers {@link MarkerIndex#ABSENT},
     * and the answer for the astral marker is produced the same way the answer for {@code 'X'} is - the
     * method has one path and no branch on whether a codepoint is ASCII. A timing test would measure the
     * JIT; this measures the shape the rule is about.
     */
    @Test
    @Rule("CHAR.030")
    @Rule("CHAR.002")
    void everyMarkerInTheDomainResolvesByIndexAndNotOnlyTheAsciiOnes() {
        List<Marker> markers = new ArrayList<>();
        for (int codepoint : new int[]{' ', 'X', 'a', 0x0391, 0x03A9, 0x0410, 0x044F, 0x1F600}) {
            markers.add(new Marker(codepoint));
        }
        MarkerIndex index = MarkerIndex.of(markers);

        assertEquals(markers.size(), index.size());
        Set<Integer> dense = new LinkedHashSet<>();
        for (Marker marker : markers) {
            int at = index.index(marker.codepoint());
            assertTrue(at >= 0 && at < index.size(), () -> marker + " is outside the dense range");
            assertTrue(dense.add(at), () -> marker + " shares an index with another marker");
        }
        assertEquals(new Marker(0x1F600), index.markers().get(index.index(0x1F600)),
                "an astral marker is one character and one index, not two of either");

        assertEquals(MarkerIndex.ABSENT, index.index('Y'));
        assertEquals(MarkerIndex.ABSENT, index.index(0x0392));
        assertEquals(MarkerIndex.ABSENT, index.index(Character.MAX_CODE_POINT));
        assertEquals(MarkerIndex.ABSENT, index.index(-1),
                "a malformed slice must not take the chunk with it");
        assertEquals(MarkerIndex.ABSENT, index.index(Character.MAX_CODE_POINT + 1));
    }

    /**
     * {@code CHAR.030}'s {@code > Why}: the remap is dense, so the cost is one page per 1024 codepoints
     * a palette uses rather than 1.1 million entries.
     * <p>
     * "A sparse array indexed by codepoint would need 1.1 million entries to make CHAR.030 true for the
     * whole domain; a dense remap needs one entry per marker the palette actually defines." The number
     * this asserts is what the implementation pays instead: the Greek and Cyrillic sweep the rule was
     * written about touches two pages, and printable ASCII touches one.
     */
    @Test
    @Rule("CHAR.030")
    void theRemapPaysForThePagesAPaletteUsesAndNotForTheCodepointRangeItSpans() {
        List<Marker> ascii = new ArrayList<>();
        for (int codepoint = ' '; codepoint <= '~'; codepoint++) {
            ascii.add(new Marker(codepoint));
        }
        assertEquals(1, MarkerIndex.of(ascii).allocatedPages());

        List<Marker> sweep = new ArrayList<>(ascii);
        for (int codepoint = 0x0391; codepoint <= 0x044F; codepoint++) {
            if (Character.isDefined(codepoint)) {
                sweep.add(new Marker(codepoint));
            }
        }
        assertEquals(2, MarkerIndex.of(sweep).allocatedPages(),
                "Greek, Coptic and Cyrillic are one page beside ASCII's");

        // The shape the sparse form would have made expensive: two markers a million codepoints apart.
        MarkerIndex far = MarkerIndex.of(List.of(new Marker(' '), new Marker(0x10FFFD)));
        assertEquals(2, far.allocatedPages());
        assertEquals(2, far.size());
    }

    /**
     * The dense index is assigned in codepoint order, never in the order a set happened to iterate.
     * <p>
     * <b>This is the {@code Map.copyOf} salt defect, one type over.</b> The index decides which compiled
     * entry sits in which array slot, and a {@link Set}'s iteration order is perturbed by a per-JVM salt
     * - so an index built from set order would lay out the same palette differently between runs, which
     * a golden file or a debug dump can see. Asserted by building the same markers from three different
     * orders and comparing the whole mapping, which is a claim that does not depend on this JVM's salt.
     */
    @Test
    @Rule("CHAR.031")
    void theIndexIsTheSameWhateverOrderTheMarkersArriveIn() {
        List<Marker> markers = new ArrayList<>();
        for (int codepoint : new int[]{'z', ' ', 0x0410, 'A', 0x1F600, '0'}) {
            markers.add(new Marker(codepoint));
        }
        MarkerIndex first = MarkerIndex.of(markers);

        List<Marker> shuffled = new ArrayList<>(markers);
        for (int seed = 0; seed < 3; seed++) {
            Collections.shuffle(shuffled, new Random(seed));
            MarkerIndex other = MarkerIndex.of(shuffled);
            assertEquals(first.markers(), other.markers(),
                    "the dense order is the codepoint order, not the order they were handed over");
            for (Marker marker : markers) {
                assertEquals(first.index(marker.codepoint()), other.index(marker.codepoint()));
            }
        }
        assertEquals(0, first.index(' '), "the lowest codepoint takes the lowest index");
        assertNotEquals(0, first.index(0x1F600));
    }
}

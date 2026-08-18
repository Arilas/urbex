package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Rule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CHAR.020}-{@code CHAR.022}: what a marker-assigning command may draw from, in what order, and
 * what happens when it runs out.
 *
 * <p>{@code CHAR.022} carried {@code [NO-FIXTURE: a command invocation]} and was one of two rules
 * {@code ConformanceIndexTest} held as an enumerated exemption, on the grounds that no
 * marker-assigning command existed in version 2. One does now, and the part of it a fixture cannot
 * reach - a command invocation needs a server, a level and an editor session - is not the part these
 * rules are about. What they are about is the alphabet, which is a pure function of Unicode and is
 * asserted here directly.</p>
 */
class MarkerAlphabetTest {

    /** Every {@code Cn} codepoint in the block {@code /exportpart} used to walk. */
    private static final List<Integer> UNASSIGNED_IN_GREEK =
            List.of(0x0378, 0x0379, 0x0380, 0x0381, 0x0382, 0x0383, 0x038B, 0x038D, 0x03A2);

    /**
     * The regression, named as one: these nine are in a shipped pack because a range walk put them
     * there, and 41 of that pack's inline palettes cannot be converted because of it.
     */
    @Test
    @Rule("CHAR.021")
    void theNineUnassignedCodepointsThatReachedAShippedPackAreNotAssignable() {
        for (int codepoint : UNASSIGNED_IN_GREEK) {
            assertFalse(MarkerAlphabet.markers().contains((char) codepoint),
                    () -> "U+" + Marker.hex(codepoint) + " is unassigned and must not be assignable; "
                            + "Zombie Apocalypse Essentials carries 320 markers on these nine");
        }
    }

    /**
     * Stated over the whole alphabet and not only over the nine, because the nine are the instance and
     * this is the property. Asked of {@link Marker#parse}, which is what the loader asks.
     */
    @Test
    @Rule("CHAR.021")
    void everyAssignableMarkerIsOneTheLoaderAccepts() {
        List<String> refused = new ArrayList<>();
        for (char marker : MarkerAlphabet.markers()) {
            if (Marker.parse(Character.toString(marker)).error().isPresent()) {
                refused.add("U+" + Marker.hex(marker));
            }
        }
        assertEquals(List.of(), refused,
                "the alphabet may hold only what CHAR.004 and CHAR.005 permit");
    }

    @Test
    @Rule("CHAR.021")
    void printableAsciiIsExhaustedFirst() {
        List<Character> markers = MarkerAlphabet.markers();
        int scan = 0;
        while (scan < markers.size() && markers.get(scan) < 0x80) {
            scan++;
        }
        final int firstNonAscii = scan;
        assertEquals(92, firstNonAscii,
                () -> "the ASCII run comes first and is the same one /exportpart always used: "
                        + markers.subList(0, firstNonAscii));
        assertTrue(markers.subList(firstNonAscii, markers.size()).stream().noneMatch(c -> c < 0x80),
                "and nothing ASCII is left stranded after it, which is what 'exhausted' means");
    }

    /** Space is legal in a file ({@code CHAR.006}) and is still not something to hand out. */
    @Test
    @Rule("CHAR.021")
    void spaceIsNotAssignedEvenThoughItIsALegalMarker() {
        assertTrue(Marker.parse(" ").result().isPresent(), "CHAR.006: it is a legal marker");
        assertFalse(MarkerAlphabet.markers().contains(' '),
                "and assigning it would spell a part's empty columns and its newest block alike");
    }

    @Test
    @Rule("CHAR.020")
    void nextTakesTheFirstMarkerNotAlreadyInUse() {
        List<Character> markers = MarkerAlphabet.markers();
        assertEquals(markers.getFirst(), MarkerAlphabet.next(Set.of()));

        Set<Character> used = new LinkedHashSet<>(markers.subList(0, 5));
        assertEquals(markers.get(5), MarkerAlphabet.next(used),
                "and skips what is taken rather than stepping past the alphabet");
    }

    @Test
    @Rule("CHAR.022")
    void exhaustingTheAlphabetFailsNamingTheLimitRatherThanContinuingPastIt() {
        Set<Character> everything = new LinkedHashSet<>(MarkerAlphabet.markers());

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> MarkerAlphabet.next(everything));

        assertTrue(failure.getMessage().contains(Integer.toString(MarkerAlphabet.size())),
                () -> "CHAR.022: the failure names the limit: " + failure.getMessage());
    }

    /**
     * The alphabet holds no duplicate, which a range walk cannot promise once ASCII and a block can
     * both contribute the same codepoint.
     */
    @Test
    @Rule("CHAR.020")
    void everyMarkerAppearsOnce() {
        List<Character> markers = MarkerAlphabet.markers();
        assertEquals(markers.size(), Set.copyOf(markers).size(),
                "a marker handed out twice would collide with itself on the second part");
    }
}

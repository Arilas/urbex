package dev.krona.urbex.format.palette;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Markers remapped to a dense integer range, once per compiled palette ({@code CHAR.030},
 * {@code CHAR.031}) - stage 8 of {@code LOAD.001}.
 * <p>
 * <b>The measurement this exists for.</b> Zombie Apocalypse Essentials ships 244 distinct markers, 162
 * of them non-ASCII, "and every one of those 162 markers misses the ASCII fast path used to resolve a
 * marker to a block, falling back to a hashed lookup on the per-block generation path".
 * {@code CHAR.030} closes that: "Resolving a marker to its compiled entry is an array index, for every
 * marker in the domain, not only for ASCII."
 * <p>
 * <b>Why a page table and not one array over the codepoint range.</b> The rule's {@code > Why} rejects
 * the sparse form outright - "a sparse array indexed by codepoint would need 1.1 million entries" - and
 * the obvious repair, one array covering {@code min..max}, is the same array wearing a hat: a palette
 * whose markers are {@code ' '} and one emoji spans a million codepoints and allocates all of them. A
 * two-level table indexes {@code pages[cp >>> 10][cp & 1023]}, which is two array reads for every
 * codepoint in the domain and allocates 4 KB per 1024-codepoint page a palette actually uses. The Greek
 * and Cyrillic sweep touches two pages; printable ASCII touches one. Every page a palette does not use
 * is one shared array of {@link #ABSENT}, so an unused page costs a reference.
 * <p>
 * <b>Dense indices are assigned in codepoint order, never in set order.</b> A {@code Set}'s iteration
 * order is perturbed by a per-JVM salt, and the index decides which compiled entry sits in which array
 * slot - so building one from a set twice would produce two palettes that are equal in behaviour and
 * different in layout, which is a difference a golden file or a debug dump can see. Sorting also makes
 * {@link #markers()} a readable listing rather than a shuffled one.
 */
public final class MarkerIndex {

    /** What {@link #index(int)} answers for a codepoint this palette does not define. */
    public static final int ABSENT = -1;

    private static final int PAGE_BITS = 10;
    private static final int PAGE_SIZE = 1 << PAGE_BITS;
    private static final int PAGE_MASK = PAGE_SIZE - 1;
    private static final int PAGES = (Character.MAX_CODE_POINT + 1) >>> PAGE_BITS;

    /** The page every codepoint of an unused 1024-block shares; never written to. */
    private static final int[] NO_PAGE = emptyPage();

    private final int[][] pages;
    private final int[] codepoints;

    private MarkerIndex(int[][] pages, int[] codepoints) {
        this.pages = pages;
        this.codepoints = codepoints;
    }

    /** Builds the dense remap for exactly these markers. */
    public static MarkerIndex of(Collection<Marker> markers) {
        int[] sorted = markers.stream().mapToInt(Marker::codepoint).distinct().sorted().toArray();
        int[][] pages = new int[PAGES][];
        Arrays.fill(pages, NO_PAGE);
        for (int dense = 0; dense < sorted.length; dense++) {
            int codepoint = sorted[dense];
            int page = codepoint >>> PAGE_BITS;
            if (pages[page] == NO_PAGE) {
                pages[page] = emptyPage();
            }
            pages[page][codepoint & PAGE_MASK] = dense;
        }
        return new MarkerIndex(pages, sorted);
    }

    /**
     * This marker's dense index, or {@link #ABSENT}.
     * <p>
     * Two array reads and a mask, with no hashing, no boxing and no branch on whether the marker is
     * ASCII - which is {@code LOAD.041} as well as {@code CHAR.030}. A codepoint outside the Unicode
     * range lands in the bounds check rather than in an exception, because a slice is read from a file
     * and a malformed one must not take the chunk with it.
     */
    public int index(int codepoint) {
        if (codepoint < 0 || codepoint > Character.MAX_CODE_POINT) {
            return ABSENT;
        }
        return pages[codepoint >>> PAGE_BITS][codepoint & PAGE_MASK];
    }

    /** How many markers this index holds - the length every parallel compiled array has. */
    public int size() {
        return codepoints.length;
    }

    /** The markers, in codepoint order, so that index {@code i} is {@code markers().get(i)}. */
    public List<Marker> markers() {
        List<Marker> markers = new ArrayList<>(codepoints.length);
        for (int codepoint : codepoints) {
            markers.add(new Marker(codepoint));
        }
        return List.copyOf(markers);
    }

    /** How many 1024-codepoint pages this index allocated - what {@code CHAR.030}'s cost really is. */
    public int allocatedPages() {
        int allocated = 0;
        for (int[] page : pages) {
            if (page != NO_PAGE) {
                allocated++;
            }
        }
        return allocated;
    }

    private static int[] emptyPage() {
        int[] page = new int[PAGE_SIZE];
        Arrays.fill(page, ABSENT);
        return page;
    }
}

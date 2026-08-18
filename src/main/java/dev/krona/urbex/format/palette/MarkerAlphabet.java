package dev.krona.urbex.format.palette;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code CHAR.020} and {@code CHAR.021}: the ordered set of codepoints a marker-assigning command draws
 * from, and the only thing it may draw from.
 *
 * <h2>Why an alphabet and not a range</h2>
 *
 * <p>{@code /exportpart} used to assign markers by walking {@code 0x0370} to {@code 0x0500} in
 * sequence, appending every codepoint in between. Nine of those are codepoints Unicode has never
 * assigned - every {@code Cn} in {@code U+0370}-{@code U+03FF} - and they reached a shipped pack:
 * Zombie Apocalypse Essentials carries 320 markers on them across 41 files, which is why 41 of its 79
 * inline palettes cannot be converted until its exporter is fixed. {@code CHAR.021}'s
 * {@code > Why} states it as a rule; this is the rule holding.
 *
 * <p>A range cannot express "assigned, and not a combining mark" because those are properties of
 * individual codepoints scattered through it. So the alphabet is <em>filtered</em>, and filtered
 * through {@link Marker#parse} rather than through a second copy of its conditions: the codepoints an
 * exporter may write are exactly the codepoints the loader will accept, and there is no way for the two
 * to drift apart. A codepoint that stops being legal stops being assignable in the same commit.
 *
 * <h2>Order</h2>
 *
 * <p>{@code CHAR.021} requires printable ASCII to be exhausted first, because a marker is read by a
 * person out of a slice row and an ASCII one can be typed. The ASCII run is the same string, in the
 * same order, that {@code /exportpart} used before this class existed, so a part exported today and one
 * exported last year assign the same markers until the ASCII runs out.
 *
 * <h2>Why it is Basic Multilingual Plane only</h2>
 *
 * <p>Not a limit of the format - {@code CHAR.002} makes an astral codepoint one marker, deliberately.
 * It is a limit of this alphabet, and it costs nothing: 92 ASCII plus the two blocks below give more
 * markers than any shipped part uses, and {@code CHAR.022} refuses rather than overflowing if that ever
 * stops being true. Keeping to the BMP lets the assigning code stay {@code char}-keyed, which is what
 * the editor's own state map is.
 */
public final class MarkerAlphabet {

    /**
     * Printable ASCII, in the order {@code /exportpart} has always used it.
     *
     * <p>Space is a legal marker ({@code CHAR.006}) and is not here: it is the conventional marker for
     * air in every shipped pack, so assigning it to a blockstate would produce a part whose empty
     * columns and whose newest block are spelt the same. {@code "} and {@code \} are absent for the
     * duller reason that they are the two characters a JSON string escapes.
     */
    private static final String ASCII =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;:'<>,.?/`~";

    /** Greek and Coptic, then Cyrillic - the two blocks the old range walk used, now filtered. */
    private static final int[][] BLOCKS = {{0x0370, 0x03FF}, {0x0400, 0x04FF}};

    private static final List<Character> ORDERED = build();

    private MarkerAlphabet() {
    }

    private static List<Character> build() {
        Set<Character> ordered = new LinkedHashSet<>();
        for (char c : ASCII.toCharArray()) {
            if (isAssignable(c)) {
                ordered.add(c);
            }
        }
        for (int[] block : BLOCKS) {
            for (int codepoint = block[0]; codepoint <= block[1]; codepoint++) {
                if (isAssignable(codepoint)) {
                    ordered.add((char) codepoint);
                }
            }
        }
        return List.copyOf(new ArrayList<>(ordered));
    }

    /** {@code CHAR.021}: exactly what {@code CHAR.004} and {@code CHAR.005} permit, asked of them. */
    private static boolean isAssignable(int codepoint) {
        return Marker.parse(new String(Character.toChars(codepoint))).result().isPresent();
    }

    /** How many markers this alphabet can assign. */
    public static int size() {
        return ORDERED.size();
    }

    /** The alphabet in order, for a caller that wants to walk it. */
    public static List<Character> markers() {
        return ORDERED;
    }

    /**
     * {@code CHAR.020}: the first marker of the alphabet that {@code used} does not already hold.
     *
     * @throws IllegalStateException {@code CHAR.022}, naming the limit - never a codepoint past the end
     *                               of the alphabet, which is what walking a range did
     */
    public static char next(Set<Character> used) {
        for (char candidate : ORDERED) {
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("This part needs more markers than Urbex can assign: the "
                + "assignment alphabet holds " + size() + " and all of them are taken. Split the part, "
                + "or move some of its blockstates into a palette so they no longer need one each.");
    }
}

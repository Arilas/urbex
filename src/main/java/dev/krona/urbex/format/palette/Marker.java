package dev.krona.urbex.format.palette;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;

import java.util.Locale;
import java.util.Set;

/**
 * One character of a palette: the key of an entry in {@code palette}, and the character a part's
 * slices are painted with.
 * <p>
 * A codepoint rather than a {@code char}, by {@code CHAR.002}. Version 1 read a marker with
 * {@code String.charAt(0)} after checking {@code String.length() == 1}, so a codepoint outside the
 * Basic Multilingual Plane was two UTF-16 units, was refused, and was refused for the wrong reason -
 * the message said it was "2 characters long", which is a sentence about Java's string encoding and
 * not about the file.
 * <p>
 * The domain is narrower than "any character", and {@link #CODEC} is where that is enforced
 * ({@code CHAR.003}-{@code CHAR.005}). The reason is measured: Zombie Apocalypse Essentials ships 244
 * distinct markers, 162 of them non-ASCII, because {@code /exportpart} assigned them by walking
 * codepoints in sequence - and that sweep produced seven codepoints Unicode has never assigned, which
 * are unstable under editors and normalisation, and one modifier letter, whose rendering in a slice
 * depends on what precedes it.
 */
public record Marker(int codepoint) {

    /**
     * The general categories {@code CHAR.005} excludes, with why each cannot be a marker.
     * <p>
     * Keyed by {@link Character#getType(int)}'s constant rather than by the two-letter Unicode name,
     * because that is what the check has in hand; the two-letter names are in the {@code CHAR.005}
     * rule and in the message this produces, so a reader of either can find the other.
     */
    private static final java.util.Map<Integer, String[]> EXCLUDED = java.util.Map.of(
            (int) Character.NON_SPACING_MARK, new String[]{"Mn, a combining mark",
                    "A combining mark occupies no position of its own in a slice."},
            (int) Character.COMBINING_SPACING_MARK, new String[]{"Mc, a combining mark",
                    "A combining mark occupies no position of its own in a slice."},
            (int) Character.ENCLOSING_MARK, new String[]{"Me, a combining mark",
                    "A combining mark occupies no position of its own in a slice."},
            (int) Character.CONTROL, new String[]{"Cc, a control character",
                    "A control character is invisible in the file, so a mismatched marker cannot be"
                            + " diagnosed by reading it."},
            (int) Character.FORMAT, new String[]{"Cf, a format character",
                    "A format character is invisible in the file, so a mismatched marker cannot be"
                            + " diagnosed by reading it."},
            (int) Character.SURROGATE, new String[]{"Cs, a surrogate",
                    "A lone surrogate is not a character; write the codepoint it is half of."},
            (int) Character.PRIVATE_USE, new String[]{"Co, private use",
                    "A private use codepoint has no assigned meaning, so nothing can render it."});

    /**
     * Decodes a marker, enforcing {@code CHAR.001} through {@code CHAR.005}.
     * <p>
     * {@code CHAR.006} and {@code CHAR.007} are both absences of a check rather than checks: U+0020
     * SPACE is category {@code Zs}, which {@link #EXCLUDED} does not list, and a marker is compared as
     * written because nothing here normalises it. Normalising would silently merge two markers an
     * author distinguished, and {@code CHAR.005} removes the only case where that would matter.
     */
    public static final Codec<Marker> CODEC =
            Codec.STRING.comapFlatMap(Marker::parse, Marker::asString);

    /** Parses one marker, or the {@code CHAR} diagnostic that refuses it. */
    public static DataResult<Marker> parse(String written) {
        int codepoints = written.codePointCount(0, written.length());
        if (codepoints != 1) {
            return DataResult.error(() -> Diag.DIAG_050.message(
                    Diagnostics.DECODING_LOCATION, "'" + written + "'", codepoints));
        }
        int codepoint = written.codePointAt(0);
        if (!Character.isDefined(codepoint)) {
            return DataResult.error(() -> Diag.DIAG_051.message(
                    Diagnostics.DECODING_LOCATION, hex(codepoint)));
        }
        String[] excluded = EXCLUDED.get(Character.getType(codepoint));
        if (excluded != null) {
            return DataResult.error(() -> Diag.DIAG_052.message(
                    Diagnostics.DECODING_LOCATION, hex(codepoint), excluded[0], excluded[1]));
        }
        return DataResult.success(new Marker(codepoint));
    }

    /** The marker as it is written in a file. */
    public String asString() {
        return new String(Character.toChars(codepoint));
    }

    /** {@code U+}-style hex, at least four digits, as {@code DIAG.051} and {@code DIAG.052} print it. */
    public static String hex(int codepoint) {
        return String.format(Locale.ROOT, "%04X", codepoint);
    }

    /** The categories {@code CHAR.005} names, for a test that wants to walk them. */
    public static Set<Integer> excludedCategories() {
        return EXCLUDED.keySet();
    }

    @Override
    public String toString() {
        return "'" + asString() + "' (U+" + hex(codepoint) + ")";
    }
}

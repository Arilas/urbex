package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.krona.urbex.Urbex;
import net.minecraft.resources.Identifier;

import java.util.Optional;

public class DataTools {

    public static Optional<String> toNullable(Character c) {
        if (c == null) {
            return Optional.empty();
        } else {
            return Optional.of(Character.toString(c));
        }
    }

    public static Character getNullableChar(Optional<String> opt) {
        return opt.isPresent() ? opt.get().charAt(0) : null;
    }

    public static Identifier fromName(String name) {
        if (!name.contains(":")) {
            throw new IllegalArgumentException("Unqualified datapack reference '" + name
                    + "': references must name their namespace, e.g. '" + Urbex.MODID + ":" + name + "'");
        }
        return Identifier.parse(name);
    }

    /**
     * Strict identifier codec for every registry's {@code extends} field: decodes through
     * {@link #fromName}, so a bare (unqualified) value fails the same way, with the same message,
     * as any other datapack cross-reference - rather than {@code Identifier.CODEC}'s own defaulting,
     * which resolves a bare string against the {@code minecraft} namespace instead of erroring.
     * Catches {@code RuntimeException}, not just {@link IllegalArgumentException}: {@link
     * Identifier#parse} throws {@code net.minecraft.IdentifierException} (a {@code RuntimeException},
     * not an {@code IllegalArgumentException}) for a qualified but malformed id (illegal characters,
     * uppercase, etc.), and that must fail cleanly as a per-file {@link DataResult#error} too,
     * instead of escaping the codec as a thrown exception.
     */
    public static final Codec<Identifier> STRICT_IDENTIFIER_CODEC = Codec.STRING.comapFlatMap(
            s -> {
                try {
                    return DataResult.success(fromName(s));
                } catch (RuntimeException e) {
                    return DataResult.error(e::getMessage);
                }
            },
            Identifier::toString);

    public static final Codec<String> STRICT_REFERENCE_CODEC =
            STRICT_IDENTIFIER_CODEC.xmap(Identifier::toString, DataTools::fromName);

    /**
     * A palette marker: the one-character string a slice row is painted with, and the same value a
     * building's {@code filler} names.
     * <p>
     * Both used to be {@code Codec.STRING} read with a raw {@code charAt(0)}, so {@code ""} threw
     * {@code StringIndexOutOfBoundsException} out of the decode with no file named, and {@code "ab"}
     * quietly meant {@code "a"} - the second is the worse of the two, because a marker that is not
     * the one the author wrote paints the wrong block everywhere it is used and nothing says so.
     * Length is the whole check: a marker may be any character a slice row can hold.
     */
    public static final Codec<String> PALETTE_CHAR_STRING = Codec.STRING.validate(
            s -> s.length() == 1
                    ? DataResult.success(s)
                    : DataResult.error(() -> "Palette marker '" + s + "' must be exactly one "
                    + "character, but is " + s.length() + " characters long"));

    /** {@link #PALETTE_CHAR_STRING} for the fields that hold the marker as a {@code char}. */
    public static final Codec<Character> PALETTE_CHAR =
            PALETTE_CHAR_STRING.xmap(s -> s.charAt(0), String::valueOf);

}

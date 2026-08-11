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
}

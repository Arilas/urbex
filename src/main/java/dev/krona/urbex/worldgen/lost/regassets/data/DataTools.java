package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.krona.urbex.Urbex;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

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

    /**
     * Strict codec for a field that always names a block tag, written with the leading {@code #}
     * every other tag reference in the format uses ({@code biomes.if_any}, a {@code stuff}
     * matcher). Requiring the {@code #} rather than accepting a bare identifier is deliberate: a
     * field that only ever takes a tag would otherwise invite a block id, which would decode
     * cleanly and then match nothing.
     * <p>
     * Not {@link TagKey#hashedCodec}, which decodes the same shape but parses the remainder with
     * {@code Identifier.read} - so {@code "#rotatable"} becomes {@code minecraft:rotatable} instead
     * of failing. Going through {@link #fromName} instead makes an unqualified tag reference the
     * same load error, with the same message, as any other unqualified reference.
     */
    public static final Codec<TagKey<Block>> BLOCK_TAG_CODEC = Codec.STRING.comapFlatMap(
            s -> {
                if (!s.startsWith("#")) {
                    return DataResult.error(() -> "Block tag reference '" + s
                            + "' must start with '#', e.g. '#" + Urbex.MODID + ":rotatable'");
                }
                try {
                    return DataResult.success(
                            TagKey.create(Registries.BLOCK, fromName(s.substring(1))));
                } catch (RuntimeException e) {
                    return DataResult.error(e::getMessage);
                }
            },
            tag -> "#" + tag.location());
}

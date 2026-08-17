package dev.krona.urbex.format.palette;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A namespaced statement about a node beyond which block it is ({@code TRAIT.001}, {@code TRAIT.002}).
 * <p>
 * <b>Opaque, on purpose, until per-trait schemas land.</b> This carries the trait's id and its payload
 * as written, and validates neither. {@code TRAIT.003} (an unregistered id is refused),
 * {@code TRAIT.009} (a block-valued field holds a node) and every rule about a particular trait need a
 * trait registry, which this task does not build. Decoding the payload into a shape now would mean
 * guessing at that registry's contract and then rewriting both.
 * <p>
 * <b>Why a {@link Tag} and not a {@code CompoundTag}.</b> {@code TRAIT.001} maps a trait id to that
 * trait's <em>value</em>, "which is an object unless that trait's schema defines a scalar shorthand" -
 * and {@code urbex:rotatable} is that case, written {@code "urbex:rotatable": false}. A compound-only
 * payload could not hold it. The rule said "object" flatly until this task; the fixture for
 * {@code TRAIT.071} disagreed with it, and the rule was the half that was wrong.
 * <p>
 * The id goes through {@link DataTools#STRICT_IDENTIFIER_CODEC} rather than {@code Identifier.CODEC},
 * which enforces {@code TRAIT.002}'s namespace: {@code Identifier.CODEC} would resolve a bare
 * {@code "damaged"} against the {@code minecraft} namespace and then report it as an unregistered
 * trait, naming a namespace the author never wrote.
 *
 * @param id   the trait id, always namespaced
 * @param data the trait's payload, exactly as the file wrote it
 */
public record Trait(Identifier id, Tag data) {

    /**
     * Any JSON value, as NBT.
     * <p>
     * Converted through {@link Dynamic#convert} rather than decoded field by field, because there are
     * no fields to decode yet - see the class note. NBT rather than a retained {@link Dynamic} because
     * a {@link Tag} has value equality, which is what makes two spellings of one trait comparable for
     * an {@code equiv=} fixture, and because the payload of the one version 1 field this replaces
     * ({@code tag}) was already NBT.
     */
    public static final Codec<Tag> PAYLOAD_CODEC = Codec.PASSTHROUGH.xmap(
            dynamic -> dynamic.convert(NbtOps.INSTANCE).getValue(),
            tag -> new Dynamic<>(NbtOps.INSTANCE, tag));

    /** {@code traits}: an object mapping a trait id to that trait's payload. */
    public static final Codec<Map<Identifier, Trait>> MAP_CODEC =
            Codec.unboundedMap(DataTools.STRICT_IDENTIFIER_CODEC, PAYLOAD_CODEC).xmap(
                    Trait::fromPayloads, Trait::toPayloads);

    private static Map<Identifier, Trait> fromPayloads(Map<Identifier, Tag> payloads) {
        Map<Identifier, Trait> traits = new LinkedHashMap<>();
        payloads.forEach((id, data) -> traits.put(id, new Trait(id, data)));
        return Map.copyOf(traits);
    }

    private static Map<Identifier, Tag> toPayloads(Map<Identifier, Trait> traits) {
        Map<Identifier, Tag> payloads = new LinkedHashMap<>();
        traits.forEach((id, trait) -> payloads.put(id, trait.data()));
        return payloads;
    }
}

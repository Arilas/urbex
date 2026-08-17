package dev.krona.urbex.format.palette;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * The operands {@code REF.050} closes the set of, in the order that rule lists them.
     * <p>
     * Ordered so that a payload carrying two of them names the same one every run, for the reason
     * {@code RawChoice.OWN_KEYS_IN_ORDER} states: a message that shuffles cannot be pinned by a test.
     */
    private static final List<String> OPERANDS = List.of("$ref", "$only", "$without", "$spread");

    /**
     * The first operand this trait's payload writes anywhere inside it, if any ({@code VER.016}).
     * <p>
     * A scan for a key rather than a reading of the payload, and that is the point: {@code TRAIT.009}
     * makes a block-valued trait field a node and {@code MODEL.032} lets a node carry {@code $ref}, so a
     * satellite naming a definition is something the format allows and nothing yet resolves - resolving
     * it needs the per-trait schemas that say which fields hold nodes, which is what this class stays
     * opaque until. Seeing the key needs none of that.
     * <p>
     * Both ways of leaving it alone are worse than refusing it. Expanding it here would mean guessing
     * which fields are nodes; not expanding it gives the satellite no block, so a marker's damaged form
     * or unlit form is silently air. {@code REF.022}'s case - a {@code $ref} on the trait object itself -
     * is caught by the same scan, for its own narrower reason, until it gets its own check.
     * <p>
     * <b>All four operands, not {@code $ref} alone.</b> The first version of this check named
     * {@code $ref} and let the other three through, so a {@code $spread} inside a satellite's
     * {@code choices} survived into the resolved palette as NBT nothing would ever expand - the same
     * silence, in the operand an author is more likely to reach for when extending an inherited list.
     * <p>
     * At any depth, because a satellite may be a weighted node whose choices are nodes: the {@code $ref}
     * in {@code {"into": {"kind": "weighted", "choices": [{"weight": 1, "$ref": "rubble"}]}}} is three
     * levels down and is exactly as unresolved as one at the top.
     */
    public Optional<String> operandHeld() {
        return OPERANDS.stream().filter(operand -> holds(data, operand)).findFirst();
    }

    private static boolean holds(Tag tag, String operand) {
        if (tag instanceof CompoundTag compound) {
            if (compound.keySet().contains(operand)) {
                return true;
            }
            return compound.keySet().stream()
                    .map(compound::get)
                    .anyMatch(value -> value != null && holds(value, operand));
        }
        if (tag instanceof ListTag list) {
            return list.stream().anyMatch(element -> holds(element, operand));
        }
        return false;
    }

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

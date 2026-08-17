package dev.krona.urbex.format.palette;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.StrictKeys;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One trait on a node: which trait it is, and its decoded payload ({@code TRAIT.001},
 * {@code TRAIT.002}).
 * <p>
 * <b>No longer opaque.</b> Until this task the payload was carried as raw NBT and validated nowhere,
 * because every rule about a particular trait needs a registry saying what that trait's fields are. That
 * registry is {@link Traits} and the contract is {@link TraitType}, so a payload is now decoded by the
 * trait that owns it. Three things follow, and each of them was a hole:
 * <ul>
 *   <li>{@code TRAIT.003} - an id no loaded mod defines is refused, with {@code DIAG.020};</li>
 *   <li>{@code MODEL.004} - "a key that this specification does not define is refused, <b>at every
 *       level</b>" - now reaches inside a payload, which was the one level it did not;</li>
 *   <li>{@code TRAIT.009} - a block-valued field holds a node, so a satellite is a {@link RawNode} that
 *       stage 3 resolves, which retires {@code VER.016}'s blanket refusal of an operand inside a
 *       trait.</li>
 * </ul>
 * <b>{@code REF.022} is enforced by the second of those and not by a check of its own.</b> "A trait
 * object may not carry {@code $ref}": a trait's key set is declared by {@link TraitType#keys()} and no
 * trait declares an operand, so {@code $ref} inside a trait object is a key the specification does not
 * define at that level and is refused as {@code DIAG.003}. That is narrower than {@code VER.016}'s scan
 * in exactly the way the two rules differ - it refuses an operand on the trait <em>object</em> and says
 * nothing about a satellite node, which may carry one and now does.
 * <p>
 * <b>Why the id goes through {@link DataTools#STRICT_IDENTIFIER_CODEC}.</b> {@code TRAIT.002} requires a
 * namespace; {@code Identifier.CODEC} would resolve a bare {@code "damaged"} against {@code minecraft}
 * and it would then be reported as an unregistered trait naming a namespace the author never wrote.
 *
 * @param type  the registered trait, which is also where its schema and declarations live
 * @param value the decoded payload
 */
public record Trait(TraitType<?> type, TraitValue value) {

    /** The trait's id ({@code TRAIT.002}). */
    public Identifier id() {
        return type.id();
    }

    /**
     * {@code traits}: an object mapping a trait id to that trait's value ({@code TRAIT.001}).
     * <p>
     * Decoded in two steps because the payload's schema depends on the id: the map is read as untyped
     * values first, and each is then key-checked and decoded by the trait it belongs to. Every failure
     * in one {@code traits} object is collected rather than the first ({@code DIAG.903}) - a node with
     * two misspelt trait ids is one edit if the author is told about both.
     */
    public static final Codec<Map<Identifier, Trait>> MAP_CODEC =
            Codec.unboundedMap(DataTools.STRICT_IDENTIFIER_CODEC, Codec.PASSTHROUGH)
                    .flatXmap(Trait::decodeAll, Trait::encodeAll);

    /** {@code TRAIT.009}: this trait's satellites as written, by field. */
    public Map<String, RawNode> satellites() {
        return type.satellitesOf(value);
    }

    /** The same trait with its satellites replaced by their resolved forms. */
    public Trait withSatellites(Map<String, RawNode> resolved) {
        return resolved.isEmpty() ? this : new Trait(type, type.withSatellitesOf(value, resolved));
    }

    private static DataResult<Map<Identifier, Trait>> decodeAll(Map<Identifier, Dynamic<?>> written) {
        Diagnostics diagnostics = new Diagnostics();
        // Insertion-ordered, not Map.copyOf: a node's traits are iterated by MODEL.081's completeness
        // walk, by TRAIT.005's inheritance and by the resolution report, and Map.copyOf's iteration
        // order is perturbed by a per-JVM salt.
        Map<Identifier, Trait> traits = new LinkedHashMap<>();
        written.forEach((id, payload) -> decodeOne(id, payload, diagnostics)
                .ifPresent(trait -> traits.put(id, trait)));
        Optional<String> error = diagnostics.asError();
        if (error.isPresent()) {
            String message = error.get();
            return DataResult.error(() -> message);
        }
        return DataResult.success(Collections.unmodifiableMap(traits));
    }

    private static Optional<Trait> decodeOne(Identifier id, Dynamic<?> payload,
                                             Diagnostics diagnostics) {
        Optional<TraitType<?>> type = Traits.of(id);
        if (type.isEmpty()) {
            // TRAIT.003, and TRAIT.091's clause: the namespace half of DIAG.020's row is what tells a
            // misspelt id apart from a trait belonging to a mod that is not installed.
            diagnostics.error(Diag.DIAG_020, Diagnostics.DECODING_LOCATION, "'" + id + "'",
                    Traits.registersNamespace(id.getNamespace())
                            ? ""
                            : ", and nothing loaded registers the namespace '"
                                    + id.getNamespace() + "'");
            return Optional.empty();
        }
        TraitType<?> found = type.orElseThrow();
        // MODEL.004 inside the payload, before it is decoded: a misspelt key holding a value of the
        // wrong type has to be reported as a misspelling rather than as a type error, which is the same
        // reason RawNode checks its keys before decoding its fields.
        Optional<String> unknownKey = StrictKeys.problem(payload, found.keys(),
                "a '" + id + "' trait", Map.of());
        if (unknownKey.isPresent()) {
            diagnostics.nested(unknownKey.get());
            return Optional.empty();
        }
        DataResult<? extends TraitValue> decoded = found.codec().parse(payload);
        if (decoded.error().isPresent()) {
            diagnostics.nested(decoded.error().orElseThrow().message());
            return Optional.empty();
        }
        return Optional.of(new Trait(found, decoded.result().orElseThrow()));
    }

    /**
     * Re-encodes a trait map.
     * <p>
     * Through {@link JsonOps} rather than the ops in hand, because {@code Codec.PASSTHROUGH} converts a
     * {@link Dynamic} into whichever ops it is encoding to and this side of an {@code flatXmap} is not
     * handed one. The round trip is exact for every payload the format defines, all of which are JSON
     * values.
     */
    private static DataResult<Map<Identifier, Dynamic<?>>> encodeAll(Map<Identifier, Trait> traits) {
        Map<Identifier, Dynamic<?>> payloads = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Trait> entry : traits.entrySet()) {
            DataResult<Dynamic<?>> payload = encodeOne(entry.getValue());
            if (payload.error().isPresent()) {
                String message = payload.error().orElseThrow().message();
                return DataResult.error(() -> message);
            }
            payloads.put(entry.getKey(), payload.result().orElseThrow());
        }
        return DataResult.success(Collections.unmodifiableMap(payloads));
    }

    @SuppressWarnings("unchecked")
    private static DataResult<Dynamic<?>> encodeOne(Trait trait) {
        Codec<TraitValue> codec = (Codec<TraitValue>) trait.type().codec();
        return codec.encodeStart(JsonOps.INSTANCE, trait.value())
                .map(json -> new Dynamic<>(JsonOps.INSTANCE, json));
    }
}

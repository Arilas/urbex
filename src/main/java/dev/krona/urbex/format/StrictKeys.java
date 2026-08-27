package dev.krona.urbex.format;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wraps a codec so that a key the specification does not define fails the decode, naming the key.
 * <p>
 * This is {@code MODEL.004} - "A key that this specification does not define is refused, at every
 * level of a version 2 palette file" - and it is the single largest behavioural difference between the
 * two format versions. It exists because of what version 1 did instead, which was nothing: DFU
 * ignores unknown map keys, so three shipped palettes wrote {@code damaged} inside {@code blocks[]}
 * elements, where nothing read it, for the whole lifetime of the pack, and the authoring guide listed
 * the resulting symptom as <em>"(no message at all)"</em>.
 * <p>
 * <b>A pre-pass over the raw {@link Dynamic}, not a field on each record.</b> Exactly the shape
 * {@code RetiredKeys} already uses, and for the same two reasons: it fires on a key's presence
 * whatever its value's type, so a misspelt key holding a number is caught as a misspelling rather
 * than as a type error; and it cannot be forgotten on a new field, because it is derived from one key
 * set per context rather than restated per field.
 * <p>
 * <b>Retired keys are consulted before unknown ones.</b> {@code VER.010} and {@code VER.011} require
 * a version 1 key to be refused <em>by name</em>, with its replacement, and {@code VER.012} forbids
 * ever silently ignoring one. A retired key is an unknown key as far as the codec is concerned, so
 * without this map it would be reported as a spelling mistake - "'blocks' is not a key of a node" -
 * which sends the author looking for a typo rather than reading the migration table.
 */
public final class StrictKeys {

    private StrictKeys() {
    }

    /**
     * A key version 2 no longer accepts, and what to say about it.
     *
     * @param diag   {@link Diag#DIAG_060} for a key that was renamed ({@code VER.010}) or
     *               {@link Diag#DIAG_061} for one that was deleted ({@code VER.011}); the two have
     *               different remedies, so they are different messages
     * @param detail the replacement key for a rename, or what to do instead for a deletion - the
     *               third placeholder of either message
     */
    public record Retirement(Diag diag, String detail) {
    }

    /** Refuses any key outside {@code keys}, reporting {@code context} as the thing that has them. */
    public static <A> Codec<A> only(Codec<A> base, Set<String> keys, String context) {
        return only(base, keys, context, Map.of());
    }

    /** {@link #only(Codec, Set, String)}, consulting {@code retired} before reporting a key unknown. */
    public static <A> Codec<A> only(Codec<A> base, Set<String> keys, String context,
                                    Map<String, Retirement> retired) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
                Optional<String> problem = problem(new Dynamic<>(ops, input), keys, context, retired);
                if (problem.isPresent()) {
                    String message = problem.get();
                    return DataResult.error(() -> message);
                }
                return base.decode(ops, input);
            }

            @Override
            public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
                return base.encode(input, ops, prefix);
            }

            @Override
            public String toString() {
                return base + "[only " + new TreeSet<>(keys) + "]";
            }
        };
    }

    /**
     * Pure: every key of {@code dyn} that {@code keys} does not allow, as one message, or empty.
     * <p>
     * All of them rather than the first, by {@code DIAG.903}: a palette with four misspelt keys is one
     * edit if the author is told about four keys and four load attempts if they are told about one.
     * Keys are visited in sorted order so that the same file reports the same message every run -
     * a map's iteration order is not something a datapack author can see, and a diagnostic that
     * shuffles between runs cannot be pinned by a test or quoted in a bug report.
     * <p>
     * A non-map input yields empty, as in {@code RetiredKeys}: the wrapped codec will reject it on
     * its own terms, and "unknown key" is nonsense to say about a JSON string.
     */
    public static Optional<String> problem(Dynamic<?> dyn, Set<String> keys, String context,
                                           Map<String, Retirement> retired) {
        Optional<Set<String>> present = presentKeys(dyn);
        if (present.isEmpty()) {
            return Optional.empty();
        }
        Diagnostics diagnostics = new Diagnostics();
        for (String key : present.get()) {
            if (keys.contains(key)) {
                continue;
            }
            Retirement retirement = retired.get(key);
            if (retirement != null) {
                diagnostics.error(retirement.diag(), Diagnostics.DECODING_LOCATION, key,
                        retirement.detail());
            } else {
                diagnostics.error(Diag.DIAG_003, Diagnostics.DECODING_LOCATION, key, context);
            }
        }
        return diagnostics.asError();
    }

    /** The keys of a map input, sorted; empty when the input is not a map. */
    private static Optional<Set<String>> presentKeys(Dynamic<?> dyn) {
        return dyn.asMapOpt().result().map(stream -> {
            Set<String> keys = new TreeSet<>();
            stream.forEach(pair -> keys.add(pair.getFirst().asString("")));
            return keys;
        });
    }
}

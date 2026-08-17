package dev.krona.urbex.format;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.OptionalDynamic;

import java.util.Map;
import java.util.TreeSet;

/**
 * Picks a registry entry's codec by the {@code version} its document declares, before that document
 * is decoded.
 * <p>
 * {@code VER.003} states the ordering as a rule - "Version selection happens before decoding, by
 * inspecting the raw document, so a version 2 file is never first decoded by the version 1 codec" -
 * and it is a rule rather than an implementation note because getting it the other way round is
 * silent. Version 1 ignores keys it does not know ({@code VER.004} keeps it that way), so a version 2
 * palette handed to the version 1 codec does not fail: it decodes to a palette with no entries, or to
 * one holding whichever keys the two formats happen to share. That is the same failure
 * {@code RetiredKeys} exists to prevent, one level up, and this is the same answer - a pre-pass over
 * the raw {@link Dynamic}, before any field is read.
 * <p>
 * {@code VER.001} is the default: a document with no {@code version} is version 1 and is read
 * unchanged. Every shipped pack is version 1 and both bundled addon packs are produced by importers,
 * so this is required rather than courteous.
 * <p>
 * Nothing here is palette-specific. {@code VER.040} says a registry adopting version 2 follows
 * {@code VER.001}-{@code VER.004} unchanged, so the mechanism that implements them belongs beside the
 * rules rather than inside the palette codec that happens to be the first caller.
 */
public final class Versioned {

    private Versioned() {
    }

    /**
     * An entry that knows which format version it was written in.
     * <p>
     * Needed for encoding, not decoding: {@link #dispatch} reads the version out of the document on
     * the way in, but on the way out the document does not exist yet, and the value has to say which
     * codec wrote it. Without this, a round-trip through the dispatcher would emit a document with no
     * {@code version} - which {@code VER.001} then reads back as version 1.
     */
    public interface Asset {
        int formatVersion();
    }

    /**
     * A codec that reads {@code version} first and delegates to the codec registered for it.
     *
     * @param context   what to call the thing being decoded in a {@code DIAG.001} message
     * @param byVersion the codec per declared version; the key {@code 1} is also what an absent
     *                  {@code version} selects, by {@code VER.001}
     */
    public static <T extends Asset> Codec<T> dispatch(String context,
                                                      Map<Integer, Codec<? extends T>> byVersion) {
        Map<Integer, Codec<? extends T>> codecs = Map.copyOf(byVersion);
        return new Codec<>() {
            @Override
            public <O> DataResult<Pair<T, O>> decode(DynamicOps<O> ops, O input) {
                DataResult<Integer> version = declaredVersion(new Dynamic<>(ops, input));
                if (version.error().isPresent()) {
                    String message = version.error().get().message();
                    return DataResult.error(() -> message);
                }
                int declared = version.result().orElseThrow();
                Codec<? extends T> codec = codecs.get(declared);
                if (codec == null) {
                    return DataResult.error(() -> Diag.DIAG_001.message(context, declared));
                }
                return codec.decode(ops, input).map(pair -> pair.mapFirst(value -> (T) value));
            }

            @Override
            public <O> DataResult<O> encode(T input, DynamicOps<O> ops, O prefix) {
                int version = input.formatVersion();
                Codec<? extends T> codec = codecs.get(version);
                if (codec == null) {
                    return DataResult.error(() -> context + " reports format version " + version
                            + ", which is not one of " + new TreeSet<>(codecs.keySet()));
                }
                // Safe: `codec` is the codec registered for exactly the version `input` reports, so
                // the value it encodes is the value that version's branch produced. The cast is the
                // price of a heterogeneous map, which is what makes the branches independent - the
                // whole point of VER.005 is that version 1 and version 2 are not dialects of each
                // other, so a common supertype for their codecs would be a fiction.
                @SuppressWarnings("unchecked")
                Codec<T> asT = (Codec<T>) codec;
                return asT.encode(input, ops, prefix);
            }

            @Override
            public String toString() {
                return context + "[by version " + new TreeSet<>(codecs.keySet()) + "]";
            }
        };
    }

    /**
     * The declared {@code version}, or 1 when absent.
     * <p>
     * A present {@code version} that is not a whole number is an error rather than a fall back to 1, and
     * rather than a truncation. Both matter, and both are plausible typos: {@code "version": "2"} read
     * with a default would hand the document to the version 1 codec, which ignores every version 2 key
     * in it and loads a palette the author did not write, and {@code "version": 2.5} truncated to 2
     * would silently accept a version this Urbex has never defined. {@code MODEL.002} refuses "any
     * {@code version} other than 1 or 2", and a string and a fraction are each other than both.
     */
    private static DataResult<Integer> declaredVersion(Dynamic<?> dyn) {
        OptionalDynamic<?> version = dyn.get("version");
        if (version.result().isEmpty()) {
            return DataResult.success(1);
        }
        Object written = version.result().orElseThrow().getValue();
        return version.asNumber().flatMap(number -> number.doubleValue() == number.intValue()
                        ? DataResult.success(number.intValue())
                        : DataResult.error(() -> Diag.DIAG_001.message(
                                Diagnostics.DECODING_LOCATION, written)))
                .mapError(error -> error.startsWith(Diagnostics.DECODING_LOCATION)
                        ? error
                        : Diag.DIAG_001.message(Diagnostics.DECODING_LOCATION, written));
    }
}

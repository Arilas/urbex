package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fails the decode of any registry entry that still uses a retired inheritance key.
 * <p>
 * {@code inherit} and {@code parent} were <b>deleted, not aliased</b>: {@code extends} is the one
 * inheritance key across all thirteen registries. Without this, leaving one in place is silent and
 * costly, because DFU codecs ignore unknown map keys - a city style declaring
 * {@code "inherit": "urbex:citystyle_common"} decodes cleanly and loads as a <em>chain root with no
 * inheritance</em>. What the author then sees depends on what else the file says, and neither
 * outcome names the key: a file that also spells out complete wiring loads and quietly generates
 * without anything it meant to inherit, and one that relies on the parent fails with
 * {@code "declares no 'streetblocks.parts'"}, which mentions neither {@code inherit} nor
 * {@code extends}. The {@code presets} registry alone would have logged a WARN, via
 * {@code UnknownKeys} - a line in a log, not a refusal.
 * <p>
 * This matters most for the format Urbex is being ported <em>from</em>: in Lost Cities Modern
 * Tweaks, {@code inherit} <em>was</em> the key, so a mechanically converted pack is exactly the pack
 * that hits this.
 * <p>
 * The check is a pre-pass rather than a field on each record, so it fires on the retired key's
 * presence whatever its value's type, and so no registry can be added without it - see
 * {@code RetiredKeysRejectedTest}, which walks every registered codec.
 */
public final class RetiredKeys {

    /**
     * Retired key -> the key that replaced it. A {@link LinkedHashMap} rather than {@link Map#of}
     * because a file carrying both must be reported the same way every run.
     */
    private static final Map<String, String> REPLACEMENTS = new LinkedHashMap<>();

    static {
        REPLACEMENTS.put("inherit", "extends");
        REPLACEMENTS.put("parent", "extends");
    }

    private RetiredKeys() {
    }

    /**
     * Pure: the error message for the first retired key present at the top level, or empty.
     * <p>
     * A non-map input yields empty rather than an error - the wrapped codec will reject it on its
     * own terms, and saying "retired key" about a JSON array would be nonsense.
     */
    public static Optional<String> problem(Dynamic<?> dyn, String context) {
        Set<String> present = dyn.asMapOpt().result().stream().flatMap(stream -> stream)
                .map(pair -> pair.getFirst().asString(""))
                .collect(Collectors.toSet());
        // Iterating REPLACEMENTS, not present: a file carrying both keys reports the same one every
        // run, in declaration order, rather than in whatever order the map yielded its keys.
        return REPLACEMENTS.entrySet().stream()
                .filter(entry -> present.contains(entry.getKey()))
                .findFirst()
                .map(entry -> "This " + context + " declares '" + entry.getKey() + "', which Urbex"
                        + " deleted rather than renamed: use '" + entry.getValue() + "' instead."
                        + " Left as it is, the key is ignored and this file loads with no"
                        + " inheritance at all.");
    }

    /**
     * Wraps a codec so a retired key fails the decode. Encode is delegated untouched - these keys
     * can never be produced by an encoder, and {@code PaletteDefinition}/{@code BuildingPartDefinition}/
     * {@code PresetDefinition} encode on live command and GUI paths that inspect the {@link DataResult}
     * themselves.
     */
    public static <A> Codec<A> reject(Codec<A> base, String context) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
                Optional<String> problem = problem(new Dynamic<>(ops, input), context);
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
                return base + "[no retired keys]";
            }
        };
    }
}

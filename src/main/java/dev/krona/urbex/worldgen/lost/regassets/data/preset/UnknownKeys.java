package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.Urbex;

import java.util.List;
import java.util.Set;

/**
 * Unknown-key detection for preset section codecs. DFU codecs silently ignore unknown map keys
 * (loads never fail, which keeps packs forward-compatible), so this surfaces typos with a single
 * WARN per decode instead. Two pieces so the detection itself is pure and unit-testable.
 */
public class UnknownKeys {

    /**
     * Pure: unknown top-level keys of a map-shaped Dynamic. Keys starting with "_" are pack
     * metadata and are never reported.
     */
    public static List<String> check(Dynamic<?> dyn, Set<String> allowed) {
        return dyn.asMapOpt().result().stream().flatMap(s -> s)
                .map(p -> p.getFirst().asString(""))
                .filter(k -> !k.startsWith("_") && !allowed.contains(k))
                .toList();
    }

    /** Wraps a codec: decode is unchanged, but unknown keys log one WARN naming them and the context. */
    public static <A> Codec<A> warning(Codec<A> base, Set<String> allowed, String context) {
        return Codec.PASSTHROUGH.comapFlatMap(dyn -> {
            List<String> unknown = check(dyn, allowed);
            if (!unknown.isEmpty()) {
                Urbex.getLogger().warn("Ignoring unknown key(s) in preset {}: {}", context, unknown);
            }
            return base.parse(dyn);
        }, a -> new Dynamic<>(JsonOps.INSTANCE, base.encodeStart(JsonOps.INSTANCE, a).getOrThrow()));
    }
}

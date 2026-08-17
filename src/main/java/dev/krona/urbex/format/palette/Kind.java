package dev.krona.urbex.format.palette;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which block source a {@link RawNode} takes its block from.
 * <p>
 * {@code MODEL.013}: a kind selects <em>exactly one</em> block source, and the kind-specific keys of
 * one kind are not accepted on another. Version 1 had the same five sources - {@code block},
 * {@code blocks}, {@code variant}, {@code frompalette} and a socket {@code lightSource} - as five
 * independent optional keys on one entry, read by an {@code if}/{@code else if} ladder that took the
 * first present key in source order and dropped the rest without a word. Nothing in the format said
 * they were exclusive, because nothing in the format said anything about them.
 */
public enum Kind {

    /** {@code MODEL.040}: one block state, named by the required {@code block} field. */
    BLOCK("block", "block"),

    /** {@code MODEL.044}: one of the nodes in the required {@code choices} list. */
    WEIGHTED("weighted", "choices"),

    /** {@code MODEL.050}: one block drawn uniformly from the block tag named by {@code tag}. */
    TAG("tag", "tag"),

    /** {@code MODEL.060}: whatever the marker named by {@code of} resolves to. */
    ALIAS("alias", "of"),

    /** {@code MODEL.070}: no block of its own - the candidates in its placement lists are its source. */
    LIGHT_SOCKET("light_socket", "floor", "wall", "ceiling", "free");

    /** Where a {@link #LIGHT_SOCKET} candidate may be placed ({@code MODEL.071}). */
    public enum Placement {
        FLOOR("floor"),
        WALL("wall"),
        CEILING("ceiling"),
        FREE("free");

        private final String key;

        Placement(String key) {
            this.key = key;
        }

        /** The key this list is written under. */
        public String key() {
            return key;
        }
    }

    private static final Map<String, Kind> BY_KEY = byKey();

    private final String key;
    private final Set<String> ownKeys;

    Kind(String key, String... ownKeys) {
        this.key = key;
        this.ownKeys = Set.of(ownKeys);
    }

    /** The value of {@code kind} that selects this one. */
    public String key() {
        return key;
    }

    /**
     * The keys only this kind accepts.
     * <p>
     * This is the data {@code MODEL.013} is checked from, and it is stated once, here, rather than as
     * one codec per kind. A dispatching codec would report a kind-specific key on the wrong kind as a
     * failure to match any branch, naming nothing in particular; {@code MODEL.013} instead says the
     * mistake "[is] caught by MODEL.004", which means the author is told which key does not belong -
     * and that needs a key set, not a dispatch.
     */
    public Set<String> ownKeys() {
        return ownKeys;
    }

    /** Every key that belongs to exactly one kind. */
    public static Set<String> allKindSpecificKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (Kind kind : values()) {
            keys.addAll(kind.ownKeys);
        }
        return Set.copyOf(keys);
    }

    /** {@code MODEL.012}: a kind outside the five defined values is refused. */
    public static final Codec<Kind> CODEC = Codec.STRING.comapFlatMap(
            written -> {
                Kind kind = BY_KEY.get(written);
                return kind != null
                        ? DataResult.success(kind)
                        : DataResult.error(() -> Diag.DIAG_004.message(
                                Diagnostics.DECODING_LOCATION, "'" + written + "'"));
            },
            Kind::key);

    private static Map<String, Kind> byKey() {
        Map<String, Kind> byKey = new LinkedHashMap<>();
        for (Kind kind : values()) {
            byKey.put(kind.key, kind);
        }
        return Map.copyOf(byKey);
    }
}

package dev.krona.urbex.worldgen;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The publish/retire rules a set of scoped runtimes obeys, with the scope's own types kept out.
 *
 * <p>Generic in both the key and the value on purpose, and not because a second instantiation is
 * expected: {@code ServerLevel} and {@code MinecraftServer} are final-ish vanilla classes that no
 * test can stand in for, so the lifetime rules below - publish once, retire on unload, swap
 * atomically, refuse everything after close - would otherwise be reachable only from a running
 * server. Here they are ordinary unit-testable code, and {@link GenerationSession} is the thin
 * level-typed edge that uses them.</p>
 *
 * <p>The rule that matters most is what a <em>reader</em> sees. {@link #find} hands back a value,
 * and whoever holds it keeps it: a {@link #republish} or a {@link #retire} that lands afterwards
 * replaces what the <em>next</em> reader gets and never mutates or empties the value already handed
 * out. That is what lets a chunk that is already generating finish against the epoch it started
 * with, instead of having its assets cleared underneath it (issue #125).</p>
 */
final class RuntimeRepository<K, V> {

    private final Map<K, V> published = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * Publishes {@code value} for {@code key}, replacing any previous value in one write.
     *
     * @return the value now published for the key
     * @throws IllegalStateException if this repository has been closed - a runtime published into a
     *                               retired server or level would never be retired again
     */
    V publish(K key, V value) {
        if (closed) {
            throw new IllegalStateException("Cannot publish into a closed runtime repository");
        }
        published.put(key, value);
        return value;
    }

    @Nullable
    V find(K key) {
        return published.get(key);
    }

    /** Retires one key's runtime. Whatever already holds it keeps working; nothing new finds it. */
    @Nullable
    V retire(K key) {
        return published.remove(key);
    }

    /**
     * Rebuilds every published runtime and swaps each in, one key at a time.
     *
     * <p>One key at a time is deliberate, and it is not a weaker guarantee than a whole-map swap:
     * every reader looks one key up, so the only atomicity a reader can observe is per key, and
     * {@code ConcurrentHashMap.put} gives that. Building outside the map matters more - a reader
     * must never see a half-built runtime, so each one is finished before it is published.</p>
     */
    void republish(Function<K, V> rebuild) {
        if (closed) {
            throw new IllegalStateException("Cannot republish into a closed runtime repository");
        }
        for (K key : List.copyOf(published.keySet())) {
            V rebuilt = rebuild.apply(key);
            // Not putIfAbsent/replace: the key may have been retired while this ran (a level
            // unloading during a reload), and a rebuilt runtime for a retired level must not
            // resurrect it.
            published.computeIfPresent(key, (k, old) -> rebuilt);
        }
    }

    /** Retires everything and refuses further publication. Idempotent. */
    void close() {
        closed = true;
        published.clear();
    }

    boolean isClosed() {
        return closed;
    }

    int size() {
        return published.size();
    }
}

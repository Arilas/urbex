package dev.krona.urbex.varia;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public class Counter<T> {
    private final Map<T, Integer> internalMap = new HashMap<>();

    public void add(T key) {
        if (!internalMap.containsKey(key)) {
            internalMap.put(key, 0);
        }
        internalMap.put(key, internalMap.get(key)+1);
    }

    public Map<T, Integer> getMap() {
        return internalMap;
    }

    public int get(T key) {
        return internalMap.getOrDefault(key, 0);
    }

    /**
     * The key with the highest count. A tie breaks on the lowest key under {@code tieBreak}, not on
     * iteration order: {@code internalMap} is a {@code HashMap}, whose iteration order depends on
     * each key's hash bucket, so an unqualified tie-break here would let an unrelated rename of a
     * key (e.g. a city style) silently flip which tied entry wins - a bad property for a mod whose
     * headline claim is reproducible generation.
     * <p>
     * {@code tieBreak} is mandatory rather than defaulting to {@code String.valueOf}, on purpose: a
     * key type with no meaningful {@code toString()} (e.g. {@code CityStyle}, whose default
     * {@code Object.toString()} embeds its identity hash) would make {@code String.valueOf} look
     * deterministic while actually varying run to run - exactly the bug this method exists to rule
     * out, just moved one layer down instead of fixed. Callers must supply an order over something
     * itself stable, such as an id.
     * <p>
     * A {@code Comparator} rather than a {@code Function<T, String>}: the caller that matters here
     * counts {@code Identifier}s, and {@code Identifier}'s own order is path-first-then-namespace,
     * which no single {@code String} key reproduces. Taking a string key forced that caller to
     * compare {@code toString()} instead - namespace first - so one asset kind was ordered two ways
     * in two places (here and {@code MultiChunk}'s city-style sort), agreeing only for as long as
     * every city style lived in one namespace.
     */
    public T getMostOccuring(Comparator<? super T> tieBreak) {
        T max = null;
        int maxCount = -1;
        for (Map.Entry<T, Integer> entry : internalMap.entrySet()) {
            int count = entry.getValue();
            if (count > maxCount
                    || (count == maxCount && tieBreak.compare(entry.getKey(), max) < 0)) {
                maxCount = count;
                max = entry.getKey();
            }
        }
        return max;
    }
}

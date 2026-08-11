package dev.krona.urbex.varia;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

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
     * The key with the highest count. A tie breaks on the lexicographically lowest
     * {@code tieBreakKey.apply(key)}, not on iteration order: {@code internalMap} is a
     * {@code HashMap}, whose iteration order depends on each key's hash bucket, so an
     * unqualified tie-break here would let an unrelated rename of a key (e.g. a city style)
     * silently flip which tied entry wins - a bad property for a mod whose headline claim is
     * reproducible generation.
     * <p>
     * {@code tieBreakKey} is mandatory rather than defaulting to {@code String.valueOf}, on
     * purpose: a key type with no meaningful {@code toString()} (e.g. {@code CityStyle}, whose
     * default {@code Object.toString()} embeds its identity hash) would make
     * {@code String.valueOf} look deterministic while actually varying run to run - exactly the
     * bug this method exists to rule out, just moved one layer down instead of fixed. Callers
     * must supply a key that is itself stable, such as an id.
     */
    public T getMostOccuring(Function<T, String> tieBreakKey) {
        T max = null;
        int maxCount = -1;
        for (Map.Entry<T, Integer> entry : internalMap.entrySet()) {
            int count = entry.getValue();
            if (count > maxCount
                    || (count == maxCount && tieBreakKey.apply(entry.getKey()).compareTo(tieBreakKey.apply(max)) < 0)) {
                maxCount = count;
                max = entry.getKey();
            }
        }
        return max;
    }
}

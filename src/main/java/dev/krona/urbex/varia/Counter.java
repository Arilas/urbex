package dev.krona.urbex.varia;

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
     * The key with the highest count. A tie breaks on the lexicographically lowest
     * {@code String.valueOf} of the key, not on iteration order: {@code internalMap} is a
     * {@code HashMap}, whose iteration order depends on each key's hash bucket, so an
     * unqualified tie-break here would let an unrelated rename of a key (e.g. a city style)
     * silently flip which tied entry wins - a bad property for a mod whose headline claim is
     * reproducible generation. Ties are ordinary here: {@link dev.krona.urbex.worldgen.lost.BuildingInfo}'s
     * 3x3-neighbour cityStyle vote, this method's only caller, produces an even split at any
     * style boundary.
     */
    public T getMostOccuring() {
        T max = null;
        int maxCount = -1;
        for (Map.Entry<T, Integer> entry : internalMap.entrySet()) {
            int count = entry.getValue();
            if (count > maxCount
                    || (count == maxCount && String.valueOf(entry.getKey()).compareTo(String.valueOf(max)) < 0)) {
                maxCount = count;
                max = entry.getKey();
            }
        }
        return max;
    }
}

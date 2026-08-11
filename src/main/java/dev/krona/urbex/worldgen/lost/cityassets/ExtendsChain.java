package dev.krona.urbex.worldgen.lost.cityassets;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Walks an {@code extends} chain and returns it root-first, so a runtime asset can apply each link
 * in order and let each descendant overwrite what its ancestors set.
 * <p>
 * Pure: it takes a lookup function rather than a registry, so it is testable without a level. This
 * generalises the resolution {@code Presets.resolve} did for preset {@code parent} chains alone.
 */
public class ExtendsChain {

    private ExtendsChain() {
    }

    /**
     * @param id        the asset being resolved
     * @param lookup    resolves an id to a registry entry, or null if the registry has no such entry
     * @param extendsOf reads an entry's {@code extends} link
     * @return the chain root-first: index 0 is the furthest ancestor, the last element is {@code id}
     * @throws IllegalStateException if the chain cycles, or a link names an id {@code lookup} does
     *                               not know
     */
    public static <R> List<R> resolve(Identifier id, Function<Identifier, R> lookup,
                                      Function<R, Optional<Identifier>> extendsOf) {
        List<R> chain = new ArrayList<>();       // leaf..root
        Set<Identifier> seen = new LinkedHashSet<>();
        Identifier cur = id;
        while (cur != null) {
            if (!seen.add(cur)) {
                String path = seen.stream().map(Identifier::toString).collect(Collectors.joining(" -> "));
                throw new IllegalStateException("'extends' cycle: " + path + " -> " + cur);
            }
            R entry = lookup.apply(cur);
            if (entry == null) {
                throw new IllegalStateException(
                        "Unknown asset '" + cur + "' (referenced from '" + id + "')");
            }
            chain.add(entry);
            cur = extendsOf.apply(entry).orElse(null);
        }
        java.util.Collections.reverse(chain);    // root..leaf
        return chain;
    }
}

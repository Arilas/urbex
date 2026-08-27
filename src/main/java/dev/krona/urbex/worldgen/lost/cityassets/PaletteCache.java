package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.GenerationMetrics;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The compiled palettes a world has already built, so it does not build them again.
 *
 * <p>A {@link CompiledPalette} is a pure function of the {@link Palette}s merged into it, and those
 * are compiled assets: one per style, per building, per part, fixed for the world. What varied was
 * only which chunk was asking. {@code computePalette} carried an upstream {@code // Cache the
 * combined palette?} comment and answered it by deep-copying three {@link Map}s over a hundred-odd
 * entries for <em>every part with a local palette in every chunk</em> (issue #53).</p>
 *
 * <h2>Why the merge is not flattened</h2>
 *
 * <p>The obvious simplification - memoize {@code merge(style, building, part)} in one step - is
 * wrong. {@link CompiledPalette#CompiledPalette(CompiledPalette, Palette...)} copies the base and
 * then runs the character-reference resolution loop again over the new palettes only, while the
 * varargs constructor runs it once over all of them together. A palette entry that refers to another
 * character can therefore resolve differently, and "first definition wins" in that loop makes the
 * order it sees load-bearing. So this memoizes each step of the existing composition rather than a
 * shorter one that would produce a different palette.</p>
 *
 * <h2>Bounded by the pack, not by the world</h2>
 *
 * <p>Keys are compiled assets compared by identity, so the number of entries is bounded by the
 * combinations a datapack actually declares - not by how far a player walks. That is what makes this
 * a finite asset-derived index rather than another unbounded coordinate cache (issue #132); the
 * {@code PERF=} line reports its size for exactly that reason.</p>
 */
public final class PaletteCache {

    /**
     * {@code draw -> compiled}, the root of every chain.
     *
     * <p>Keyed on the <em>list</em> a style drew rather than on one flattened palette, which is what
     * {@code VER.006} requires: a draw may hold a version 1 and a version 2 palette, and there is no
     * single {@code Palette} that is both. {@link CompiledPalette} merges compiled markers, so the draw
     * is the thing that identifies the merge. Bounded by what the pack declares exactly as before - the
     * number of distinct draws is the product of the group sizes, not a function of how far a player
     * walks.</p>
     */
    private final Map<List<Palette>, CompiledPalette> roots = new ConcurrentHashMap<>();
    /** {@code (base, overlay) -> compiled}, one step of the chain. */
    private final Map<Overlay, CompiledPalette> overlays = new ConcurrentHashMap<>();

    private record Overlay(CompiledPalette base, Palette extra) {}

    /**
     * Hits and misses, so "how many deep copies did this avoid" is a number rather than an argument.
     * Null when metrics are off, like every other counter here.
     */
    private final GenerationMetrics.CacheStats stats =
            GenerationMetrics.enabled() ? GenerationMetrics.cache("palettes", this::size) : null;

    /** The palette compiled from one style's draw. */
    public CompiledPalette of(List<Palette> root) {
        // get / compute-outside / putIfAbsent, like every other cache here: compiling inside a
        // ConcurrentHashMap bin lock stalls every other worldgen thread whose key shares the bin,
        // and racing threads compile equal palettes because the inputs are immutable.
        CompiledPalette existing = roots.get(root);
        if (existing != null) {
            if (stats != null) {
                stats.hit();
            }
            return existing;
        }
        if (stats != null) {
            stats.miss();
        }
        CompiledPalette compiled = new CompiledPalette(root.toArray(new Palette[0]));
        CompiledPalette raced = roots.putIfAbsent(root, compiled);
        if (raced != null && stats != null) {
            stats.raced();
        }
        return raced != null ? raced : compiled;
    }

    /**
     * {@code base} with {@code extra} merged over it, or {@code base} itself when there is nothing
     * to merge.
     */
    public CompiledPalette with(CompiledPalette base, @Nullable Palette extra) {
        if (extra == null) {
            return base;
        }
        Overlay key = new Overlay(base, extra);
        CompiledPalette existing = overlays.get(key);
        if (existing != null) {
            if (stats != null) {
                stats.hit();
            }
            return existing;
        }
        if (stats != null) {
            stats.miss();
        }
        CompiledPalette compiled = new CompiledPalette(base, extra);
        CompiledPalette raced = overlays.putIfAbsent(key, compiled);
        if (raced != null && stats != null) {
            stats.raced();
        }
        return raced != null ? raced : compiled;
    }

    /** How many palettes this world has compiled. Reported by {@code -Durbex.metrics}. */
    public int size() {
        return roots.size() + overlays.size();
    }

    public void clear() {
        roots.clear();
        overlays.clear();
    }
}

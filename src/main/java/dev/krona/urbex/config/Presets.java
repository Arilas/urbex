package dev.krona.urbex.config;

import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves {@link PresetRE} parent chains into runtime {@link Preset}s, and lists the presets a
 * UI should offer to browse.
 */
public class Presets {

    public static final TagKey<PresetRE> TAG_BROWSABLE =
            TagKey.create(CustomRegistries.PRESET_REGISTRY_KEY, Identifier.fromNamespaceAndPath("urbex", "presets"));

    private static final Identifier DEFAULT_PRESET_ID = Identifier.fromNamespaceAndPath("urbex", "default");

    // Worldgen worker threads resolve presets concurrently; get / construct-outside / putIfAbsent
    // mirrors RegistryAssetRegistry (a resolve() call never touches this map while holding a lock
    // that resolving a parent could re-enter).
    private static final Map<Identifier, Preset> CACHE = new ConcurrentHashMap<>();

    private Presets() {
    }

    /**
     * Pure resolution core: walks the {@code parent} chain of {@code id} through {@code lookup},
     * then applies the chain root-first onto a fresh {@link Preset}. Testable without any registry
     * or level.
     *
     * @throws IllegalStateException if the parent chain cycles, or a referenced parent id is
     *                                unknown to {@code lookup}.
     */
    public static Preset resolve(Identifier id, Function<Identifier, PresetRE> lookup) {
        List<PresetRE> chain = new ArrayList<>();   // leaf..root
        Set<Identifier> seen = new LinkedHashSet<>();
        Identifier cur = id;
        while (cur != null) {
            if (!seen.add(cur)) {
                String path = seen.stream().map(Identifier::toString).collect(Collectors.joining(" -> "));
                throw new IllegalStateException("Preset parent cycle: " + path + " -> " + cur);
            }
            PresetRE re = lookup.apply(cur);
            if (re == null) {
                throw new IllegalStateException("Unknown preset '" + cur + "' (referenced from '" + id + "')");
            }
            chain.add(re);
            cur = re.parent().orElse(null);
        }
        Preset p = new Preset(id);
        for (int i = chain.size() - 1; i >= 0; i--) {
            chain.get(i).applyTo(p);
        }
        return p;
    }

    /** Registry-backed wrapper around {@link #resolve(Identifier, Function)}, cached per id. */
    public static Preset resolve(RegistryAccess access, Identifier id) {
        Preset cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        Registry<PresetRE> registry = access.lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY);
        Preset resolved = resolve(id, registry::getValue);
        Preset raced = CACHE.putIfAbsent(id, resolved);
        return raced != null ? raced : resolved;
    }

    /** Clones {@code base} and applies {@code overrides}'s present sections on top of the clone. */
    public static Preset applyOverrides(Preset base, PresetRE overrides) {
        Preset p = base.copy();
        overrides.applyTo(p);
        return p;
    }

    /**
     * Members of tag {@code #urbex:presets} in registry order; if the tag is missing or empty,
     * every registry entry instead. {@code urbex:default} sorts first, then the rest
     * lexicographically.
     */
    public static List<Identifier> listBrowsable(RegistryAccess access) {
        Registry<PresetRE> registry = access.lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY);
        List<Identifier> ids = new ArrayList<>();
        for (Holder<PresetRE> holder : registry.getTagOrEmpty(TAG_BROWSABLE)) {
            holder.unwrapKey().ifPresent(key -> ids.add(key.identifier()));
        }
        if (ids.isEmpty()) {
            ids.addAll(registry.keySet());
        }
        ids.sort(Comparator.comparing((Identifier i) -> !i.equals(DEFAULT_PRESET_ID))
                .thenComparing(Identifier::compareTo));
        return ids;
    }

    /** Clears the resolution cache; wired into {@code AssetRegistries.reset()}. */
    public static void reset() {
        CACHE.clear();
    }
}

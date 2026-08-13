package dev.krona.urbex.config;

import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.ExtendsChain;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Resolves {@link PresetDefinition} {@code extends} chains into runtime {@link Preset}s, and lists the
 * presets a UI should offer to browse.
 */
public class Presets {

    public static final TagKey<PresetDefinition> TAG_BROWSABLE =
            TagKey.create(CustomRegistries.PRESET_REGISTRY_KEY, Identifier.fromNamespaceAndPath("urbex", "presets"));

    private static final Identifier DEFAULT_PRESET_ID = Identifier.fromNamespaceAndPath("urbex", "default");

    // Worldgen worker threads resolve presets concurrently; get / construct-outside / putIfAbsent
    // mirrors RegistryAssetRegistry (a resolve() call never touches this map while holding a lock
    // that resolving a parent could re-enter).
    private static final Map<Identifier, Preset> CACHE = new ConcurrentHashMap<>();

    private Presets() {
    }

    /**
     * Pure resolution core: walks the {@code extends} chain of {@code id} through {@code lookup},
     * then applies the chain root-first onto a fresh {@link Preset}. Testable without any registry
     * or level.
     *
     * @throws IllegalStateException if the extends chain cycles, or a referenced id is unknown to
     *                                {@code lookup}.
     */
    public static Preset resolve(Identifier id, Function<Identifier, PresetDefinition> lookup) {
        List<PresetDefinition> chain = ExtendsChain.resolve(id, lookup, PresetDefinition::getExtends);
        Preset p = new Preset(id);
        for (PresetDefinition re : chain) {
            re.applyTo(p);
        }
        return p;
    }

    /** Registry-backed wrapper around {@link #resolve(Identifier, Function)}, cached per id. */
    public static Preset resolve(RegistryAccess access, Identifier id) {
        Preset cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        Registry<PresetDefinition> registry = access.lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY);
        Preset resolved = resolve(id, registry::getValue);
        Preset raced = CACHE.putIfAbsent(id, resolved);
        return raced != null ? raced : resolved;
    }

    /** Clones {@code base} and applies {@code overrides}'s present sections on top of the clone. */
    public static Preset applyOverrides(Preset base, PresetDefinition overrides) {
        Preset p = base.copy();
        overrides.applyTo(p);
        return p;
    }

    /**
     * Members of tag {@code #urbex:presets}; if the tag is missing or empty, every registry entry
     * instead. Either way the result is sorted - the sort below is unconditional, so neither the
     * tag's declared order nor the registry's survives it. {@code urbex:default} sorts first, then
     * the rest by {@link Identifier#compareTo}, which is <em>path, then namespace</em> and not
     * lexicographic on the whole id: {@code b:apple} sorts before {@code a:zebra}. That is the same
     * order {@code MultiChunk}'s city-style sort and {@code ChunkPlan}'s city-style vote already
     * use.
     */
    public static List<Identifier> listBrowsable(RegistryAccess access) {
        Registry<PresetDefinition> registry = access.lookupOrThrow(CustomRegistries.PRESET_REGISTRY_KEY);
        List<Identifier> ids = new ArrayList<>();
        for (Holder<PresetDefinition> holder : registry.getTagOrEmpty(TAG_BROWSABLE)) {
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

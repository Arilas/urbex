package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.lost.regassets.Extendable;
import dev.krona.urbex.worldgen.lost.regassets.IAsset;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CommonLevelAccessor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class RegistryAssetRegistry<T, R> {

    // Registry assets are looked up from every worldgen worker thread. Concurrent, and populated
    // with get / construct-outside / putIfAbsent rather than computeIfAbsent, to avoid deadlocking
    // inside a bin lock if constructing T ever re-enters this map. Losing the race just means one
    // throwaway copy of an immutable asset.
    private final Map<Identifier, T> assets = new ConcurrentHashMap<>();
    private final ResourceKey<Registry<R>> registryKey;
    /**
     * Compiles a resolved {@code extends} chain into the runtime asset.
     * <p>
     * It is handed the {@link RegistryAccess} the chain was read from, because compiling an asset
     * can require resolving a reference into another of these registries - a palette entry naming a
     * {@code variant} is the case that exists. That used to be done by asking
     * {@code ServerAccess.getServer().getLevel(OVERWORLD)}, which pinned every dimension's variants
     * to the overworld's registries and threw from a worldgen worker if the static server reference
     * was not populated yet (issue #60). The access the caller already holds is the correct one.
     */
    private final BiFunction<RegistryAccess, List<R>, T> assetConstructor;

    public RegistryAssetRegistry(ResourceKey<Registry<R>> registryKey,
                                 BiFunction<RegistryAccess, List<R>, T> assetConstructor) {
        this.registryKey = registryKey;
        this.assetConstructor = assetConstructor;
    }

    public T get(CommonLevelAccessor level, String name) {
        if (name == null) {
            return null;
        }
        return get(level, DataTools.fromName(name));
    }

    @Nonnull
    public T getOrThrow(CommonLevelAccessor level, String name) {
        if (name == null) {
            throw new RuntimeException("Invalid name given to " + registryKey.registry() + " getOrThrow!");
        }
        T result = get(level, DataTools.fromName(name));
        if (result == null) {
            throw new RuntimeException("Can't find '" + name + "' in " + registryKey.registry() + "!");
        }
        return result;
    }

    @Nullable
    public T getOrWarn(CommonLevelAccessor level, String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        T rc = get(level, DataTools.fromName(name));
        if (rc == null) {
            // Warning
            Urbex.LOGGER.warn("Cannot find '" + name + "' in " + registryKey.registry() + "!");
        }
        return rc;
    }

    public T get(CommonLevelAccessor level, Identifier name) {
        if (name == null) {
            return null;
        }
        T t = assets.get(name);
        if (t == null) {
            try {
                RegistryAccess access = level.registryAccess();
                Registry<R> registry = access.lookupOrThrow(registryKey);
                List<R> chain = ExtendsChain.resolve(name,
                        key -> {
                            R entry = registry.getValue(ResourceKey.create(registryKey, key));
                            if (entry instanceof IAsset asset) {
                                asset.setRegistryName(key);
                            }
                            return entry;
                        },
                        entry -> entry instanceof Extendable ext ? ext.getExtends() : Optional.empty());
                t = assetConstructor.apply(access, chain);
            } catch (Exception e) {
                throw new RuntimeException("Error getting resource " + name + "!", e);
            }
            T raced = assets.putIfAbsent(name, t);
            if (raced != null) {
                t = raced;
            }
        }
        return t;
    }

    /**
     * Same lookup as {@link #get(CommonLevelAccessor, Identifier)}, but for callers that only have
     * registry access and no {@link CommonLevelAccessor} - the world-creation preview, chiefly, via
     * {@code NullDimensionInfo}.
     */
    @Nullable
    public T get(RegistryAccess access, String name) {
        if (name == null) {
            return null;
        }
        return get(access, DataTools.fromName(name));
    }

    /**
     * @throws RuntimeException naming the registry and the id, including when {@code access} is
     *                          null - an asset compiled without registry access cannot resolve a
     *                          cross-reference, and saying so beats a {@code NullPointerException}
     *                          from somewhere further in.
     */
    @Nonnull
    public T getOrThrow(RegistryAccess access, String name) {
        if (name == null) {
            throw new RuntimeException("Invalid name given to " + registryKey.registry() + " getOrThrow!");
        }
        if (access == null) {
            throw new RuntimeException("Cannot resolve '" + name + "' in " + registryKey.registry()
                    + "! This asset was compiled without registry access.");
        }
        T result = get(access, DataTools.fromName(name));
        if (result == null) {
            throw new RuntimeException("Can't find '" + name + "' in " + registryKey.registry() + "!");
        }
        return result;
    }

    @Nullable
    public T get(RegistryAccess access, Identifier name) {
        if (access == null || name == null) {
            return null;
        }
        T t = assets.get(name);
        if (t == null) {
            try {
                Registry<R> registry = access.lookupOrThrow(registryKey);
                List<R> chain = ExtendsChain.resolve(name,
                        key -> {
                            R entry = registry.getValue(ResourceKey.create(registryKey, key));
                            if (entry instanceof IAsset asset) {
                                asset.setRegistryName(key);
                            }
                            return entry;
                        },
                        entry -> entry instanceof Extendable ext ? ext.getExtends() : Optional.empty());
                t = assetConstructor.apply(access, chain);
            } catch (Exception e) {
                throw new RuntimeException("Error getting resource " + name + "!", e);
            }
            T raced = assets.putIfAbsent(name, t);
            if (raced != null) {
                t = raced;
            }
        }
        return t;
    }

    public void loadAll(CommonLevelAccessor level) {
        if (level == null) {
            return;
        }
        RegistryAccess access = level.registryAccess();
        Registry<R> registry = access.lookupOrThrow(registryKey);
        for (R r : registry) {
            Identifier name = registry.getKey(r);
            if (!assets.containsKey(name)) {
                List<R> chain = ExtendsChain.resolve(name,
                        key -> {
                            R entry = registry.getValue(ResourceKey.create(registryKey, key));
                            if (entry instanceof IAsset asset) {
                                asset.setRegistryName(key);
                            }
                            return entry;
                        },
                        entry -> entry instanceof Extendable ext ? ext.getExtends() : Optional.empty());
                T t = assetConstructor.apply(access, chain);
                assets.putIfAbsent(name, t);
            }
        }
    }

    public Iterable<T> getIterable() {
        return assets.values();
    }

    public int getNumAssets(CommonLevelAccessor level) {
        return level.registryAccess().lookupOrThrow(registryKey).size();
    }

    public void reset() {
        assets.clear();
    }
}

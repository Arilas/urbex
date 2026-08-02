package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.lost.regassets.IAsset;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.CommonLevelAccessor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class RegistryAssetRegistry<T, R> {

    private final Map<Identifier, T> assets = new HashMap<>();
    private final ResourceKey<Registry<R>> registryKey;
    private final Function<R, T> assetConstructor;

    public RegistryAssetRegistry(ResourceKey<Registry<R>> registryKey, Function<R, T> assetConstructor) {
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
                Registry<R> registry = level.registryAccess().lookupOrThrow(registryKey);
                R value = registry.getValueOrThrow(ResourceKey.create(registryKey, name));
                if (value instanceof IAsset asset) {
                    asset.setRegistryName(name);
                }
                t = assetConstructor.apply(value);
            } catch (Exception e) {
                throw new RuntimeException("Error getting resource " + name + "!", e);
            }
            assets.put(name, t);
        }
        if (t instanceof CityStyle cs) {
            cs.init(level);
        }
        return t;
    }

    public void loadAll(CommonLevelAccessor level) {
        if (level == null) {
            return;
        }
        Registry<R> registry = level.registryAccess().lookupOrThrow(registryKey);
        for (R r : registry) {
            Identifier name = registry.getKey(r);
            if (!assets.containsKey(name)) {
                if (r instanceof IAsset asset) {
                    asset.setRegistryName(name);
                }
                T t = assetConstructor.apply(r);
                assets.put(name, t);
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

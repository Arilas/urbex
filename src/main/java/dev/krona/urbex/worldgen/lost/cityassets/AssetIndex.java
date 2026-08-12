package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.Identifier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every compiled asset of one kind, finished before anyone can look anything up.
 *
 * <p>This replaces {@code RegistryAssetRegistry}'s lookup half. The difference that matters is not
 * immutability for its own sake - it is that <strong>there is no compile-on-miss</strong>. Every
 * lookup either finds a finished asset or finds nothing, so a datapack error cannot first surface
 * from a worldgen worker halfway through a chunk (issue #128).</p>
 *
 * <p><strong>No level argument.</strong> The old registries took a {@code CommonLevelAccessor} on
 * every {@code get} purely to reach {@code level.registryAccess()} and compile from it. An index has
 * already been compiled, so it needs nothing from the caller but a name - and losing that parameter
 * is what stops a lookup from being a place where compilation can happen.</p>
 */
public final class AssetIndex<T> {

    /** Named in every message this class raises, so a failure says which registry it is about. */
    private final String registry;
    private final Map<Identifier, T> byId;
    /**
     * Ids already warned about by {@link #getOrWarn}, so a name missing on every chunk is reported
     * once. The registries this replaces logged per call.
     */
    private final Set<Identifier> warned = ConcurrentHashMap.newKeySet();

    public AssetIndex(String registry, Map<Identifier, T> byId) {
        this.registry = registry;
        this.byId = Map.copyOf(byId);
    }

    public static <T> AssetIndex<T> empty(String registry) {
        return new AssetIndex<>(registry, Map.of());
    }

    @Nullable
    public T get(@Nullable String name) {
        return name == null ? null : get(DataTools.fromName(name));
    }

    @Nullable
    public T get(@Nullable Identifier name) {
        return name == null ? null : byId.get(name);
    }

    /**
     * @throws RuntimeException naming the registry and the id. Reachable only for a reference that
     *                          compiled but names something absent - the compiler validates every
     *                          reference it resolves, so this is a backstop rather than the primary
     *                          report.
     */
    @Nonnull
    public T getOrThrow(@Nullable String name) {
        if (name == null) {
            throw new RuntimeException("Invalid name given to " + registry + " getOrThrow!");
        }
        T found = get(DataTools.fromName(name));
        if (found == null) {
            throw new RuntimeException("Can't find '" + name + "' in " + registry + "!");
        }
        return found;
    }

    /** For the optional references: absent is legal, and says so once rather than once per chunk. */
    @Nullable
    public T getOrWarn(@Nullable String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        Identifier id = DataTools.fromName(name);
        T found = get(id);
        if (found == null && warned.add(id)) {
            Urbex.LOGGER.warn("Cannot find '{}' in {}!", name, registry);
        }
        return found;
    }

    public Collection<T> all() {
        return byId.values();
    }

    public Set<Identifier> ids() {
        return byId.keySet();
    }

    public int size() {
        return byId.size();
    }

    public String registry() {
        return registry;
    }
}

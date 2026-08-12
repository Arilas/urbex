package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.worldgen.lost.regassets.Extendable;
import dev.krona.urbex.worldgen.lost.regassets.IAsset;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * One registry's worth of compilation: resolve every registered entry's {@code extends} chain and
 * build the runtime asset from it, recording what fails rather than stopping at it.
 *
 * <p>Separate from {@link AssetIndex} because compiling and looking up are different jobs with
 * different lifetimes - this runs once, while the world loads, and then has nothing left to do. That
 * separation is the point of the whole issue: the class it replaces did both, so a lookup was also a
 * place where compilation could happen.</p>
 */
final class AssetStage {

    private AssetStage() {
    }

    /**
     * Compiles every entry of one registry.
     *
     * @param compile builds the runtime asset from its chain, root-first. It may read any index
     *                already built - {@link AssetCompiler} calls the stages in dependency order so
     *                that "already built" is a fact rather than a hope.
     */
    static <R, T> AssetIndex<T> compileAll(RegistryAccess access,
                                           ResourceKey<Registry<R>> registryKey,
                                           BiFunction<Identifier, List<R>, T> compile,
                                           AssetDiagnostics diagnostics) {
        String name = registryKey.registry().toString();
        Registry<R> registry = access.lookupOrThrow(registryKey);
        Map<Identifier, T> compiled = new HashMap<>();
        for (R entry : registry) {
            Identifier id = registry.getKey(entry);
            try {
                compiled.put(id, compile.apply(id, chainOf(registry, registryKey, id)));
            } catch (Exception e) {
                diagnostics.record(name, id, e);
            }
        }
        return new AssetIndex<>(name, compiled);
    }

    /**
     * The {@code extends} chain of one entry, root-first.
     *
     * <p>{@code setRegistryName} is still called on each link, because a compiled asset reads its own
     * id off the last link of its chain. Carrying identity beside the decoded value instead of
     * writing it into it is 128d's job; doing it here would mean touching all twelve asset
     * constructors in the same PR that moves where they live.</p>
     */
    private static <R> List<R> chainOf(Registry<R> registry, ResourceKey<Registry<R>> registryKey,
                                       Identifier id) {
        return ExtendsChain.resolve(id,
                key -> {
                    R entry = registry.getValue(ResourceKey.create(registryKey, key));
                    if (entry instanceof IAsset asset) {
                        asset.setRegistryName(key);
                    }
                    return entry;
                },
                entry -> entry instanceof Extendable ext ? ext.getExtends() : Optional.empty());
    }
}

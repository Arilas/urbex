package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Versioned;
import dev.krona.urbex.worldgen.lost.regassets.Extendable;
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
     * <p>Nothing is written back into the decoded entries. Each link used to have its registry id
     * assigned onto it here, so that the compiled asset could read its own name off the last link -
     * which meant compilation mutated the authored model, and a registry entry's identity depended on
     * something having walked a chain that reached it. The id travels as a parameter now: the caller
     * already has it, and hands it to the asset constructor beside the chain (issue #128).</p>
     */
    private static <R> List<R> chainOf(Registry<R> registry, ResourceKey<Registry<R>> registryKey,
                                       Identifier id) {
        List<R> chain = ExtendsChain.resolve(id,
                key -> registry.getValue(ResourceKey.create(registryKey, key)),
                entry -> entry instanceof Extendable ext ? ext.getExtends() : Optional.empty());
        refuseCrossVersionChain(id, chain);
        return chain;
    }

    /**
     * {@code VER.005}, {@code MERGE.010}: an {@code extends} chain may not cross format versions, in
     * either direction.
     * <p>
     * <b>Guarded on the entry type, not on the registry.</b> This method runs for all thirteen
     * registries because {@link #chainOf} is shared, and it does nothing for twelve of them: only an
     * entry that declares which format version it was written in - a {@link Versioned.Asset}, which
     * today is the {@code palettes} registry alone - can cross one. That is also what {@code VER.040}
     * asks for from the next registry to adopt a version 2: "a registry adopting version 2 follows
     * VER.001 through VER.004 unchanged", and the rule that keeps its chains from crossing should not
     * have to be written again with a different registry key in it.
     * <p>
     * <b>Why the two formats may not meet in one chain</b> is {@code MERGE.010}'s own {@code > Why}: the
     * alternative was an invariant that a version 1 palette and its version 2 translation compile to
     * identical forms, maintained forever, which would constrain version 2 to what version 1 could
     * already express. A style's {@code randompalettes} drawing one of each is a different mechanism and
     * stays legal ({@code VER.006}), because that composition operates on compiled palettes.
     * <p>
     * Thrown rather than returned, like every other failure of a chain: {@link #compileAll} records it
     * against the asset it was compiling and carries on with the rest of the registry, so a pack with
     * two such chains reports both.
     *
     * @param id    the leaf, which is the only link whose id the chain itself does not carry
     * @param chain the chain root-first, as {@link ExtendsChain} returned it
     */
    static <R> void refuseCrossVersionChain(Identifier id, List<R> chain) {
        Identifier childId = id;
        for (int i = chain.size() - 1; i > 0; i--) {
            R child = chain.get(i);
            // Every link but the root declares an 'extends', because that is what ExtendsChain followed
            // to reach the next one - so this stops rather than guessing, and never casts blindly. A
            // link whose id cannot be read is a link this cannot name, and DIAG.900 requires the message
            // to name both assets; a ClassCastException recorded against the asset would name neither.
            if (!(child instanceof Extendable extendable) || extendable.getExtends().isEmpty()) {
                return;
            }
            Identifier parentId = extendable.getExtends().orElseThrow();
            // 'continue', not 'return': an unversioned link is a pair this rule has nothing to say
            // about, not the end of the chain. Unreachable while palettes are the only versioned
            // registry, and the javadoc above promises this guard to the next one - a registry midway
            // through adopting a version would otherwise stop being checked at its first old entry.
            if (child instanceof Versioned.Asset childVersion
                    && chain.get(i - 1) instanceof Versioned.Asset parentVersion
                    && childVersion.formatVersion() != parentVersion.formatVersion()) {
                throw new IllegalStateException(Diag.DIAG_038.message("'" + childId + "'",
                        childVersion.formatVersion(), "'" + parentId + "'",
                        parentVersion.formatVersion()));
            }
            childId = parentId;
        }
    }
}

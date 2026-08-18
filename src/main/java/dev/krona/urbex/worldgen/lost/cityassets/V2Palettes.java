package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.DefinitionIndex;
import dev.krona.urbex.format.palette.Exclusion;
import dev.krona.urbex.format.palette.NodeResolver;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.format.palette.V2Chain;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where a registered palette's format version chooses its compiler.
 *
 * <p>{@code VER.003} makes version selection happen before decoding, and {@code PaletteAssetDefinition}
 * is the dispatcher that does it. This is the same fork one stage later: the chain is decoded, its
 * version is known, and the two formats compile through code that shares nothing — which is the point
 * of {@code VER.005}'s independence and is why there is no common interface between them here either.
 *
 * <h2>Version 2 is stages 2 to 8 of {@code LOAD.001}, run here</h2>
 *
 * <p>{@link V2Chain#merge} is stage 2, {@link NodeResolver#resolve} stage 3, and
 * {@link CompiledV2Palette#compile} stages 4 to 8. {@code LOAD.002} says this happens "once per world
 * load, on the loading thread, against the registries of the world being loaded", and that is exactly
 * where {@link AssetCompiler} runs.
 *
 * <h2>Failure is a diagnostic naming the asset, not an exception with no file in it</h2>
 *
 * <p>{@code LOAD.004}: "A palette that fails to compile produces a diagnostic naming the asset and does
 * not abort the compilation of other assets; all diagnostics are reported together." The collected
 * {@link Diagnostics} is turned into one exception per palette, which is what
 * {@link AssetStage#compileAll} records against that asset before carrying on to the next — so a pack
 * with four broken palettes produces one report rather than four world loads.
 */
final class V2Palettes {

    private V2Palettes() {
    }

    /**
     * What the version 2 compiler needs from the world, gathered once.
     *
     * <p>{@code LOAD.003}: every block string resolves "against a block registry handed to the compiler
     * by its caller, never one it fetches", and {@code TRAIT.093} says the same of trait validation.
     * Both are in here, built once per world load rather than once per palette, because
     * {@link TraitContext.TagEpoch} snapshots the tags and two palettes of one load must not see two
     * epochs — {@code MODEL.052} compiles a tag "against the tag epoch the palette is compiled under",
     * and a {@code /reload} landing between two palettes would otherwise give them different ones.
     */
    record Context(Exclusion.Presence presence, TraitContext traits, DefinitionIndex definitions) {
    }

    /**
     * The presence and registries this world compiles version 2 palettes against.
     *
     * @param conditions the compiled conditions index, which {@code TRAIT.021} and {@code TRAIT.031}
     *                   check their pools against. Compiled before palettes for that reason.
     */
    static Context context(RegistryAccess access, HolderLookup<Block> blockLookup,
                           AssetIndex<Condition> conditions) {
        Set<Identifier> conditionIds = new LinkedHashSet<>();
        conditions.all().forEach(condition -> conditionIds.add(condition.getId()));
        return new Context(Exclusion.installed(blockLookup, packNamespaces(access)),
                TraitContext.withConditions(blockLookup, conditionIds), definitions(access));
    }

    /**
     * The {@code definitions} registry, read off the world being loaded ({@code REF.010}).
     *
     * <p>Read here rather than fetched where a pointer is resolved, for {@code LOAD.003}'s measured
     * reason — "which registry answered depended on whether the server field was populated yet" — and
     * gathered once per load beside the block presence and the tag epoch, because a definitions asset
     * is part of the same snapshot those two are.</p>
     *
     * <p>Decoded assets rather than compiled ones, which is what {@link DefinitionIndex} holds and why:
     * a definitions asset carries its own {@code $imports} ({@code REF.018}) and a pointer written
     * inside it expands against those, not against the file that points at it ({@code REF.086}). There
     * is nothing to compile before that expansion happens.</p>
     */
    private static DefinitionIndex definitions(RegistryAccess access) {
        Map<Identifier, DefinitionAssetDefinition> byId = new LinkedHashMap<>();
        // lookup rather than lookupOrThrow: an empty index and an unregistered registry are the same
        // answer here - no definitions asset is loaded, so every qualified $ref fails with DIAG.030
        // naming the tier it searched. Throwing instead would turn a RegistryAccess assembled without
        // this registry into a crash with no asset in it, which is what LOAD.004 exists to avoid.
        access.lookup(CustomRegistries.DEFINITIONS_REGISTRY_KEY).ifPresent(registry ->
                registry.listElements().forEach(
                        holder -> byId.put(holder.key().identifier(), holder.value())));
        return new DefinitionIndex(byId);
    }

    /**
     * Every namespace this world's datapacks register palette assets in.
     *
     * <p>{@code WEIGHT.023}'s second condition asks whether "anything loaded registers assets in this
     * namespace", and {@link Exclusion#installed} documents the answer as "the closest thing to 'which
     * packs are installed' a datapack-only pack can be seen by". Read off the palettes registry rather
     * than listed, for the reason {@code docs/format/README.md} §1 gives: a listed set is a second copy
     * of a fact the repository already states.
     */
    private static Set<String> packNamespaces(RegistryAccess access) {
        Set<String> namespaces = new LinkedHashSet<>();
        access.lookupOrThrow(Registries.BLOCK).listElementIds()
                .forEach(key -> namespaces.add(key.identifier().getNamespace()));
        access.lookupOrThrow(CustomRegistries.PALETTE_REGISTRY_KEY)
                .registryKeySet().forEach(key -> namespaces.add(key.identifier().getNamespace()));
        return namespaces;
    }

    /**
     * One registered palette, compiled by the rules of the version its chain declares.
     *
     * <p>The chain is all of one version already: {@code VER.005}/{@code MERGE.010} refuse a chain that
     * crosses versions, enforced in {@link AssetStage} where the chain is walked. So testing the leaf is
     * testing all of it, and the cast below cannot fail for a reason a pack author can cause.</p>
     */
    static Palette compile(Identifier id, HolderLookup<Block> blockLookup,
                           @Nullable AssetIndex<Variant> variants,
                           List<PaletteAssetDefinition> chainRootFirst, Context context) {
        if (chainRootFirst.isEmpty()) {
            throw new IllegalArgumentException("an extends chain holds at least the file it is for");
        }
        if (chainRootFirst.getLast().formatVersion() == PaletteV2Definition.FORMAT_VERSION) {
            return Palette.version2(id, compileV2(id, "'" + id + "'", chainRootFirst, context));
        }
        return new Palette(id, blockLookup, variants, version1(chainRootFirst));
    }

    /**
     * Stages 2 to 8 over one version 2 chain, or one exception carrying every diagnostic.
     *
     * @param asset what {@code 08-errors.md} §2 puts in the {@code <asset>} slot — the registry id for a
     *              registered palette, the owner for an inline one ({@code DIAG.902})
     */
    static CompiledV2Palette compileV2(Identifier id, String asset,
                                       List<PaletteAssetDefinition> chainRootFirst, Context context) {
        List<PaletteV2Definition> files = new ArrayList<>(chainRootFirst.size());
        for (PaletteAssetDefinition link : chainRootFirst) {
            files.add((PaletteV2Definition) link);
        }
        Diagnostics diagnostics = new Diagnostics();
        // The definitions registry is the world's; the sibling-palette map is still empty, so a
        // fragment pointer into another *palette* (REF.043) fails to resolve here and says so by name.
        // That half needs the decoded chains of every other palette, which this stage does not hold.
        // The registry half is wired, because without it VER's translation table has no destination:
        // "variant" becomes a $ref into 'definitions', so every converted pack that used a variant
        // refused to load - four of the bundled pack's thirty palettes, naming DIAG.030.
        Optional<CompiledV2Palette> compiled = V2Chain
                .merge(files, Optional.of(id), diagnostics)
                .flatMap(merged -> NodeResolver.resolve(merged, context.definitions(), Map.of(),
                        diagnostics))
                .flatMap(resolved -> CompiledV2Palette.compile(resolved, context.presence(),
                        context.traits(), asset, diagnostics));
        if (compiled.isPresent() && !diagnostics.hasFatal()) {
            return compiled.orElseThrow();
        }
        // Thrown rather than returned, because AssetStage records a thrown exception against the asset
        // it was compiling and carries on with the rest of the registry - which is LOAD.004's "does not
        // abort the compilation of other assets" using the mechanism the compiler already has.
        throw new IllegalStateException(diagnostics.asError()
                .orElse("the palette did not compile and no diagnostic said why"));
    }

    /** A chain already known to be version 1, as the type its compiler takes. */
    private static List<PaletteDefinition> version1(List<PaletteAssetDefinition> chainRootFirst) {
        List<PaletteDefinition> version1 = new ArrayList<>(chainRootFirst.size());
        for (PaletteAssetDefinition link : chainRootFirst) {
            version1.add((PaletteDefinition) link);
        }
        return version1;
    }
}

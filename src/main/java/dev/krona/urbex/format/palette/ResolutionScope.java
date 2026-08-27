package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a pointer needs to be answered: the document it was written in, the assets it may reach,
 * and what the entry it appears in inherited.
 * <p>
 * <b>The document, not the file under resolution.</b> The brief for this task named a
 * {@code Map<String, RawNode> localDefs} component. It is a {@link Document} instead because a pointer
 * inside a node that <em>arrived</em> through another pointer resolves against its own document's
 * names: {@code REF.086} makes {@code $imports} file-local, {@code REF.011} makes a bare name a
 * definition of "this file's {@code $defs}", and {@code REF.015} exists precisely because a registry
 * definition has no file whose {@code $defs} to resolve against - "resolving against the referring
 * file's would make a shared definition mean different things to different callers". Carrying the
 * document with the node is what makes that true rather than aspirational.
 * <p>
 * {@code inherited} is the one component that is per-entry rather than per-document, because
 * {@code REF.061} says so: "{@code $super} is scoped to the entry it appears in". The file-level pass
 * builds one scope per entry with {@link #forEntry}.
 *
 * @param document  the document the node being resolved was written in
 * @param registry  the {@code definitions} registry ({@code REF.010})
 * @param palettes  every decoded version 2 palette, which is what a fragment pointer reaches into.
 *                  {@code LOAD.025} is why this is a map of decoded documents and not a loader
 *                  callback: "stage 2 runs for every palette before stage 3 runs for any"
 * @param inherited what this entry inherited through {@code extends}, which {@code $super} names
 *                  ({@code REF.060}). Empty for an entry no earlier file of the chain declared, and
 *                  for every entry of a file that declares no {@code extends} - the two cases
 *                  {@code REF.062} refuses and {@code DIAG.036} tells apart
 */
public record ResolutionScope(Document document, DefinitionIndex registry,
                              Map<Identifier, PaletteV2Definition> palettes,
                              Optional<MergedEntry> inherited) {

    /**
     * The three things {@code REF.086} and {@code REF.011} make file-local, plus the file's identity.
     *
     * @param id        the asset this document is, or empty for the document currently being loaded -
     *                  which a {@link com.mojang.serialization.Codec} never knows and this stage is only
     *                  told by its caller. See {@code DIAG.902} and the gap {@link Diagnostics} records
     * @param extendsId {@code extends}, needed only to tell {@code DIAG.036}'s two cases apart: a file
     *                  that declares no {@code extends} inherits nothing at all, and a file that
     *                  declares one may still inherit nothing for this particular entry. Per-file like
     *                  {@code imports}, and for the same reason - the entry may have been written
     *                  further up the chain than the leaf, and the sentence has to be true of the file
     *                  that wrote it
     * @param imports   {@code $imports} of the file at the end of this document's chain, alias name to
     *                  prefix ({@code REF.080}). File-local by {@code REF.086}, which is why an entry
     *                  declared further up the chain reads this document through {@link #asWrittenBy}
     *                  instead
     * @param defs      {@code $defs}, merged along the chain ({@code MERGE.003}) and each entry
     *                  carrying the file that wrote it ({@code REF.001}, {@code REF.011})
     */
    public record Document(Optional<Identifier> id, Optional<Identifier> extendsId,
                           Map<String, String> imports, Map<String, MergedEntry> defs) {

        public Document(Optional<Identifier> id, Optional<Identifier> extendsId,
                        Map<String, String> imports, Map<String, MergedEntry> defs) {
            this.id = id;
            this.extendsId = extendsId;
            this.imports = Map.copyOf(imports);
            // Insertion order, not Map.copyOf: REF.032 names a cycle "in declaration order, beginning
            // with the node the loader reached first", and the pass that finds one walks these entries
            // in the order this map yields them. Map.copyOf's order is salted once per JVM, so the same
            // palette would name its cycle starting from a different definition on a different run -
            // the same defect Kind.Placement.ordered fixed for a node's placement lists.
            this.defs = Collections.unmodifiableMap(new LinkedHashMap<>(defs));
        }

        /** The palette file being loaded, whose id nothing at this stage knows. */
        public static Document of(PaletteV2Definition file) {
            return new Document(Optional.empty(), file.extendsId(), file.imports(),
                    entries(file));
        }

        /** A palette a pointer reached into, which does have an id. */
        public static Document of(Identifier id, PaletteV2Definition file) {
            return new Document(Optional.of(id), file.extendsId(), file.imports(), entries(file));
        }

        /**
         * One file's {@code $defs}, as entries of a chain of one.
         * <p>
         * A pointer into another palette reads that palette as decoded, so its definitions inherit
         * nothing here. {@code REF.044} says a fragment resolves against the target's document "after
         * its own {@code extends} chain is applied", which this does not yet do: nothing merges the
         * chain of an asset that is only <em>pointed at</em>, because the map of decoded palettes a
         * pointer reaches into holds documents rather than chains. Recorded here rather than silently
         * approximated; it is the loader stage's to close, with {@code LOAD.025}.
         */
        private static Map<String, MergedEntry> entries(PaletteV2Definition file) {
            Map<String, MergedEntry> defs = new LinkedHashMap<>();
            file.defs().forEach((name, node) -> defs.put(name, MergedEntry.of(node, file)));
            return defs;
        }

        /**
         * The same document, read as the file that wrote {@code entry} wrote it.
         * <p>
         * Two components change and two do not. {@code $imports} and {@code extends} are facts of the
         * file that wrote the entry ({@code REF.086}, and {@code DIAG.036}'s choice of sentence); the
         * identity and the merged {@code $defs} are facts of the chain, and must not. The merged palette
         * is one document with one set of names, and giving an ancestor's entry its own document would
         * give one {@code $defs} name two keys in {@code REF.032}'s graph.
         */
        public Document asWrittenBy(MergedEntry entry) {
            return new Document(id, entry.extendsId(), entry.imports(), defs);
        }

        /**
         * A definitions asset, which carries {@code $imports} and no {@code $defs} ({@code REF.018}).
         */
        public static Document of(Identifier id, DefinitionAssetDefinition asset) {
            return new Document(Optional.of(id), asset.extendsId(), asset.imports(), Map.of());
        }

        /** What this document is called in a diagnostic's leading {@code <asset>} slot. */
        public String describe() {
            return id.map(Identifier::toString).orElse(Diagnostics.DECODING_LOCATION);
        }

        /**
         * The name of a {@code $defs} entry of this document in the reference graph.
         * <p>
         * A bare name for the document being loaded, so that {@code DIAG.032} prints the cycle the way
         * its catalogue row does - {@code a → b → c → a} - and a qualified one for any other document,
         * because two documents may each call a definition {@code rubble} and a graph that conflated
         * them would report a cycle between two unrelated nodes.
         */
        public String defKey(String name) {
            return id.map(asset -> asset + "#/$defs/" + name).orElse(name);
        }

        /** The name of a marker entry of this document in the reference graph. */
        public String markerKey(Marker marker) {
            return id.map(asset -> asset + "#/palette/" + marker.asString())
                    .orElse("marker " + marker);
        }
    }

    /** The scope for {@code file}, with no other asset reachable - one document, on its own. */
    public static ResolutionScope of(PaletteV2Definition file) {
        return of(file, DefinitionIndex.empty(), Map.of());
    }

    /** The scope for {@code file}, resolving pointers against {@code registry} and {@code palettes}. */
    public static ResolutionScope of(PaletteV2Definition file, DefinitionIndex registry,
                                     Map<Identifier, PaletteV2Definition> palettes) {
        return new ResolutionScope(Document.of(file), registry, Map.copyOf(palettes),
                Optional.empty());
    }

    /** The same reachable assets, reading a node of {@code document} instead. */
    public ResolutionScope in(Document document) {
        return new ResolutionScope(document, registry, palettes, Optional.empty());
    }

    /** The same document, resolving {@code $super} against {@code inherited} ({@code REF.061}). */
    public ResolutionScope withInherited(Optional<MergedEntry> inherited) {
        return new ResolutionScope(document, registry, palettes, inherited);
    }

    /**
     * The scope one entry of this document resolves in.
     * <p>
     * Everything a merged palette keeps per entry, applied together: the {@code $imports} and the
     * {@code extends} of the file that wrote it ({@code REF.086}, and {@code DIAG.036}'s two sentences)
     * and the layer it replaced, which its {@code $super} names ({@code REF.060}, {@code REF.061}). Every
     * caller that reaches an entry - the file-level pass, a bare-name pointer, {@code $super} itself -
     * goes through here, so they cannot be applied in one place and forgotten in another.
     */
    public ResolutionScope forEntry(MergedEntry entry) {
        return new ResolutionScope(document.asWrittenBy(entry), registry, palettes,
                entry.inherited());
    }

    public ResolutionScope(Document document, DefinitionIndex registry,
                           Map<Identifier, PaletteV2Definition> palettes,
                           Optional<MergedEntry> inherited) {
        this.document = document;
        this.registry = registry;
        this.palettes = Map.copyOf(palettes);
        this.inherited = inherited;
    }
}

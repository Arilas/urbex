package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import net.minecraft.resources.Identifier;

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
 * builds one scope per entry with {@link #withInherited}.
 *
 * @param document  the document the node being resolved was written in
 * @param registry  the {@code definitions} registry ({@code REF.010})
 * @param palettes  every decoded version 2 palette, which is what a fragment pointer reaches into.
 *                  {@code LOAD.025} is why this is a map of decoded documents and not a loader
 *                  callback: "stage 2 runs for every palette before stage 3 runs for any"
 * @param inherited what this entry inherited through {@code extends}, which {@code $super} names
 *                  ({@code REF.060}). Always empty until the {@code extends} chain is merged - a file
 *                  that declares no {@code extends} inherits nothing, and {@code REF.062} refuses
 *                  {@code $super} in it
 */
public record ResolutionScope(Document document, DefinitionIndex registry,
                              Map<Identifier, PaletteV2Definition> palettes,
                              Optional<RawNode> inherited) {

    /**
     * The three things {@code REF.086} and {@code REF.011} make file-local, plus the file's identity.
     *
     * @param id        the asset this document is, or empty for the document currently being loaded -
     *                  which a {@link com.mojang.serialization.Codec} never knows and this stage is only
     *                  told by its caller. See {@code DIAG.902} and the gap {@link Diagnostics} records
     * @param extendsId {@code extends}, needed only to tell {@code DIAG.036}'s two cases apart: a file
     *                  that declares no {@code extends} inherits nothing at all, and a file that
     *                  declares one may still inherit nothing for this particular entry
     * @param imports   {@code $imports}, alias name to prefix ({@code REF.080}), file-local by
     *                  {@code REF.086}
     * @param defs      {@code $defs}, as decoded ({@code REF.001})
     */
    public record Document(Optional<Identifier> id, Optional<Identifier> extendsId,
                           Map<String, String> imports, Map<String, RawNode> defs) {

        public Document(Optional<Identifier> id, Optional<Identifier> extendsId,
                        Map<String, String> imports, Map<String, RawNode> defs) {
            this.id = id;
            this.extendsId = extendsId;
            this.imports = Map.copyOf(imports);
            this.defs = Map.copyOf(defs);
        }

        /** The palette file being loaded, whose id nothing at this stage knows. */
        public static Document of(PaletteV2Definition file) {
            return new Document(Optional.empty(), file.extendsId(), file.imports(), file.defs());
        }

        /** A palette a pointer reached into, which does have an id. */
        public static Document of(Identifier id, PaletteV2Definition file) {
            return new Document(Optional.of(id), file.extendsId(), file.imports(), file.defs());
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
    public ResolutionScope withInherited(Optional<RawNode> inherited) {
        return new ResolutionScope(document, registry, palettes, inherited);
    }

    public ResolutionScope(Document document, DefinitionIndex registry,
                           Map<Identifier, PaletteV2Definition> palettes,
                           Optional<RawNode> inherited) {
        this.document = document;
        this.registry = registry;
        this.palettes = Map.copyOf(palettes);
        this.inherited = inherited;
    }
}

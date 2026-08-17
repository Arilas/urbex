package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 2 of {@code LOAD.001} for a version 2 palette: folds an {@code extends} chain into the one
 * document {@link NodeResolver} then links.
 * <p>
 * <b>Replacement is the only implicit behaviour</b> ({@code MERGE.004}). {@code palette} merges by
 * marker and {@code $defs} by definition name ({@code MERGE.002}, {@code MERGE.003}), each key taken
 * from the last file of the chain that declares it, whole. Nothing is deep-merged and nothing is
 * appended: a marker a child repaints does not keep its ancestor's traits ({@code MERGE.008}), which
 * costs nothing to implement here because replacement is what a {@link Map#put} does, and is tested so
 * that it stays free. To build on what an entry inherits an author writes {@code $super}
 * ({@code MERGE.005}), and {@link MergedEntry} is what makes that value reachable.
 * <p>
 * <b>The result is one document, not a list of them.</b> A bare name in an ancestor's node resolves
 * against the <em>merged</em> {@code $defs}, which is what {@code MERGE.006} requires - "redefining a
 * definition repaints every marker that references it, including markers this file does not mention" -
 * and it is the mechanism that makes "the same layout in different materials" a short file. The two
 * things that stay per-file are {@code $imports} ({@code REF.086}) and what each entry inherits
 * ({@code REF.060}); both travel on the entry, for the reasons {@link MergedEntry} records.
 * <p>
 * <b>What this does not do.</b> A chain that <em>cycles</em> cannot be expressed as the list this takes:
 * it is refused where the chain is walked, by
 * {@link dev.krona.urbex.worldgen.lost.cityassets.ExtendsChain}, before any of these files are read.
 * What {@code REF.033} adds - "a cycle through {@code $ref} and {@code extends} together is one cycle" -
 * is a cycle that exists only <em>after</em> this merge, because two files that are each acyclic can
 * reference each other's entries once their {@code $defs} are one map. That one is found by
 * {@link NodeResolver}'s stack, in the graph this document produces, and reported as {@code DIAG.032}
 * naming every node in it.
 */
public final class V2Chain {

    private V2Chain() {
    }

    /**
     * A resolved {@code extends} chain: the document its entries resolve in, and its markers.
     * <p>
     * The brief for this task named {@code MergedPalette(Map<String, RawNode> defs,
     * Map<Marker, RawNode> palette)}. Both halves grew a component for a rule the plain shape cannot
     * carry: an entry is a {@link MergedEntry} rather than a {@link RawNode} because of {@code REF.060}
     * and {@code REF.086}, and {@code defs} lives on the {@link ResolutionScope.Document} because that
     * is the value {@code $defs} <em>is</em> to everything downstream - the file's own names, merged.
     * Keeping a second copy beside the document would be two answers to "what does {@code rubble} mean
     * here".
     *
     * @param document what a pointer written in any entry of this chain is answered against
     * @param palette  the merged markers ({@code MERGE.002}), in the order the chain declared them
     */
    public record MergedPalette(ResolutionScope.Document document,
                                Map<Marker, MergedEntry> palette) {

        public MergedPalette(ResolutionScope.Document document, Map<Marker, MergedEntry> palette) {
            this.document = document;
            // Declaration order, for the reason ResolutionScope.Document's own constructor records:
            // the resolution pass walks these in the order this map yields them, and DIAG.032 and
            // DIAG.903 both describe an order. Map.copyOf's is salted per JVM.
            this.palette = Collections.unmodifiableMap(new LinkedHashMap<>(palette));
        }

        /** The merged {@code $defs} ({@code MERGE.003}), which the document carries. */
        public Map<String, MergedEntry> defs() {
            return document.defs();
        }
    }

    /** Folds a chain of one - a file with no {@code extends}, whose id nothing at this stage knows. */
    public static Optional<MergedPalette> merge(PaletteV2Definition file, Diagnostics diagnostics) {
        return merge(List.of(file), Optional.empty(), diagnostics);
    }

    /**
     * Folds {@code rootFirst} into one document, or refuses the chain.
     * <p>
     * {@code MERGE.001}: "The chain is resolved root-first, and each file is applied over the
     * accumulated result." The only refusal here is {@code MERGE.007}/{@code DIAG.002}: {@code palette}
     * is required of the <em>chain</em> and not of each file, because a file that only repaints a
     * definition its ancestors' markers reference declares no markers of its own ({@code MERGE.006}).
     * <p>
     * A present-but-empty {@code palette} declares one. That is deliberate and it matches version 1's
     * fold, which asks whether the key was written rather than whether it contributed: an empty
     * {@code palette} is a file saying "these are the markers, and there are none", which
     * {@code MODEL.081} then has nothing to complain about, where an <em>absent</em> one is a file that
     * did not answer the question at all.
     *
     * @param owner the asset the chain belongs to, when the caller knows it. Empty for a document being
     *              decoded on its own, where {@link Diagnostics#DECODING_LOCATION} stands in the
     *              {@code <asset>} slot and {@code DIAG.032} prints a local definition's name bare -
     *              see {@link ResolutionScope.Document#defKey}
     * @return the merged document, or empty when a diagnostic refused the chain
     */
    public static Optional<MergedPalette> merge(List<PaletteV2Definition> rootFirst,
                                                Optional<Identifier> owner,
                                                Diagnostics diagnostics) {
        if (rootFirst.isEmpty()) {
            throw new IllegalArgumentException("an extends chain holds at least the file it is for");
        }
        Map<String, MergedEntry> defs = new LinkedHashMap<>();
        Map<Marker, MergedEntry> palette = new LinkedHashMap<>();
        boolean anyPalette = false;
        for (PaletteV2Definition file : rootFirst) {
            for (Map.Entry<String, RawNode> def : file.defs().entrySet()) {
                defs.put(def.getKey(), layer(defs.get(def.getKey()), def.getValue(), file));
            }
            if (file.palette().isEmpty()) {
                continue;
            }
            anyPalette = true;
            for (Map.Entry<Marker, RawNode> marker : file.palette().orElseThrow().entrySet()) {
                palette.put(marker.getKey(),
                        layer(palette.get(marker.getKey()), marker.getValue(), file));
            }
        }

        PaletteV2Definition leaf = rootFirst.get(rootFirst.size() - 1);
        ResolutionScope.Document document =
                new ResolutionScope.Document(owner, leaf.extendsId(), leaf.imports(), defs);
        if (!anyPalette) {
            // MERGE.007. Reported here rather than at decode because it is a property of the chain:
            // PaletteV2Definition.palette is optional for exactly this reason.
            diagnostics.error(Diag.DIAG_002, document.describe());
            return Optional.empty();
        }
        return Optional.of(new MergedPalette(document, palette));
    }

    /**
     * {@code MERGE.004}: {@code node} replaces {@code inherited}, which stays reachable as
     * {@code $super}.
     */
    private static MergedEntry layer(MergedEntry inherited, RawNode node, PaletteV2Definition file) {
        return MergedEntry.over(node, file, Optional.ofNullable(inherited));
    }
}

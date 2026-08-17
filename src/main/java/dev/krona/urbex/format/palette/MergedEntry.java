package dev.krona.urbex.format.palette;

import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Optional;

/**
 * One entry of a merged palette: the node that won, the file that wrote it, and what it replaced.
 * <p>
 * A {@code $defs} name or a marker may be declared by several files of one {@code extends} chain, and
 * {@code MERGE.004} says the last one <b>replaces</b> the rest rather than merging into them. So the
 * winner is one node - but two things about the losers survive it, and both are rules:
 * <ul>
 *   <li><b>{@code inherited}</b> - {@code REF.060} makes {@code $super} name "what would have stood at
 *       this marker or definition name had this file not declared it", so the layer underneath the
 *       winner has to still be reachable. It is a {@code MergedEntry} in turn, and not a bare
 *       {@link RawNode}, because an ancestor's declaration may itself write {@code $super}: in a chain
 *       of three where all three declare {@code 'X'}, the leaf's {@code $super} is the middle file's
 *       node and <em>its</em> {@code $super} is the root's.</li>
 *   <li><b>{@code imports}</b> - {@code REF.086} makes {@code $imports} file-local and explicitly not
 *       inherited, so an alias in this node means what the file that <em>wrote</em> this node declared,
 *       which is not necessarily the file at the end of the chain. A merged palette whose entries all
 *       expanded aliases against the leaf's {@code $imports} would refuse an ancestor's own pointer
 *       ({@code DIAG.039}) for a file the author never edited.</li>
 *   <li><b>{@code extendsId}</b> - the same shape, one scalar rather than a map, and it exists because a
 *       message was false without it: {@code DIAG.036} chooses between "this file declares no extends"
 *       and "nothing in its extends chain declares it" by asking whether the file declared one, and read
 *       off the merged document the answer was always the leaf's.</li>
 * </ul>
 * <p>
 * {@code $defs} carries the same three for the same reasons, which is why this is the value type of
 * {@link ResolutionScope.Document#defs()} as well as of {@link V2Chain.MergedPalette#palette()}: a
 * definition declared by the root and pointed at by the leaf resolves its own aliases and its own
 * {@code $super}.
 * <p>
 * What it carries is the file's <em>facts</em>, deliberately not the file's <em>identity</em>. The merged
 * palette is one document - {@code MERGE.002} merges by marker and the result is a palette, not a list of
 * palettes - and a per-layer document identity would give one entry two names in the reference graph,
 * where {@code REF.032}'s cycle needs exactly one per entry. Naming the ancestor a diagnostic came from
 * is {@code DIAG.902}'s business, and that slot is unfilled at this stage for the leaf too.
 *
 * @param node      the declaration that won, exactly as its file wrote it
 * @param imports   the {@code $imports} of the file that wrote {@code node} ({@code REF.086})
 * @param extendsId the {@code extends} of the file that wrote {@code node}, which is <em>not</em>
 *                  necessarily the leaf's. {@code DIAG.036} has two sentences and picks between them by
 *                  this: "this file declares no extends" is true of an entry the root wrote and false of
 *                  one the leaf wrote, and read off the merged document it was the leaf's answer for
 *                  both. A diagnostic derived from a value is only true if the value is the one the file
 *                  wrote - the same shape as {@code imports} above, one scalar instead of a map
 * @param inherited the layer this one replaced, which {@code $super} names ({@code REF.060}), or empty
 *                  when no earlier file in the chain declared this key - the case {@code REF.062}
 *                  refuses
 */
public record MergedEntry(RawNode node, Map<String, String> imports,
                          Optional<Identifier> extendsId, Optional<MergedEntry> inherited) {

    public MergedEntry(RawNode node, Map<String, String> imports, Optional<Identifier> extendsId,
                       Optional<MergedEntry> inherited) {
        this.node = node;
        this.imports = Map.copyOf(imports);
        this.extendsId = extendsId;
        this.inherited = inherited;
    }

    /**
     * How many layers stand under this one, counting from the root declaration, which is 0.
     * <p>
     * The reference graph needs it: two layers of one entry are two nodes of that graph, and a key that
     * named only the entry would call the second hop of a three-file chain a cycle. Counted from the
     * root so that lengthening a chain does not renumber the layers already in it. Walked rather than
     * stored, because a chain is a handful of files and a stored depth is a second thing that can be
     * wrong.
     */
    public int depth() {
        return inherited.map(under -> under.depth() + 1).orElse(0);
    }

    /** A node from a file that declares no {@code $imports} and no {@code extends}, inheriting nothing. */
    public static MergedEntry of(RawNode node) {
        return new MergedEntry(node, Map.of(), Optional.empty(), Optional.empty());
    }

    /** A node from one decoded file, as an entry of a chain of one. */
    public static MergedEntry of(RawNode node, PaletteV2Definition file) {
        return new MergedEntry(node, file.imports(), file.extendsId(), Optional.empty());
    }

    /** This node, declared by {@code file} over what {@code inherited} holds. */
    static MergedEntry over(RawNode node, PaletteV2Definition file,
                            Optional<MergedEntry> inherited) {
        return new MergedEntry(node, file.imports(), file.extendsId(), inherited);
    }
}

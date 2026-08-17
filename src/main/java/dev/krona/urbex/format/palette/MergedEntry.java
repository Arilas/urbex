package dev.krona.urbex.format.palette;

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
 * </ul>
 * <p>
 * {@code $defs} carries the same pair for the same two reasons, which is why this is the value type of
 * {@link ResolutionScope.Document#defs()} as well as of {@link V2Chain.MergedPalette#palette()}: a
 * definition declared by the root and pointed at by the leaf resolves its own aliases and its own
 * {@code $super}.
 * <p>
 * Deliberately <em>not</em> carrying which file each layer came from. The merged palette is one
 * document - {@code MERGE.002} merges by marker and the result is a palette, not a list of palettes -
 * and a per-layer identity would give one entry two names in the reference graph, where
 * {@code REF.032}'s cycle needs exactly one per entry. What a diagnostic needs about the ancestor is
 * {@code DIAG.902}'s business, and that slot is unfilled at this stage for the leaf too.
 *
 * @param node      the declaration that won, exactly as its file wrote it
 * @param imports   the {@code $imports} of the file that wrote {@code node} ({@code REF.086})
 * @param inherited the layer this one replaced, which {@code $super} names ({@code REF.060}), or empty
 *                  when no earlier file in the chain declared this key - the case {@code REF.062}
 *                  refuses
 */
public record MergedEntry(RawNode node, Map<String, String> imports,
                          Optional<MergedEntry> inherited) {

    public MergedEntry(RawNode node, Map<String, String> imports,
                       Optional<MergedEntry> inherited) {
        this.node = node;
        this.imports = Map.copyOf(imports);
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

    /** A node from a file that declares no {@code $imports} and inherits nothing - a chain of one. */
    public static MergedEntry of(RawNode node) {
        return new MergedEntry(node, Map.of(), Optional.empty());
    }

    /** A node from a file whose {@code $imports} are {@code imports}, inheriting nothing. */
    public static MergedEntry of(RawNode node, Map<String, String> imports) {
        return new MergedEntry(node, imports, Optional.empty());
    }

    /** This node, declared over {@code inherited} by a file whose imports are {@code imports}. */
    static MergedEntry over(RawNode node, Map<String, String> imports,
                            Optional<MergedEntry> inherited) {
        return new MergedEntry(node, imports, inherited);
    }
}

package dev.krona.urbex.format.palette;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code LOAD.050}: a marker's fully resolved form, printed.
 * <p>
 * "The loader can print the fully resolved form of any marker: its kind, its slots with exact shares,
 * every trait with the node it was inherited from, and the reference chain each came through."
 * <p>
 * <b>Why this ships with the compiled form rather than after it.</b> The rule's own {@code > Why}:
 * "indirection is the cost this format pays for reuse, and the two questions it creates - what does this
 * resolve to, and where did that come from - are answerable for free, because everything is already
 * resolved in memory. Without this the format is harder to work with than the one it replaces." Version
 * 1 had one level of indirection and no way to ask either question; version 2 has {@code $ref},
 * {@code $super}, {@code $spread}, {@code extends} and trait inheritance, and shipping them without an
 * answer would be a straight regression in how a pack is debugged.
 * <p>
 * <b>Exact shares, not slot counts.</b> The report reads {@link Apportion#flatten} rather than the 128
 * materialised slots, because a slot count is the answer after {@code WEIGHT.060}'s rounding and the
 * question an author has is what they asked for: {@code 43/128} does not tell them whether they wrote a
 * third or {@code 0.336}. Both are printed - the exact fraction, and the slots it rounded to - so the
 * rounding is visible rather than substituted for the intent.
 */
public final class ResolutionReport {

    private ResolutionReport() {
    }

    /** The report for one marker of a linked palette, as lines. */
    public static String of(Marker marker, ResolvedNode node) {
        List<String> lines = new ArrayList<>();
        lines.add("marker " + marker + " — " + node.kind().key());
        describe(node, "  ", Fraction.ONE, lines);
        return String.join("\n", lines);
    }

    /** The report for every marker of a linked palette, in declaration order. */
    public static String of(NodeResolver.ResolvedPalette palette) {
        List<String> lines = new ArrayList<>();
        palette.palette().forEach((marker, node) -> lines.add(of(marker, node)));
        return String.join("\n", lines);
    }

    private static void describe(ResolvedNode node, String indent, Fraction share,
                                 List<String> lines) {
        switch (node.source()) {
            case ResolvedNode.Source.Block block -> lines.add(indent + share(share) + block.block());
            case ResolvedNode.Source.Tag tag ->
                    lines.add(indent + share(share) + "every block of " + tag.tag());
            case ResolvedNode.Source.Alias alias ->
                    lines.add(indent + share(share) + "whatever " + alias.of() + " resolves to");
            case ResolvedNode.Source.Weighted weighted -> {
                lines.add(indent + share(share) + "weighted, " + weighted.choices().size()
                        + " alternatives:");
                for (Apportion.Leaf leaf : Apportion.flatten(weighted.choices())) {
                    describe(leaf.node(), indent + "  ", share.times(leaf.share()), lines);
                }
            }
            case ResolvedNode.Source.Socket socket -> {
                lines.add(indent + "light_socket, tried floor, wall, ceiling, free:");
                for (Map.Entry<Kind.Placement, List<ResolvedNode.Choice>> list
                        : socket.placements().entrySet()) {
                    lines.add(indent + "  " + list.getKey().key() + ":");
                    for (Apportion.Leaf leaf : Apportion.flatten(list.getValue())) {
                        describe(leaf.node(), indent + "    ", leaf.share(), lines);
                    }
                }
            }
        }
        traits(node, indent + "  ", lines);
    }

    /**
     * {@code LOAD.050}'s trait half, and {@code LOAD.051}'s chain.
     * <p>
     * Every trait says three things: what it is, whether this node wrote it or inherited it and from
     * where ({@code TRAIT.005}), and the pointers the declaring node was reached through. The third is
     * the one that is hard to reconstruct by reading a pack, because a definition can be reached through
     * an {@code $imports} alias, a {@code $super} and a fragment in one pointer.
     */
    private static void traits(ResolvedNode node, String indent, List<String> lines) {
        node.traits().forEach((id, trait) -> {
            StringBuilder line = new StringBuilder(indent + "trait " + id);
            line.append(trait.provenance().inherited()
                    ? " — inherited from " + trait.provenance().declaredAt()
                    : " — declared here");
            if (!trait.provenance().via().isEmpty()) {
                line.append(", via ").append(String.join(" → ", trait.provenance().via()));
            }
            lines.add(line.toString());
            trait.satellites().forEach((field, satellite) -> {
                lines.add(indent + "  " + field + ":");
                describe(satellite, indent + "    ", Fraction.ONE, lines);
            });
        });
    }

    /** A share as the file's own arithmetic and as the slots it rounds to. */
    private static String share(Fraction share) {
        if (share.equals(Fraction.ONE)) {
            return "";
        }
        int slots = (int) Math.round(share.numerator().doubleValue()
                / share.denominator().doubleValue() * Apportion.SLOTS);
        return share.toPlainString() + " (" + slots + "/" + Apportion.SLOTS + " slots) ";
    }
}

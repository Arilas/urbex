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
 * <b>Exact shares beside the real slot counts.</b> The share is what the author asked for -
 * {@code 43/128} does not say whether they wrote a third or {@code 0.336} - and the slot count is what
 * they get. Both are printed, and the second is read from {@link Apportion#slots}, the same
 * apportionment the palette is compiled with, rather than re-derived per leaf.
 * <p>
 * <b>Re-deriving it was a bug and it is worth recording why.</b> Rounding each share independently
 * printed {@code 1/3 (43/128 slots)} three times for three equal weights - 129 slots, for a node that
 * has 128 - and so could not answer the one question that needs a slot count at all: which of three
 * equal choices {@code WEIGHT.060}'s tie break made rarer. A report that agrees with the palette
 * everywhere except where the reader is looking is worse than no parenthetical.
 */
public final class ResolutionReport {

    private ResolutionReport() {
    }

    /** The report for one marker of a linked palette, as lines. */
    public static String of(Marker marker, ResolvedNode node) {
        List<String> lines = new ArrayList<>();
        lines.add("marker " + marker + " — " + node.kind().key());
        describe(node, "  ", Fraction.ONE, UNAPPORTIONED, lines);
        return String.join("\n", lines);
    }

    /** What a node's slot count is when it is not one alternative of an apportioned list. */
    private static final int UNAPPORTIONED = -1;

    /** The report for every marker of a linked palette, in declaration order. */
    public static String of(NodeResolver.ResolvedPalette palette) {
        List<String> lines = new ArrayList<>();
        palette.palette().forEach((marker, node) -> lines.add(of(marker, node)));
        return String.join("\n", lines);
    }

    private static void describe(ResolvedNode node, String indent, Fraction share, int slots,
                                 List<String> lines) {
        String at = share(share, slots);
        switch (node.source()) {
            case ResolvedNode.Source.Block block -> lines.add(indent + at + block.block());
            case ResolvedNode.Source.Tag tag -> lines.add(indent + at + "every block of " + tag.tag());
            case ResolvedNode.Source.Alias alias ->
                    lines.add(indent + at + "whatever " + alias.of() + " resolves to");
            case ResolvedNode.Source.Weighted weighted -> {
                lines.add(indent + at + "weighted, " + weighted.choices().size() + " alternatives:");
                alternatives(Apportion.flatten(weighted.choices()), indent + "  ", share, lines);
            }
            case ResolvedNode.Source.Socket socket -> {
                lines.add(indent + "light_socket, tried floor, wall, ceiling, free:");
                for (Map.Entry<Kind.Placement, List<ResolvedNode.Choice>> list
                        : socket.placements().entrySet()) {
                    lines.add(indent + "  " + list.getKey().key() + ":");
                    alternatives(Apportion.flatten(list.getValue()), indent + "    ", Fraction.ONE,
                            lines);
                }
            }
        }
        traits(node, indent + "  ", lines);
    }

    /**
     * One apportioned list, with the slot counts the palette will actually hold.
     * <p>
     * {@link Apportion#slots} is the same call {@code Apportion.materialise} makes, so the numbers here
     * and the numbers in the compiled entry are one computation rather than two that agree by
     * coincidence - which is what makes {@code WEIGHT.060}'s tie break visible: three equal thirds print
     * 43, 43 and 42, and the author asking why one alternative is rarer has their answer.
     * <p>
     * A list {@link Apportion#slots} cannot serve - empty, or longer than the slot budget, which
     * {@code WEIGHT.063} refuses at compile - prints its shares without a parenthetical rather than a
     * fabricated one. This runs on the linked palette, which is before that refusal.
     */
    private static void alternatives(List<Apportion.Leaf> leaves, String indent, Fraction of,
                                     List<String> lines) {
        int[] slots = leaves.isEmpty() || leaves.size() > Apportion.SLOTS
                ? null
                : Apportion.slots(leaves.stream().map(Apportion.Leaf::share).toList(),
                        Apportion.SLOTS);
        for (int index = 0; index < leaves.size(); index++) {
            describe(leaves.get(index).node(), indent, of.times(leaves.get(index).share()),
                    slots == null ? UNAPPORTIONED : slots[index], lines);
        }
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
                describe(satellite, indent + "    ", Fraction.ONE, UNAPPORTIONED, lines);
            });
        });
    }

    /** A share as the file's own arithmetic, and as the slots the apportionment gave it. */
    private static String share(Fraction share, int slots) {
        if (share.equals(Fraction.ONE) && slots == UNAPPORTIONED) {
            return "";
        }
        if (slots == UNAPPORTIONED) {
            return share.toPlainString() + " ";
        }
        return share.toPlainString() + " (" + slots + "/" + Apportion.SLOTS + " slots) ";
    }
}

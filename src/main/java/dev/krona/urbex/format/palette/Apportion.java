package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How much of a node each alternative takes, and which of the 128 slots it gets.
 * <p>
 * <b>One rounding step, at the root</b> ({@code WEIGHT.052}). The tree is walked accumulating exact
 * {@link Fraction}s - a nested node's own distribution scaled by its share of its parent
 * ({@code WEIGHT.050}), with {@code rest} resolved against the list it appears in at whatever depth that
 * is ({@code WEIGHT.051}) - and only the flattened result is materialised. Distributing at each level and
 * folding the results would compound a rounding step per level, and would also make {@code rest} mean
 * "the remainder of an already-rounded parent share" at depth and "the remainder" at the top, which is
 * two meanings for one spelling. {@code WEIGHT.053} is the invariant that follows: a nested tree's
 * compiled distribution equals its flattened equivalent's to within one slot per choice.
 * <p>
 * <b>What is checked here and what is checked one stage earlier.</b> {@code WEIGHT.005} says every rule
 * about a list's sizes is evaluated on the list "after {@code $spread} expansion, and <b>before</b>
 * exclusion, never on the choices as written". {@code RawChoice} checks {@code WEIGHT.013} and
 * {@code WEIGHT.014} at decode and skips any list holding a {@code $spread} or a {@code when}, precisely
 * so that {@link #checkSizes} can check them again where the list is whole.
 * <p>
 * <b>Why before exclusion.</b> The rule used to say "after exclusion", and that made {@code WEIGHT.014}
 * and {@code WEIGHT.021} contradict each other: a list of three shares totalling 1, one of them carrying
 * a {@code when} that does not hold, totals 0.9 with nothing to take the remainder - so
 * {@code WEIGHT.014} refused precisely the list {@code WEIGHT.021} says to scale back up. The rule now
 * states this order and records the reason: a size rule is about what the author assembled, and
 * exclusion is a fact about the installed environment which {@code WEIGHT.021} already governs.
 */
public final class Apportion {

    /**
     * {@code WEIGHT.040}: "A {@code weighted} node compiles to exactly 128 slots."
     * <p>
     * Version 1's number, deliberately not a second constant with the same value: the two formats
     * address a slot with one function ({@code Rng.paletteSlotAt}) and a marker converted from one format
     * to the other must land in the same place, which it cannot do if the array lengths ever drift.
     */
    public static final int SLOTS = CompiledPalette.SLOTS;

    private Apportion() {
    }

    /**
     * One alternative of the flattened tree: a leaf node, and the exact fraction of the root it takes.
     *
     * @param share summing to exactly 1 across a flattened list, by construction
     */
    public record Leaf(ResolvedNode node, Fraction share) {
    }

    // ---- Sizes ---------------------------------------------------------------------------------

    /**
     * {@code WEIGHT.003}, {@code WEIGHT.013}, {@code WEIGHT.014} and {@code WEIGHT.019} over every list
     * in this node's tree, on the expanded list.
     * <p>
     * Recursive over both kinds of alternative, because each list resolves its own {@code rest}
     * ({@code WEIGHT.051}) and so has its own total to be right about. A node with no list is trivially
     * fine.
     *
     * @return false when a diagnostic was reported; every list is checked before it returns
     *         ({@code DIAG.903})
     */
    public static boolean checkSizes(ResolvedNode node, PointerResolver.Site site,
                                     Diagnostics diagnostics) {
        return switch (node.source()) {
            case ResolvedNode.Source.Weighted weighted ->
                    checkSizes(weighted.choices(), site, "choice", diagnostics);
            case ResolvedNode.Source.Socket socket -> {
                boolean ok = true;
                for (Map.Entry<Kind.Placement, List<ResolvedNode.Choice>> list
                        : socket.placements().entrySet()) {
                    ok &= checkSizes(list.getValue(),
                            site.inside("'" + list.getKey().key() + "'"), "candidate", diagnostics);
                }
                yield ok;
            }
            default -> true;
        };
    }

    /** {@link #checkSizes(ResolvedNode, PointerResolver.Site, Diagnostics)} for one list. */
    public static boolean checkSizes(List<ResolvedNode.Choice> choices, PointerResolver.Site site,
                                     String position, Diagnostics diagnostics) {
        boolean ok = stated(choices, site, position, diagnostics)
                & oneRest(choices, site, diagnostics)
                & sharesLeaveTheRightRemainder(choices, site, diagnostics);
        for (int index = 0; index < choices.size(); index++) {
            ok &= checkSizes(choices.get(index).node(), site.inside(position + " " + index),
                    diagnostics);
        }
        return ok;
    }

    /**
     * {@code WEIGHT.003}: every element of the expanded list states exactly one size.
     * <p>
     * Unreachable from a document today and kept anyway: the only element that may omit a size is a
     * {@code $spread} ({@code REF.070}), which is replaced by elements that state their own before this
     * runs, so an element with no size here would mean the expansion had produced one. The check is what
     * makes that a diagnostic rather than a crash in {@link #flatten}.
     */
    private static boolean stated(List<ResolvedNode.Choice> choices, PointerResolver.Site site,
                                  String position, Diagnostics diagnostics) {
        boolean ok = true;
        for (int index = 0; index < choices.size(); index++) {
            if (choices.get(index).size().isEmpty()) {
                // site.location(), not site.inside(position): DIAG.040's template already writes
                // "choice <i>" after the location slot, so appending the position here would print it
                // twice. The template's word is "choice" even in a socket's list, which is the
                // catalogue row's wording and is noted in this task's report.
                diagnostics.error(Diag.DIAG_040, site.location(), index,
                        "declares none of 'weight', 'share' and 'rest'");
                ok = false;
            }
        }
        return ok;
    }

    /**
     * {@code WEIGHT.013}: at most one {@code rest}, and none beside a {@code weight}.
     * <p>
     * The counts are of the expanded list, which is the point of running this again after decode: a file
     * that writes {@code "rest": true} beside a {@code $spread} of a list of weights is asking for the
     * same fraction twice, and nothing at decode can see the second half of that.
     */
    private static boolean oneRest(List<ResolvedNode.Choice> choices, PointerResolver.Site site,
                                   Diagnostics diagnostics) {
        long rests = count(choices, Size.Rest.class);
        long weights = count(choices, Size.Weight.class);
        if (rests > 1) {
            diagnostics.error(Diag.DIAG_041, site.location(), rests + " choices declare 'rest'");
            return false;
        }
        if (rests == 1 && weights > 0) {
            diagnostics.error(Diag.DIAG_041, site.location(),
                    "'rest' is declared beside " + weights + " weighted choices");
            return false;
        }
        return true;
    }

    /**
     * {@code WEIGHT.014}, and {@code WEIGHT.019} when a spread contributed to the total.
     * <p>
     * The two halves of the total are counted separately because {@code WEIGHT.019} requires the message
     * to name them separately: a spread that brings a list's shares to 1 is refused "naming the incoming
     * and inherited totals", since - in that rule's words - "Shares total 1.15" sends an author looking
     * through their own four lines for a number that came from a file they did not write. The clause is
     * absent when nothing was spread in, which is every list a single file wrote.
     */
    private static boolean sharesLeaveTheRightRemainder(List<ResolvedNode.Choice> choices,
                                                        PointerResolver.Site site,
                                                        Diagnostics diagnostics) {
        Fraction written = Fraction.ZERO;
        Fraction spread = Fraction.ZERO;
        // A LinkedHashMap, not a Set.copyOf: this reaches a message, and the order two spreads are
        // named in must be the order the file wrote them rather than a per-JVM hash salt.
        Map<String, Boolean> sources = new LinkedHashMap<>();
        for (ResolvedNode.Choice choice : choices) {
            if (!(choice.size().orElse(null) instanceof Size.Share share)) {
                continue;
            }
            Fraction fraction = Fraction.ofDecimal(share.fraction());
            if (choice.spreadFrom().isPresent()) {
                spread = spread.plus(fraction);
                sources.put(choice.spreadFrom().get(), Boolean.TRUE);
            } else {
                written = written.plus(fraction);
            }
        }
        Fraction total = written.plus(spread);
        String provenance = sources.isEmpty() ? "" : " - " + written.toPlainString()
                + " written here and " + spread.toPlainString() + " spread from "
                + quoted(sources.keySet());

        boolean somethingTakesTheRemainder =
                count(choices, Size.Rest.class) + count(choices, Size.Weight.class) > 0;
        if (somethingTakesTheRemainder) {
            if (total.compareTo(Fraction.ONE) >= 0) {
                diagnostics.error(Diag.DIAG_045, site.location(), total.toPlainString(), provenance,
                        "Shares must leave something for the weight choices");
                return false;
            }
            return true;
        }
        if (choices.isEmpty() || total.compareTo(Fraction.ONE) == 0) {
            return true;
        }
        diagnostics.error(Diag.DIAG_045, site.location(), total.toPlainString(), provenance,
                "Shares must total exactly 1 when nothing takes the remainder");
        return false;
    }

    private static String quoted(Iterable<String> names) {
        List<String> parts = new ArrayList<>();
        names.forEach(name -> parts.add("'" + name + "'"));
        return String.join(" and ", parts);
    }

    private static long count(List<ResolvedNode.Choice> choices, Class<? extends Size> size) {
        return choices.stream().filter(choice -> choice.size().filter(size::isInstance).isPresent())
                .count();
    }

    // ---- Exact rationals over the whole tree ---------------------------------------------------

    /** {@code WEIGHT.050}: a nested node contributes its own distribution, scaled by its share. */
    public static List<Leaf> flatten(ResolvedNode.Source.Weighted weighted) {
        return flatten(weighted.choices());
    }

    /**
     * The tree below one list, as leaves carrying their exact fraction of it.
     * <p>
     * Shares take exactly what they state ({@code WEIGHT.010}); what they leave is divided between the
     * {@code weight} choices in proportion to their weights ({@code WEIGHT.011}), or taken whole by the
     * one {@code rest} ({@code WEIGHT.012}). A list of nothing but shares that no longer total 1 - which
     * only exclusion can produce, and only in the shape {@code WEIGHT.021} describes - has its survivors
     * scaled up in their existing proportions, which is that rule's "in proportion to the remaining
     * shares if there are none".
     * <p>
     * Every list this is handed has been through {@link #checkSizes} and {@link Exclusion}, so it is
     * non-empty, every element states a size, and the shares leave the room the sizes need.
     */
    public static List<Leaf> flatten(List<ResolvedNode.Choice> choices) {
        List<Leaf> leaves = new ArrayList<>();
        collect(choices, Fraction.ONE, leaves);
        return List.copyOf(leaves);
    }

    private static void collect(List<ResolvedNode.Choice> choices, Fraction of, List<Leaf> into) {
        List<Fraction> shares = shares(choices);
        for (int index = 0; index < choices.size(); index++) {
            Fraction fraction = of.times(shares.get(index));
            ResolvedNode node = choices.get(index).node();
            if (node.source() instanceof ResolvedNode.Source.Weighted nested) {
                collect(nested.choices(), fraction, into);
            } else {
                into.add(new Leaf(node, fraction));
            }
        }
    }

    /**
     * Each choice's exact fraction of the list it is in, positionally.
     * <p>
     * A list parallel to {@code choices} rather than a map keyed by them, because two elements of one
     * list can be equal records - the same block written twice at two weights is a legal, if odd, list -
     * and a keyed map would silently make them one entry with one share.
     */
    private static List<Fraction> shares(List<ResolvedNode.Choice> choices) {
        Fraction stated = Fraction.ZERO;
        long weightTotal = 0;
        for (ResolvedNode.Choice choice : choices) {
            switch (choice.size().orElseThrow()) {
                case Size.Share share -> stated = stated.plus(Fraction.ofDecimal(share.fraction()));
                case Size.Weight weight -> weightTotal += weight.weight();
                case Size.Rest ignored -> {
                }
            }
        }
        Fraction remainder = Fraction.ONE.minus(stated);
        // WEIGHT.021, the "in proportion to the remaining shares" half: exclusion took a share out of a
        // list that has nothing to give the remainder to, so what is left is scaled back up to 1. Only
        // reachable after exclusion - WEIGHT.014 refuses the same list written that way.
        boolean scaleShares = weightTotal == 0 && !hasRest(choices) && !stated.equals(Fraction.ONE)
                && stated.signum() > 0;

        List<Fraction> perIndex = new ArrayList<>();
        for (ResolvedNode.Choice choice : choices) {
            long weights = weightTotal;
            perIndex.add(switch (choice.size().orElseThrow()) {
                case Size.Share share -> scaleShares
                        ? Fraction.ofDecimal(share.fraction()).dividedBy(stated)
                        : Fraction.ofDecimal(share.fraction());
                case Size.Weight weight -> remainder.times(Fraction.of(weight.weight(), weights));
                case Size.Rest ignored -> remainder;
            });
        }
        return List.copyOf(perIndex);
    }

    private static boolean hasRest(List<ResolvedNode.Choice> choices) {
        return count(choices, Size.Rest.class) > 0;
    }

    // ---- Rounding ------------------------------------------------------------------------------

    /**
     * {@code WEIGHT.060} to {@code WEIGHT.062}: how many of {@code slotCount} slots each share gets.
     * <p>
     * Largest remainder, ties to the lowest index in declaration order ({@code WEIGHT.060}), every slot
     * assigned ({@code WEIGHT.061}), and every share at least one ({@code WEIGHT.062}) - "a choice an
     * author wrote and weighted is a choice they want to see. Silently rounding it out of existence makes
     * a weight of 1 in a long list mean nothing, with no diagnostic."
     * <p>
     * <b>Why taking the deficit from the largest cannot starve the donor.</b> With {@code n} shares and
     * {@code z} of them rounding to zero, the {@code n - z} others hold all {@code slotCount} slots; the
     * transfer leaves them {@code slotCount - z}, which is at least {@code n - z} exactly when
     * {@code n <= slotCount}. If the largest were 1, every non-zero share would be 1 and
     * {@code slotCount = n - z}, which forces {@code z = 0} and no transfer at all. {@code WEIGHT.063} is
     * what keeps {@code n <= slotCount} true, and {@link #materialise} raises it.
     *
     * <b>The dependency on {@code WEIGHT.063} is enforced, not assumed.</b> This method is public and
     * the argument above holds only while there is at least one share and no more than {@code slotCount}
     * of them; outside that range it would quietly hand back an array violating {@code WEIGHT.062}, or
     * index a zero-length one. Both were reachable: an empty list is what a socket's emptied placement
     * list used to be, and {@link #materialise} raising {@code DIAG.044} is a check a second caller could
     * simply not have made. A precondition is the honest shape - these are not malformed datapacks, they
     * are callers using this wrongly, and {@code 08-errors.md} catalogues the former only.
     *
     * @param shares exact, in declaration order, summing to 1; at least one and at most {@code slotCount}
     * @throws IllegalArgumentException when that is not so
     */
    public static int[] slots(List<Fraction> shares, int slotCount) {
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("no shares to apportion " + slotCount + " slots over");
        }
        if (shares.size() > slotCount) {
            throw new IllegalArgumentException(shares.size() + " shares over " + slotCount
                    + " slots cannot satisfy WEIGHT.062; the caller owes a DIAG.044");
        }
        int[] slots = new int[shares.size()];
        List<Fraction> remainders = new ArrayList<>();
        int assigned = 0;
        for (int index = 0; index < shares.size(); index++) {
            Fraction exact = shares.get(index).times(Fraction.of(slotCount));
            int whole = exact.numerator().divide(exact.denominator()).intValueExact();
            slots[index] = whole;
            assigned += whole;
            remainders.add(exact.minus(Fraction.of(whole)));
        }
        for (int remaining = slotCount - assigned; remaining > 0; remaining--) {
            int best = 0;
            for (int index = 1; index < remainders.size(); index++) {
                // Strictly greater, so an equal remainder leaves the lower index holding it: that is
                // WEIGHT.060's tie break, and it is the whole of what WEIGHT.064 allows declaration
                // order to decide.
                if (remainders.get(index).compareTo(remainders.get(best)) > 0) {
                    best = index;
                }
            }
            slots[best]++;
            remainders.set(best, Fraction.of(-1));
        }
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] > 0) {
                continue;
            }
            int largest = 0;
            for (int other = 1; other < slots.length; other++) {
                if (slots[other] > slots[largest]) {
                    largest = other;
                }
            }
            slots[largest]--;
            slots[index] = 1;
        }
        return slots;
    }

    /**
     * The 128 slots one weighted node compiles to, or empty when it cannot have them.
     * <p>
     * The single materialisation {@code WEIGHT.052} allows: the tree is flattened to exact fractions
     * first, and this is the only place a fraction becomes a slot.
     * <p>
     * <b>{@code WEIGHT.063} is raised against the flattened count,</b> which is what that rule now says:
     * "A node whose alternatives, flattened by {@code WEIGHT.052}, number more than 128 after exclusion
     * is refused". It used to be stated per list, which is not the condition it names after "since" - a
     * tree of four lists of fifty is 200 alternatives competing for 128 slots with no list in it anywhere
     * near the limit. The check subsumes the older wording, since a single list of more than 128 choices
     * flattens to at least that many leaves.
     *
     * @param choices a list {@link Exclusion} has already pruned, and therefore non-empty: an emptied
     *                list either cascaded out of its parent or refused the marker with {@code DIAG.043},
     *                so one arriving here is a caller that skipped stage 4's first half rather than a
     *                datapack. {@link #slots} says so with a precondition
     */
    public static Optional<ResolvedNode[]> materialise(List<ResolvedNode.Choice> choices,
                                                       PointerResolver.Site site,
                                                       Diagnostics diagnostics) {
        List<Leaf> leaves = flatten(choices);
        if (leaves.size() > SLOTS) {
            diagnostics.error(Diag.DIAG_044, site.location(), leaves.size());
            return Optional.empty();
        }
        int[] slots = slots(leaves.stream().map(Leaf::share).toList(), SLOTS);
        ResolvedNode[] materialised = new ResolvedNode[SLOTS];
        int at = 0;
        for (int index = 0; index < leaves.size(); index++) {
            for (int slot = 0; slot < slots[index]; slot++) {
                materialised[at++] = leaves.get(index).node();
            }
        }
        return Optional.of(materialised);
    }
}

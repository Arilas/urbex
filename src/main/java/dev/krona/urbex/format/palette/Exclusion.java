package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.worldgen.lost.cityassets.ReferenceProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which alternatives exist at all: {@code when} ({@code WEIGHT.020}-{@code WEIGHT.024}) and absent
 * blocks ({@code WEIGHT.030}-{@code WEIGHT.032}), applied once, before any share is computed.
 * <p>
 * The order is fixed by {@code WEIGHT.031} - {@code when} first, then absent blocks - "so the two
 * compose without ordering surprises", and both run before {@code Apportion} so that
 * {@code WEIGHT.021}'s redistribution is not a redistribution at all but simply the arithmetic of a
 * shorter list. A removed {@code weight} leaves the remaining weights dividing the same remainder; a
 * removed {@code share} leaves a larger remainder for the weights, or, when there are none, leaves the
 * surviving shares to be scaled up in their existing proportions. All three fall out of running the
 * apportionment on the list that is left, which is why nothing here computes a size.
 * <p>
 * <b>Once, at load</b> ({@code WEIGHT.022}). This runs over the resolved tree and produces another
 * tree; every position that resolves the marker afterwards sees the same reduced list. That is the whole
 * difference from {@code urbex:optional}, which {@code WEIGHT.025} refuses to let stand in for it: this
 * decides whether a choice exists, and the trait decides, per position, whether an existing choice is
 * written or its replacement is.
 */
public final class Exclusion {

    private Exclusion() {
    }

    /**
     * What the two conditions are asked about, so that a test can answer them without a game.
     * <p>
     * {@code WEIGHT.023} fixes the questions at exactly two - a mod id that must be loaded, and a
     * namespace that must register assets - and its {@code > Why} says why there is no third: "both
     * already have implementations", and widening the condition to configuration or dimension would make
     * "load time" depend on state that can change without a reload. The third method is not a third
     * condition: it is {@code WEIGHT.030}'s absent block, which is asked of every choice rather than
     * written on one.
     */
    public interface Presence {

        /** {@code WEIGHT.023}: is this mod id loaded? */
        boolean modIsLoaded(String modId);

        /** {@code WEIGHT.023}: does anything loaded register assets in this namespace? */
        boolean packRegistersAssets(String namespace);

        /** {@code WEIGHT.030}: does any installed mod provide the block this string names? */
        boolean blockExists(String block);
    }

    /**
     * The presence this game has, answered by the two implementations {@code WEIGHT.023}'s
     * {@code > Why} says already exist.
     * <p>
     * The block half asks the registry rather than the loader, and the difference is
     * {@code minecraft:no_such_block}: the mod that would provide it is installed and the id still does
     * not resolve, which {@code WEIGHT.030} covers just as squarely as a block from a mod nobody has -
     * it is an "id no installed mod provides" either way. Only the id is read, because a property
     * expression on a block that exists is {@code MODEL.043}'s rejection and not this rule's silence.
     *
     * @param packNamespaces every namespace the compiled snapshot holds assets in, which is the closest
     *                       thing to "which packs are installed" a datapack-only pack can be seen by
     */
    public static Presence installed(HolderLookup<Block> blocks, Set<String> packNamespaces) {
        Set<String> namespaces = Set.copyOf(packNamespaces);
        return new Presence() {
            @Override
            public boolean modIsLoaded(String modId) {
                return ReferenceProvider.modIsInstalled(modId);
            }

            @Override
            public boolean packRegistersAssets(String namespace) {
                return namespaces.contains(namespace);
            }

            @Override
            public boolean blockExists(String block) {
                int properties = block.indexOf('[');
                Identifier id = Identifier.tryParse(
                        properties < 0 ? block : block.substring(0, properties));
                return id != null
                        && blocks.get(ResourceKey.create(Registries.BLOCK, id)).isPresent();
            }
        };
    }

    /**
     * The node with every alternative its conditions exclude removed, or empty when a list was emptied.
     * <p>
     * Recursive, because {@code MODEL.047} makes nesting unbounded and a {@code when} is a key of a
     * choice at any depth. A node with no list of alternatives - a block, a tag, an alias - comes back
     * unchanged: {@code WEIGHT.030} drops "a choice", and a marker naming an absent block is
     * {@code MODEL.042}, which resolves to air and lets the load succeed.
     */
    public static Optional<ResolvedNode> prune(ResolvedNode node, Presence presence,
                                               PointerResolver.Site site, Diagnostics diagnostics) {
        return switch (node.source()) {
            case ResolvedNode.Source.Weighted weighted -> {
                Optional<List<ResolvedNode.Choice>> kept =
                        apply(weighted.choices(), presence, site, "choice", diagnostics);
                yield kept.map(choices -> new ResolvedNode(node.kind(),
                        new ResolvedNode.Source.Weighted(choices), node.traits()));
            }
            case ResolvedNode.Source.Socket socket -> socket(node, socket, presence, site,
                    diagnostics);
            default -> Optional.of(node);
        };
    }

    /**
     * A socket's four placement lists, pruned, or empty when nothing is left in any of them.
     * <p>
     * <b>Refused with {@code DIAG.043}, and no rule says so.</b> {@code WEIGHT.024} and
     * {@code WEIGHT.032} both name "a {@code weighted} node"; a {@code light_socket} is a different kind
     * with the same lists, whose candidates {@code MODEL.076} explicitly says accept {@code when}. So the
     * case is reachable, it is exactly the failure {@code WEIGHT.024}'s {@code > Why} describes - "a
     * marker that silently becomes air, which is the failure mode a pack notices only by looking at a
     * chunk" - and {@code DIAG.043}'s message is true of it word for word, since {@code MODEL.070} makes
     * the candidates a socket's only block source. It is reported in this task's report as a gap in the
     * two rules rather than worked around here, because the alternative is a marker that places nothing.
     * <p>
     * A list emptied while another survives is <em>not</em> refused: {@code MODEL.072} asks for a
     * candidate in one of the four, not in all of them, and {@code MODEL.073} tries the opportunities in
     * a fixed order and falls through the ones with nothing to place.
     */
    private static Optional<ResolvedNode> socket(ResolvedNode node,
                                                 ResolvedNode.Source.Socket socket,
                                                 Presence presence, PointerResolver.Site site,
                                                 Diagnostics diagnostics) {
        Map<Kind.Placement, List<ResolvedNode.Choice>> kept = new LinkedHashMap<>();
        Removed removed = new Removed();
        boolean ok = true;
        for (Map.Entry<Kind.Placement, List<ResolvedNode.Choice>> list : socket.placements()
                .entrySet()) {
            PointerResolver.Site inside = site.inside("'" + list.getKey().key() + "'");
            List<ResolvedNode.Choice> survivors = new ArrayList<>();
            // DIAG.903: every list of this socket is walked before any of them is acted on, and the
            // counts DIAG.043 prints are the socket's, not one list's - the message is about the marker.
            ok &= keep(list.getValue(), presence, inside, "candidate", diagnostics, removed,
                    survivors);
            kept.put(list.getKey(), survivors);
        }
        if (!ok) {
            return Optional.empty();
        }
        if (kept.values().stream().allMatch(List::isEmpty)) {
            diagnostics.error(Diag.DIAG_043, site.location(), removed.byWhen, removed.byAbsentBlock);
            return Optional.empty();
        }
        return Optional.of(new ResolvedNode(node.kind(),
                new ResolvedNode.Source.Socket(kept), node.traits()));
    }

    /**
     * One list, with the alternatives its conditions exclude removed ({@code WEIGHT.020},
     * {@code WEIGHT.030}), or empty when every one of them went ({@code WEIGHT.024},
     * {@code WEIGHT.032}).
     * <p>
     * <b>An emptied list is refused at every depth, and that reading needs a ruling.</b>
     * {@code WEIGHT.024} says "A {@code weighted} node all of whose choices are removed is refused", and
     * a nested one is a {@code weighted} node - so the sentence covers it and this follows the sentence.
     * Two things pull the other way and are in this task's report rather than in this code: the rule's
     * {@code > Why} ("the alternative is a marker that silently becomes air") is not true of a nested
     * node, whose parent would simply divide the remainder between the choices that are left, and
     * {@code DIAG.044}'s own remedy tells an author to "nest the rare choices under one weighted choice"
     * - which is exactly where cross-mod content goes, and so exactly the list this refuses.
     */
    public static Optional<List<ResolvedNode.Choice>> apply(List<ResolvedNode.Choice> choices,
                                                            Presence presence,
                                                            PointerResolver.Site site,
                                                            String position,
                                                            Diagnostics diagnostics) {
        Removed removed = new Removed();
        List<ResolvedNode.Choice> survivors = new ArrayList<>();
        if (!keep(choices, presence, site, position, diagnostics, removed, survivors)) {
            return Optional.empty();
        }
        if (survivors.isEmpty()) {
            diagnostics.error(Diag.DIAG_043, site.location(), removed.byWhen, removed.byAbsentBlock);
            return Optional.empty();
        }
        return Optional.of(List.copyOf(survivors));
    }

    /**
     * Walks one list, appending what survives and counting what did not.
     *
     * @return false when a nested node was refused, which has already been reported
     */
    private static boolean keep(List<ResolvedNode.Choice> choices, Presence presence,
                                PointerResolver.Site site, String position,
                                Diagnostics diagnostics, Removed removed,
                                List<ResolvedNode.Choice> survivors) {
        boolean ok = true;
        for (int index = 0; index < choices.size(); index++) {
            ResolvedNode.Choice choice = choices.get(index);
            if (choice.when().isPresent() && !holds(choice.when().get(), presence)) {
                removed.byWhen++;
                continue;
            }
            // WEIGHT.031: after 'when', and before the share is computed.
            if (choice.node().source() instanceof ResolvedNode.Source.Block block
                    && !presence.blockExists(block.block())) {
                removed.byAbsentBlock++;
                continue;
            }
            Optional<ResolvedNode> pruned = prune(choice.node(), presence,
                    site.inside(position + " " + index), diagnostics);
            if (pruned.isEmpty()) {
                ok = false;
                continue;
            }
            survivors.add(new ResolvedNode.Choice(pruned.get(), choice.size(), choice.when(),
                    choice.spreadFrom()));
        }
        return ok;
    }

    /**
     * {@code WEIGHT.023}: both fields must hold, and a {@code when} with neither is vacuously true.
     * <p>
     * Both rather than either, because {@code When} carries two independent optionals and a condition
     * naming a mod <em>and</em> a pack is naming two things it needs. No rule states the conjunction; no
     * fixture writes both, and this is the reading that makes each field mean what it says on its own.
     */
    private static boolean holds(When when, Presence presence) {
        return when.mod().map(presence::modIsLoaded).orElse(true)
                && when.pack().map(presence::packRegistersAssets).orElse(true);
    }

    /** {@code DIAG.043}'s two counts: "{@code <n>} by 'when', {@code <n>} by absent blocks". */
    private static final class Removed {
        private int byWhen;
        private int byAbsentBlock;
    }
}

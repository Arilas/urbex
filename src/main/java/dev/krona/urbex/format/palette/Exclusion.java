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
     * A marker's node with every excluded alternative removed, or empty with {@code DIAG.043} when the
     * marker is left with nothing.
     * <p>
     * <b>This is the only place {@code DIAG.043} is raised, and that is {@code WEIGHT.024}'s cascade.</b>
     * A <em>nested</em> node all of whose alternatives are excluded is removed from the list it is a
     * choice of rather than refused, upward until either something survives or the marker's own node is
     * empty. The format would otherwise recommend a shape it rejects: {@code DIAG.044}'s remedy is "nest
     * the rare choices under one weighted choice", and the rare choices are the ones carrying
     * {@code when}, so an author following that advice on a vanilla install would be refused for doing
     * what the diagnostic told them to. Nothing is lost by cascading - the parent divides the remainder
     * between the choices that are left, which is {@code WEIGHT.021} and not a new mechanism - and it is
     * what makes {@code DIAG.043}'s sentence, "the marker would generate as air", true wherever it is
     * printed.
     * <p>
     * The two counts are accumulated over the <em>whole</em> subtree for the same reason. A root whose
     * two choices are one excluded block and one nested list of three absent ones reports three absent
     * blocks, not one nested node: the message names why each alternative went, and "a node that
     * cascaded" is not a cause an author can act on.
     */
    public static Optional<ResolvedNode> prune(ResolvedNode node, Presence presence,
                                               PointerResolver.Site site, Diagnostics diagnostics) {
        Removed removed = new Removed();
        Optional<ResolvedNode> pruned = exclude(node, presence, site, removed);
        if (pruned.isEmpty()) {
            diagnostics.error(Diag.DIAG_043, site.location(), removed.byWhen, removed.byAbsentBlock);
        }
        return pruned;
    }

    /**
     * One node of the tree, or empty when everything under it went - which is exclusion, not a refusal.
     * <p>
     * Recursive, because {@code MODEL.047} makes nesting unbounded and a {@code when} is a key of a
     * choice at any depth. A node with no list of alternatives - a block, a tag, an alias - comes back
     * unchanged: {@code WEIGHT.030} drops "a choice", and a marker naming an absent block is
     * {@code MODEL.042}, which resolves to air and lets the load succeed.
     * <p>
     * Nothing here reports, so there is no path on which a cascade prints a diagnostic about a node the
     * parent then absorbs. {@link #prune} is the one caller that turns an empty result into
     * {@code DIAG.043}, and it is only ever the marker's own node.
     */
    private static Optional<ResolvedNode> exclude(ResolvedNode node, Presence presence,
                                                  PointerResolver.Site site, Removed removed) {
        return switch (node.source()) {
            case ResolvedNode.Source.Weighted weighted -> {
                List<ResolvedNode.Choice> kept =
                        keep(weighted.choices(), presence, site, "choice", removed);
                yield kept.isEmpty() ? Optional.empty() : Optional.of(new ResolvedNode(node.kind(),
                        new ResolvedNode.Source.Weighted(kept), node.traits()));
            }
            case ResolvedNode.Source.Socket socket -> socket(node, socket, presence, site, removed);
            default -> Optional.of(node);
        };
    }

    /**
     * A socket's four placement lists, with their excluded candidates gone.
     * <p>
     * {@code WEIGHT.024} names a {@code light_socket} beside a {@code weighted} node because
     * {@code MODEL.076} makes its placement lists lists like any other and {@code MODEL.070} makes their
     * candidates its only block source - so a socket with none generates as air exactly as an emptied
     * weighted node does. {@code MODEL.072} refuses a socket that <em>declares</em> no candidate; this is
     * the same absence arriving from the installed environment instead.
     * <p>
     * A list emptied while another survives is not an exclusion at all: {@code MODEL.072} asks for a
     * candidate in one of the four, not in all of them, and {@code MODEL.073} tries the opportunities in
     * a fixed order and falls through the ones with nothing to place.
     */
    private static Optional<ResolvedNode> socket(ResolvedNode node,
                                                 ResolvedNode.Source.Socket socket,
                                                 Presence presence, PointerResolver.Site site,
                                                 Removed removed) {
        Map<Kind.Placement, List<ResolvedNode.Choice>> kept = new LinkedHashMap<>();
        for (Map.Entry<Kind.Placement, List<ResolvedNode.Choice>> list : socket.placements()
                .entrySet()) {
            kept.put(list.getKey(), keep(list.getValue(), presence,
                    site.inside("'" + list.getKey().key() + "'"), "candidate", removed));
        }
        if (kept.values().stream().allMatch(List::isEmpty)) {
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
     * The brief's entry point, and the shape a caller holding a bare list wants: it treats that list as a
     * root, so an emptied one is {@code DIAG.043} rather than a cascade. A list <em>inside</em> a tree
     * goes through {@link #prune}, which is where the cascade lives.
     */
    public static Optional<List<ResolvedNode.Choice>> apply(List<ResolvedNode.Choice> choices,
                                                            Presence presence,
                                                            PointerResolver.Site site,
                                                            String position,
                                                            Diagnostics diagnostics) {
        Removed removed = new Removed();
        List<ResolvedNode.Choice> survivors = keep(choices, presence, site, position, removed);
        if (survivors.isEmpty()) {
            diagnostics.error(Diag.DIAG_043, site.location(), removed.byWhen, removed.byAbsentBlock);
            return Optional.empty();
        }
        return Optional.of(survivors);
    }

    /** Walks one list, keeping what survives and counting what did not, by the rule that took it. */
    private static List<ResolvedNode.Choice> keep(List<ResolvedNode.Choice> choices,
                                                  Presence presence, PointerResolver.Site site,
                                                  String position, Removed removed) {
        List<ResolvedNode.Choice> survivors = new ArrayList<>();
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
            // Empty means the whole subtree was excluded, so this choice goes with it - WEIGHT.024's
            // cascade. Its own counts are already in `removed`, which is what keeps DIAG.043's two
            // numbers a count of causes rather than of nodes.
            exclude(choice.node(), presence, site.inside(position + " " + index), removed)
                    .ifPresent(pruned -> survivors.add(new ResolvedNode.Choice(pruned,
                            choice.size(), choice.when(), choice.spreadFrom())));
        }
        return List.copyOf(survivors);
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

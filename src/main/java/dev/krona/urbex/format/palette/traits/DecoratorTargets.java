package dev.krona.urbex.format.palette.traits;

import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.palette.PointerResolver;
import dev.krona.urbex.format.palette.ResolvedNode;
import dev.krona.urbex.format.palette.ResolvedTrait;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.format.palette.TraitType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

/** Validates each possible target of a declared loot or spawner decorator. */
final class DecoratorTargets {

    private DecoratorTargets() {
    }

    static void validate(ResolvedNode owner, Identifier decorator, Predicate<BlockState> supports,
                         Diag diagnostic, TraitContext context, PointerResolver.Site site,
                         Diagnostics diagnostics) {
        visit(owner, false, decorator, supports, diagnostic, context, site, diagnostics,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void visit(ResolvedNode node, boolean descendant, Identifier decorator,
                              Predicate<BlockState> supports, Diag diagnostic, TraitContext context,
                              PointerResolver.Site site, Diagnostics diagnostics, Set<ResolvedNode> visited) {
        ResolvedTrait own = node.traits().get(decorator);
        if ((descendant && own != null && !own.provenance().inherited()) || !visited.add(node)) {
            // Explicit overrides are checked by their own declaration, with their own selection tree.
            return;
        }
        switch (node.source()) {
            case ResolvedNode.Source.Weighted weighted -> {
                for (int index = 0; index < weighted.choices().size(); index++) {
                    visit(weighted.choices().get(index).node(), true, decorator, supports, diagnostic,
                            context, site.inside("choice " + index), diagnostics, visited);
                }
                return;
            }
            case ResolvedNode.Source.Socket socket -> {
                socket.placements().forEach((placement, choices) -> {
                    for (int index = 0; index < choices.size(); index++) {
                        visit(choices.get(index).node(), true, decorator, supports, diagnostic, context,
                                site.inside("'" + placement.key() + "' candidate " + index), diagnostics, visited);
                    }
                });
                return;
            }
            default -> {
            }
        }

        // A tag is checked before expansion: one incompatible member refuses it, and the diagnostic
        // still names the tag the author wrote. Missing cross-mod blocks and unresolved aliases yield
        // no states, so they remain accepted rather than being mistaken for incompatible blocks.
        if (context.statesOf(node).stream().anyMatch(state -> !supports.test(state))) {
            String written = String.join(", ", context.writtenBlocks(node).stream()
                    .distinct().map(block -> "'" + block + "'").toList());
            diagnostics.error(diagnostic, site.location(), written);
        }

        // Only effective leaf selections matter. A weighted child may override its parent's selection,
        // so walking the parent's replacement independently would validate an unreachable outcome.
        for (ResolvedTrait selection : node.traits().values()) {
            if (selection.type().phase() != TraitType.Phase.SELECTION) {
                continue;
            }
            selection.type().replacementField().ifPresent(field -> {
                ResolvedNode replacement = selection.satellites().get(field);
                if (replacement != null && !visited.contains(replacement)) {
                    PointerResolver.Site through = site.through("'" + selection.id() + "." + field + "'");
                    context.pruneForValidation(replacement, through).ifPresent(pruned ->
                            visit(pruned, true, decorator, supports, diagnostic, context, through,
                                    diagnostics, visited));
                }
            });
        }
    }
}

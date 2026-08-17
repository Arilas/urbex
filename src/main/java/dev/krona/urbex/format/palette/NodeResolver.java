package dev.krona.urbex.format.palette;

import com.mojang.serialization.DataResult;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Stage 3 of {@code LOAD.001}: turns a decoded palette into a resolved one, with every reference,
 * filter, spread and alias expanded.
 * <p>
 * <b>One topological pass, not a fixpoint loop</b> ({@code REF.031}). The graph is over named entries -
 * every {@code $defs} name, every marker, every definitions asset and every entry of another palette a
 * pointer reaches - and each is resolved once, after everything it depends on. Version 1 resolved its
 * one indirection, {@code frompalette}, with a {@code while (dirty)} loop at merge time, because a
 * reference's target might not have been resolved yet; the loop had no answer for a cycle except to stop
 * changing, and no way to name what it had failed to resolve.
 * <p>
 * The order the graph is walked in is a depth-first search from the entries in <b>declaration order</b>,
 * which is what {@code REF.032} needs: "The diagnostic names every node in the cycle, in declaration
 * order, beginning with the node the loader reached first." The recursion carries an explicit stack, so
 * a <em>cycle</em> is found and named rather than followed forever. Depth is still bounded by the JVM's
 * frames and not by a rule: a chain of some sixteen hundred definitions, each referencing the next,
 * overflows the stack instead of loading. Nothing in the corpus is within three orders of magnitude of
 * that - the deepest shipped chain is two - and no rule states a limit, so this records the bound rather
 * than inventing a diagnostic for it.
 * <p>
 * <b>Order within one node</b> ({@code REF.003}, {@code REF.004}, {@code REF.051}, {@code REF.052}):
 * <ol>
 *   <li>{@code $spread} elements in every list are replaced by the lists they name ({@code REF.070}) -
 *       before anything else, because the expanded list is one of the node's own keys and step 3 applies
 *       those over the target;</li>
 *   <li>the {@code $ref}'s target is resolved, in <em>its own</em> document's scope;</li>
 *   <li>the target is filtered by {@code $only} or {@code $without};</li>
 *   <li>this node's own keys replace the filtered target's, key by key;</li>
 *   <li>{@code traits} merge by id, replacing whole ({@code TRAIT.006}).</li>
 * </ol>
 * <p>
 * <b>Completeness is checked where a definition is used</b> ({@code REF.021}, {@code MODEL.081},
 * {@code MODEL.082}). So the walk below completes markers and everything reachable from them, and
 * leaves {@code $defs} entries as resolved {@link RawNode}s: a definition carrying only traits is valid
 * ({@code REF.020}) and is the mechanism the format uses for sharing a trait.
 * <p>
 * An instance holds the memo for one pass and is discarded with it, which is {@code LOAD.031}:
 * "Compilation may not retain state in static fields that outlive the compiled palette." Version 1 held
 * two static interning pools, written from a decoding worker pool, that nothing ever emptied.
 */
public final class NodeResolver {

    /**
     * A palette after stage 3: resolved definitions, and markers that resolve to a block source.
     *
     * @param defs    every {@code $defs} entry with its operands gone. Still {@link RawNode}s, by
     *                {@code MODEL.082} - a definition need not resolve to a block source. Kept because
     *                {@code MERGE.003} merges {@code $defs} by name down the chain and a descendant may
     *                point at one
     * @param palette every marker, resolved and complete ({@code MODEL.081})
     */
    public record ResolvedPalette(Map<String, RawNode> defs, Map<Marker, ResolvedNode> palette) {

        public ResolvedPalette(Map<String, RawNode> defs, Map<Marker, ResolvedNode> palette) {
            this.defs = Map.copyOf(defs);
            this.palette = Map.copyOf(palette);
        }
    }

    private final ResolutionScope fileScope;
    private final Diagnostics diagnostics;

    /**
     * Entry name to its resolved form, or to empty when resolving it failed.
     * <p>
     * A failure is memoised as deliberately as a success. Two markers referencing one broken definition
     * is one mistake, and the second one must not report it again; more importantly, a node whose
     * dependency failed must not have anything <em>derived</em> reported about it - the reason
     * {@link RawNode}'s {@code validatedWhenComplete} exists, one stage earlier. A cycle would otherwise
     * be followed by a {@code DIAG.011} about every marker that referenced it.
     */
    private final Map<String, Optional<RawNode>> resolved = new LinkedHashMap<>();

    /** The entries currently being resolved, outermost first - {@code REF.032}'s cycle, when it bites. */
    private final List<String> stack = new ArrayList<>();

    /**
     * A declared trait to its completed form, or to empty when a satellite of it failed.
     * <p>
     * Keyed by the trait value, which has record equality all the way down to its satellite
     * {@link RawNode}s, so two markers that reference one definition carrying one bad satellite report
     * it once. Discarded with the pass, like {@link #resolved} and for the same reason
     * ({@code LOAD.031}).
     */
    private final Map<Trait, Optional<ResolvedTrait>> traits = new LinkedHashMap<>();

    private boolean failed;

    private NodeResolver(ResolutionScope fileScope, Diagnostics diagnostics) {
        this.fileScope = fileScope;
        this.diagnostics = diagnostics;
    }

    /** Resolves {@code file} on its own, with no other asset reachable. */
    public static Optional<ResolvedPalette> resolve(PaletteV2Definition file,
                                                    Diagnostics diagnostics) {
        return resolve(file, DefinitionIndex.empty(), Map.of(), diagnostics);
    }

    /**
     * Resolves {@code file} against the assets a pointer in it may reach.
     * <p>
     * A file on its own is a chain of one, folded by {@link V2Chain} like any other, so that a file with
     * no {@code extends} and a file at the end of a chain of four go through the same code. That is also
     * where {@code MERGE.007} refuses a chain declaring no {@code palette}, which is why this can now
     * refuse a document that decoded cleanly.
     *
     * @param registry the {@code definitions} registry ({@code REF.010})
     * @param palettes every other decoded version 2 palette, by id ({@code LOAD.025})
     * @return the resolved palette, or empty when a diagnostic refused it - never a partial one
     */
    public static Optional<ResolvedPalette> resolve(PaletteV2Definition file,
                                                    DefinitionIndex registry,
                                                    Map<Identifier, PaletteV2Definition> palettes,
                                                    Diagnostics diagnostics) {
        return V2Chain.merge(file, diagnostics)
                .flatMap(merged -> resolve(merged, registry, palettes, diagnostics));
    }

    /**
     * Resolves a merged {@code extends} chain - stage 3 over what stage 2 produced.
     *
     * @param merged the folded chain ({@code MERGE.001}), whose document is what every pointer in it is
     *               answered against
     */
    public static Optional<ResolvedPalette> resolve(V2Chain.MergedPalette merged,
                                                    DefinitionIndex registry,
                                                    Map<Identifier, PaletteV2Definition> palettes,
                                                    Diagnostics diagnostics) {
        NodeResolver resolver = new NodeResolver(
                new ResolutionScope(merged.document(), registry, palettes, Optional.empty()),
                diagnostics);
        return resolver.file(merged);
    }

    /**
     * Resolves one node in a position that requires a block source - the signature the brief for this
     * task specified.
     * <p>
     * A convenience over the file-level pass for a caller holding one node: the scope's own
     * {@code $defs} are still resolved in dependency order, because this node's {@code $ref} may name
     * one that names another.
     */
    public static Optional<ResolvedNode> resolve(RawNode node, ResolutionScope scope,
                                                 Diagnostics diagnostics) {
        NodeResolver resolver = new NodeResolver(scope, diagnostics);
        PointerResolver.Site site = PointerResolver.Site.wholeAsset(scope);
        return resolver.node(node, scope, site)
                .flatMap(flat -> resolver.complete(flat, Map.of(), Origin.of(node),
                        reached(site, node.ref())));
    }

    /**
     * The site a completeness diagnostic is reported at: the entry, plus the {@code $ref} it came
     * through.
     * <p>
     * {@code DIAG.011}'s row carries a {@code <via>} slot and {@code DIAG.901} requires it generally -
     * "Every diagnostic names the reference chain the failing node was reached through, when it was
     * reached through one". A marker that resolves to nothing was reached through its own {@code $ref},
     * and naming it is what turns "this marker has no block" into "and here is the file to edit".
     */
    private static PointerResolver.Site reached(PointerResolver.Site site, Optional<String> from) {
        return from.map(pointer -> site.through("'" + pointer + "'")).orElse(site);
    }

    private Optional<ResolvedPalette> file(V2Chain.MergedPalette merged) {
        Map<String, RawNode> defs = new LinkedHashMap<>();
        // Declaration order, so that DIAG.032 begins its cycle at the node the loader reached first.
        for (Map.Entry<String, MergedEntry> def : merged.defs().entrySet()) {
            ResolutionScope scope = entryScope(def.getValue());
            entry(fileScope.document().defKey(def.getKey()), def.getValue().node(), scope,
                    PointerResolver.Site.definition(scope, def.getKey()))
                    .ifPresent(value -> defs.put(def.getKey(), value));
        }

        Map<Marker, ResolvedNode> palette = new LinkedHashMap<>();
        for (Map.Entry<Marker, MergedEntry> marker : merged.palette().entrySet()) {
            ResolutionScope scope = entryScope(marker.getValue());
            PointerResolver.Site site = PointerResolver.Site.marker(scope, marker.getKey());
            RawNode node = marker.getValue().node();
            Origin origin = Origin.of(node);
            entry(fileScope.document().markerKey(marker.getKey()), node, scope, site)
                    // A marker inherits nothing: it is nobody's alternative, so TRAIT.005 has no
                    // parent to draw from.
                    .flatMap(flat -> complete(flat, Map.of(), origin, reached(site, origin.ref())))
                    .ifPresent(value -> palette.put(marker.getKey(), value));
        }
        return failed ? Optional.empty() : Optional.of(new ResolvedPalette(defs, palette));
    }

    /**
     * The scope one entry resolves in: the document's, plus the two things the merge keeps per entry.
     * <p>
     * Per-entry rather than per-file because {@code REF.061} scopes {@code $super} to the entry it
     * appears in, and because {@code REF.086} scopes an alias to the file that wrote the entry rather
     * than to the file at the end of the chain. Both live on {@link MergedEntry}; this is the seam
     * {@code MERGE.001} fills, and it returned a scope inheriting nothing until there was a chain to
     * fill it with.
     */
    private ResolutionScope entryScope(MergedEntry entry) {
        return fileScope.forEntry(entry);
    }

    /**
     * Resolves one named entry, memoised, with cycle detection.
     * <p>
     * The stack is a list rather than a {@link java.util.Deque} because {@code REF.032} needs its
     * contents in order, from the node the loader reached first, and a set for membership would be a
     * second structure to keep in step. Palettes have tens of entries, so the linear scan is not worth
     * removing.
     */
    private Optional<RawNode> entry(String key, RawNode node, ResolutionScope scope,
                                    PointerResolver.Site site) {
        Optional<RawNode> memoised = resolved.get(key);
        if (memoised != null) {
            return memoised;
        }
        int at = stack.indexOf(key);
        if (at >= 0) {
            List<String> cycle = new ArrayList<>(stack.subList(at, stack.size()));
            cycle.add(key);
            diagnostics.error(Diag.DIAG_032, site.asset(), String.join(" → ", cycle));
            failed = true;
            return Optional.empty();
        }
        stack.add(key);
        Optional<RawNode> value;
        try {
            value = node(node, scope, site);
        } finally {
            stack.remove(stack.size() - 1);
        }
        resolved.put(key, value);
        return value;
    }

    /** One node's tree: spreads, then children, then its own {@code $ref}. */
    private Optional<RawNode> node(RawNode node, ResolutionScope scope,
                                   PointerResolver.Site site) {
        // Every list of this node is resolved before any failure is acted on, by DIAG.903: a node whose
        // 'floor' and 'wall' each hold a broken pointer is two mistakes, and reporting the first and
        // stopping is two load-fail-edit cycles. The same reason RawChoice checks a whole list.
        boolean ok = true;
        Optional<List<RawChoice>> choices = Optional.empty();
        if (node.choices().isPresent()) {
            choices = list(node.choices().get(), scope, site, "choice");
            ok = choices.isPresent();
        }
        Map<Kind.Placement, List<RawChoice>> placements = new LinkedHashMap<>();
        for (Map.Entry<Kind.Placement, List<RawChoice>> placement : node.placements().entrySet()) {
            Optional<List<RawChoice>> candidates = list(placement.getValue(), scope,
                    site.inside("'" + placement.getKey().key() + "'"), "candidate");
            if (candidates.isEmpty()) {
                ok = false;
                continue;
            }
            placements.put(placement.getKey(), candidates.get());
        }
        // TRAIT.009: a block-valued trait field holds a node, so a satellite's own $ref, $spread and
        // filters are expanded here exactly as a choice's are. This is what VER.016 refused outright
        // while a trait payload was opaque and nothing could say which of its fields were nodes.
        Map<Identifier, Trait> traits = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Trait> declared : node.traits().entrySet()) {
            Optional<Trait> resolvedTrait = satellitesOf(declared.getValue(), scope, site);
            if (resolvedTrait.isEmpty()) {
                ok = false;   // DIAG.903: every trait of this node is walked before it fails
                continue;
            }
            traits.put(declared.getKey(), resolvedTrait.get());
        }
        if (!ok) {
            return Optional.empty();
        }

        Fields own = new Fields(node.kind(), node.block(), choices, node.tag(), node.aliasOf(),
                placements, traits);
        if (node.ref().isEmpty()) {
            return Optional.of(own.node());
        }
        return referenced(node, own, scope, site);
    }

    /**
     * One trait with the operands inside its satellites expanded ({@code TRAIT.009}).
     * <p>
     * Which fields hold nodes is {@link TraitType#blockValuedFields()}'s answer and not a guess, which is
     * the whole reason {@code TRAIT.090} requires the declaration: a trait that declares a block-valued
     * field gets its satellite resolved, and one that forgets gets a satellite with an unexpanded
     * {@code $ref} that fails loudly at completion rather than a silently blank one.
     * <p>
     * The site names the trait and the field, so a broken pointer inside {@code urbex:damaged.into}
     * reads as such rather than as a failure of the marker.
     */
    private Optional<Trait> satellitesOf(Trait trait, ResolutionScope scope,
                                         PointerResolver.Site site) {
        Map<String, RawNode> written = trait.satellites();
        if (written.isEmpty()) {
            return Optional.of(trait);
        }
        Map<String, RawNode> expanded = new LinkedHashMap<>();
        boolean ok = true;
        for (Map.Entry<String, RawNode> field : written.entrySet()) {
            Optional<RawNode> value = node(field.getValue(), scope,
                    satelliteSite(site, trait, field.getKey()));
            if (value.isEmpty()) {
                ok = false;
                continue;
            }
            expanded.put(field.getKey(), value.get());
        }
        return ok ? Optional.of(trait.withSatellites(expanded)) : Optional.empty();
    }

    /** Where a satellite's diagnostics are reported: the owning node, the trait, and the field. */
    private static PointerResolver.Site satelliteSite(PointerResolver.Site site, Trait trait,
                                                      String field) {
        return site.inside("trait '" + trait.id() + "' '" + field + "'");
    }

    /**
     * {@code REF.002}: the referenced node is the base, and the referring node's own keys are applied
     * over it.
     */
    private Optional<RawNode> referenced(RawNode node, Fields own, ResolutionScope scope,
                                         PointerResolver.Site site) {
        String written = node.ref().orElseThrow();
        Optional<Reached> reached = reach(written, "'$ref'", scope, site);
        if (reached.isEmpty()) {
            return Optional.empty();
        }
        Reached found = reached.orElseThrow();
        if (!(found.target() instanceof PointerResolver.Target.Node target)) {
            return fail(Diag.DIAG_034, site.location(), found.describe(),
                    "no node at '/" + String.join("/", found.path()) + "'",
                    "A '$ref' names a node, and a " + describe(found.target()) + " is not one.");
        }
        Fields base = Fields.of(target.node()).filter(node.only(), node.without());
        return Optional.of(base.overlay(own).node());
    }

    /** A pointer followed to a value: what it was, where it pointed, and what stands there. */
    private record Reached(Pointer pointer, String written, List<String> path,
                           PointerResolver.Target target) {

        /** The pointer as a diagnostic names it ({@code REF.085}). */
        String describe() {
            return Pointer.describe(written, pointer);
        }
    }

    /**
     * Parses a pointer, resolves the entry it names, and reads the rest of its path.
     * <p>
     * The whole of a pointer's resolution in one place, because the failures are one story: the alias is
     * not declared ({@code DIAG.039}), the name is in no tier ({@code DIAG.030}), the asset or the path is
     * absent ({@code DIAG.034}), the entry inherits nothing ({@code DIAG.036}), or the entry it names is
     * part of a cycle ({@code DIAG.032}). Each is reported once, by whoever found it, and this returns
     * empty without adding a second sentence about the same mistake. What the caller does with the value
     * differs - {@code $ref} needs a node, {@code $spread} needs a list - and that is the one decision
     * left to them.
     */
    private Optional<Reached> reach(String written, String operand, ResolutionScope scope,
                                    PointerResolver.Site site) {
        Optional<Pointer> parsed = Pointer.parse(written, scope.document().imports(), site.location())
                .reportInto(diagnostics);
        if (parsed.isEmpty()) {
            failed = true;
            return Optional.empty();
        }
        Pointer pointer = parsed.orElseThrow();
        Optional<PointerResolver.Addressed> addressed =
                PointerResolver.address(pointer, written, operand, scope, site)
                        .reportInto(diagnostics);
        if (addressed.isEmpty()) {
            failed = true;
            return Optional.empty();
        }
        PointerResolver.Addressed entry = addressed.orElseThrow();
        Optional<RawNode> value = entry(entry.key(), entry.node(), entry.scope(),
                site.through(Pointer.describe(written, pointer)));
        if (value.isEmpty()) {
            // The entry said why - a cycle, or a failure of its own. A second sentence about this
            // pointer would describe a consequence and not a mistake.
            failed = true;
            return Optional.empty();
        }
        DataResult<PointerResolver.Target> at = PointerResolver.walk(value.get(), entry.path());
        if (at.error().isPresent()) {
            // The path names nothing, which is REF.045 whichever operand asked: DIAG.037 is for a
            // pointer that names something other than a list, and this one names nothing at all.
            return fail(Diag.DIAG_034, site.location(), Pointer.describe(written, pointer),
                    at.error().get().message(), "The entry exists; the path into it does not.");
        }
        return Optional.of(new Reached(pointer, written, entry.path(),
                at.result().orElseThrow()));
    }

    /**
     * {@code REF.070}: a {@code $spread} element is replaced by the elements of the list it names, in
     * order, at that position.
     * <p>
     * Positional, by {@code REF.073}: the list is rebuilt element by element, so several spreads compose
     * and what surrounds each one keeps its place. That is the whole difference from version 1's
     * {@code {"replace": false, "values": [...]}}, which could only append and only from the parent.
     */
    private Optional<List<RawChoice>> list(List<RawChoice> written, ResolutionScope scope,
                                           PointerResolver.Site site, String position) {
        List<RawChoice> expanded = new ArrayList<>();
        boolean ok = true;
        for (RawChoice choice : written) {
            if (choice.node().spread().isEmpty()) {
                expanded.add(choice);
                continue;
            }
            String pointer = choice.node().spread().orElseThrow();
            Optional<List<RawChoice>> spread = spread(pointer, scope, site);
            if (spread.isEmpty()) {
                ok = false;
                continue;
            }
            // Each incoming element is stamped with the pointer this file wrote, overwriting whatever
            // provenance it carried in its own document: WEIGHT.019's message divides a total into what
            // the reader can see in the file they are editing and what arrived from somewhere else, and
            // "somewhere else" is this pointer whether or not the list it names was itself assembled.
            spread.get().forEach(element -> expanded.add(element.broughtInBy(pointer)));
        }
        if (!ok) {
            return Optional.empty();
        }

        List<RawChoice> resolvedChoices = new ArrayList<>();
        for (int index = 0; index < expanded.size(); index++) {
            RawChoice choice = expanded.get(index);
            Optional<RawNode> value =
                    node(choice.node(), scope, site.inside(position + " " + index));
            if (value.isEmpty()) {
                // Collected, not returned: two broken pointers in one list are two mistakes.
                ok = false;
                continue;
            }
            resolvedChoices.add(new RawChoice(value.get(), choice.size(), choice.when(),
                    choice.spreadFrom()));
        }
        return ok ? Optional.of(List.copyOf(resolvedChoices)) : Optional.empty();
    }

    /** The list a {@code $spread} names, or empty with {@code DIAG.037} when it names something else. */
    private Optional<List<RawChoice>> spread(String written, ResolutionScope scope,
                                             PointerResolver.Site site) {
        Optional<Reached> reached = reach(written, "'$spread'", scope, site);
        if (reached.isEmpty()) {
            return Optional.empty();
        }
        Reached found = reached.orElseThrow();
        if (found.target() instanceof PointerResolver.Target.Choices choices) {
            return Optional.of(choices.choices());
        }
        // REF.071. The <kind> slot holds what the pointer did name, because that is the sentence that
        // tells the author whether to move the pointer or to change the node it lands on.
        return fail(Diag.DIAG_037, site.location(), found.describe(), describe(found.target()));
    }

    /**
     * {@code MODEL.080}: reads the one block source this node has, or refuses it.
     * <p>
     * {@code MODEL.081} is the refusal, and it applies "in a marker position, a {@code choices} entry or
     * a socket candidate" - so this recurses into both kinds of alternative and into nothing else.
     * {@code MODEL.031} is why it goes no further: a satellite is not an alternative, so the nodes inside
     * a trait payload are not completed here, and by {@code TRAIT.007} they inherit nothing to complete
     * them with either.
     *
     * @param inherited the traits of the node this one is an alternative of ({@code TRAIT.005}), empty
     *                  for a marker, for a {@code $defs} entry used directly and - by
     *                  {@code TRAIT.007} - for every satellite
     * @param origin    where this node's keys came from, for {@code DIAG.011}'s subject
     */
    private Optional<ResolvedNode> complete(RawNode node, Map<Identifier, ResolvedTrait> inherited,
                                            Origin origin, PointerResolver.Site site) {
        Kind kind = node.kind().orElse(Kind.BLOCK);   // MODEL.011
        if (!coherent(node, kind, site)) {
            return Optional.empty();
        }
        Map<Identifier, ResolvedTrait> traits = effective(node.traits(), inherited, site);
        if (traits == null) {
            return silently();
        }
        return switch (kind) {
            case BLOCK -> node.block()
                    .map(block -> built(kind, new ResolvedNode.Source.Block(block), traits))
                    .orElseGet(() -> noBlockSource(node, kind, "block", origin, site));
            case TAG -> node.tag()
                    .map(tag -> built(kind, new ResolvedNode.Source.Tag(tag), traits))
                    .orElseGet(() -> noBlockSource(node, kind, "tag", origin, site));
            case ALIAS -> node.aliasOf()
                    .map(of -> built(kind, new ResolvedNode.Source.Alias(of), traits))
                    .orElseGet(() -> noBlockSource(node, kind, "of", origin, site));
            case WEIGHTED -> weighted(node, traits, origin, site);
            case LIGHT_SOCKET -> socket(node, traits, site);
        };
    }

    /**
     * {@code TRAIT.005} and {@code TRAIT.006}: what a node inherits, then what it declares.
     * <p>
     * The inherited set is copied through {@link ResolvedTrait.Provenance#inheritedForm()} rather than
     * reused, because a trait's provenance is what tells the two apart afterwards - the resolution
     * report prints "the node it was inherited from" ({@code LOAD.050}) and {@code TRAIT.052} is asked
     * of the node that <em>declared</em> the trait rather than of every alternative that inherited it.
     * <p>
     * A declared trait replaces the inherited one <b>whole</b>, by id ({@code TRAIT.006}): "Trait objects
     * are never deep-merged. […] A keyed replace has one answer to 'what survived'; a deep merge has one
     * answer per field, and the reader has to know the shape of the trait to predict it." That is also
     * the whole of {@code TRAIT.055} - a socket candidate's own {@code urbex:light.unlit} beats the
     * socket's because it replaced the socket's trait, not because sockets are special.
     *
     * @return the merged set, or {@code null} when a declared trait's satellite could not be resolved
     */
    private Map<Identifier, ResolvedTrait> effective(Map<Identifier, Trait> declared,
                                                     Map<Identifier, ResolvedTrait> inherited,
                                                     PointerResolver.Site site) {
        if (declared.isEmpty() && inherited.isEmpty()) {
            return Map.of();
        }
        Map<Identifier, ResolvedTrait> traits = new LinkedHashMap<>();
        inherited.forEach((id, trait) -> traits.put(id, new ResolvedTrait(trait.type(),
                trait.value(), trait.satellites(), trait.provenance().inheritedForm())));
        boolean ok = true;
        for (Map.Entry<Identifier, Trait> own : declared.entrySet()) {
            Optional<ResolvedTrait> resolvedTrait = trait(own.getValue(), site);
            if (resolvedTrait.isEmpty()) {
                ok = false;
                continue;
            }
            traits.put(own.getKey(), resolvedTrait.get());
        }
        return ok ? traits : null;
    }

    /**
     * One declared trait, with its satellites completed ({@code TRAIT.009}) and checked
     * ({@code MODEL.033}).
     * <p>
     * <b>A satellite inherits nothing</b> ({@code TRAIT.007}), which is why {@link #complete} is called
     * with an empty inherited set here and is the one place in this class where that is a rule rather
     * than an absence of a parent. Without it "an {@code unlit} satellite would inherit
     * {@code urbex:light} from the node it replaces, and so be an optional light whose own replacement
     * is an optional light, without termination".
     * <p>
     * Memoised by trait value, so a definition carrying a broken satellite that two markers reference is
     * one diagnostic rather than two - the same reason {@link #resolved} memoises a failed entry. The
     * site recorded is the first one that asked, which is also the first one an author would edit.
     */
    private Optional<ResolvedTrait> trait(Trait trait, PointerResolver.Site site) {
        Optional<ResolvedTrait> memoised = traits.get(trait);
        if (memoised != null) {
            return memoised;
        }
        Map<String, ResolvedNode> satellites = new LinkedHashMap<>();
        boolean ok = true;
        for (Map.Entry<String, RawNode> field : trait.satellites().entrySet()) {
            PointerResolver.Site at = satelliteSite(site, trait, field.getKey());
            Optional<ResolvedNode> completed =
                    // Origin.satellite: the satellite reaching here has already had its own $ref
                    // applied by satellitesOf, so the node in hand cannot say which key came from where
                    // and DIAG.011 reads its subject off the node itself - but it does know it is in a
                    // trait field, which is what MODEL.081's fourth position needs for its remedy.
                    complete(field.getValue(), Map.of(), Origin.inTraitField(), at);
            if (completed.isEmpty()) {
                ok = false;
                continue;
            }
            if (completed.get().kind() == Kind.LIGHT_SOCKET) {
                // MODEL.033: a socket defers placement so it can search for a support, and a satellite
                // is written at a position already decided.
                diagnostics.error(Diag.DIAG_005, at.location(), "'" + field.getKey() + "'");
                failed = true;
                ok = false;
                continue;
            }
            satellites.put(field.getKey(), completed.get());
        }
        Optional<ResolvedTrait> value = ok
                ? Optional.of(new ResolvedTrait(trait.type(), trait.value(), satellites,
                        new ResolvedTrait.Provenance(site.location(), site.via(), false)))
                : Optional.empty();
        traits.put(trait, value);
        return value;
    }

    private Optional<ResolvedNode> built(Kind kind, ResolvedNode.Source source,
                                         Map<Identifier, ResolvedTrait> traits) {
        return Optional.of(new ResolvedNode(kind, source, traits));
    }

    /**
     * {@code MODEL.081}: this node is in a position that needs a block source and has none.
     * <p>
     * <b>Every phrasing below is true of the file it describes,</b> which took two goes. The first
     * version said "{@code <def>} declares only traits" always, which is false of a node that declared a
     * kind; the second added the kind case and still attributed the absence to the {@code $ref} as
     * written, which is false whenever a filter is what dropped it - {@code $without: ["block"]} against
     * a definition that <em>does</em> declare {@code block} said "'d' declares no 'block'". So the
     * subject is chosen by what can be proved from the {@link Origin}, in this order:
     * <ol>
     *   <li>a filter was written: it kept no block-placing key, which is necessarily true - the merged
     *       node has no block source, and a node's own keys only override, never remove, so nothing the
     *       filter kept placed a block;</li>
     *   <li>the marker declared the kind itself: the sentence is about the marker, not about a definition
     *       that may never have mentioned a kind at all;</li>
     *   <li>everything came from a {@code $ref} unfiltered: absence in the merged node implies absence in
     *       the target, so the definition is the right subject and the fix is in its file;</li>
     *   <li>no provenance at all - a list element, whose pointer belongs to the entry - so the subject is
     *       the resolved node, read off the node itself and therefore true of it.</li>
     * </ol>
     *
     * <b>The remedy is chosen the same way the clause is.</b> {@code MODEL.081} covers a block-valued
     * trait field as of this task, and "give this marker a {@code block} … as well" is false there - the
     * marker has a block, and the satellite is what does not. A row whose remedy cannot be followed is
     * a row that fails {@code DIAG.900}, which requires the remedy as much as the finding.
     *
     * @param required the key this kind needs and does not have
     */
    private Optional<ResolvedNode> noBlockSource(RawNode node, Kind kind, String required,
                                                 Origin origin, PointerResolver.Site site) {
        String key = "'" + required + "'";
        String def = origin.ref().map(pointer -> "'" + pointer + "'").orElse("it");
        String remedy = origin.satellite()
                ? "give this trait field a block, or a weighted list of them"
                : "give this marker a 'block', 'choices', 'tag' or 'alias' as well";
        String clause;
        if (origin.filter().isPresent() && origin.ref().isPresent()) {
            clause = origin.filter().orElseThrow() + " kept no key of " + def
                    + " that places a block";
        } else if (origin.ownKind()) {
            clause = "this marker declares kind " + kind.key() + " and no " + key;
        } else if (node.kind().isPresent()) {
            clause = def + " declares kind " + kind.key() + " and no " + key;
        } else if (!node.traits().isEmpty()) {
            clause = def + " declares only traits";
        } else {
            clause = def + " declares no " + key;
        }
        diagnostics.error(Diag.DIAG_011, site.location(), clause, remedy);
        failed = true;
        return Optional.empty();
    }

    /**
     * Where a node's keys came from, as far as {@code DIAG.011} needs to attribute their absence.
     * <p>
     * Read off the node <em>as written</em>, before its {@code $ref} was applied, because that is the only
     * place the answer survives: the merged node cannot say whether its missing {@code block} was never in
     * the target or was dropped by a {@code $without}.
     *
     * @param ref     the {@code $ref} this node was reached through, which {@code DIAG.011} names as
     *                {@code <def>}
     * @param filter  {@code '$only'} or {@code '$without'} when one was written
     * @param ownKind   whether the node declared its own {@code kind}, rather than taking one from its
     *                  target
     * @param satellite whether this node stands in a block-valued trait field, which decides
     *                  {@code DIAG.011}'s <em>remedy</em>: the marker already has a block, and telling
     *                  its author to give it one is advice they cannot follow
     */
    private record Origin(Optional<String> ref, Optional<String> filter, boolean ownKind,
                          boolean satellite) {

        static Origin of(RawNode written) {
            Optional<String> filter = written.only().isPresent()
                    ? Optional.of("'$only'")
                    : written.without().map(ignored -> "'$without'");
            return new Origin(written.ref(), filter, written.kind().isPresent(), false);
        }

        /** A node whose provenance this pass did not keep - an element of a resolved list. */
        static Origin unknown() {
            return new Origin(Optional.empty(), Optional.empty(), false, false);
        }

        /** A node in a block-valued trait field ({@code MODEL.081}'s fourth position). */
        static Origin inTraitField() {
            return new Origin(Optional.empty(), Optional.empty(), false, true);
        }
    }

    /** Fails without a word, because whatever failed underneath has already said so. */
    private Optional<ResolvedNode> silently() {
        failed = true;
        return Optional.empty();
    }

    /**
     * {@code MODEL.013} over the merged node: the kind-specific keys of one kind are not accepted on
     * another.
     * <p>
     * Checked here as well as at decode because a node carrying {@code $ref} is checked at decode
     * against the union of every kind's keys - its kind is not knowable until the reference is resolved.
     * {@code REF.054}'s {@code > Why} describes exactly the case this catches: taking a node's traits
     * while supplying a different block, written without a filter, so that "the target's
     * {@code kind: weighted} arrives, the sibling {@code block} is declared, and MODEL.013 refuses the
     * result". The filter is what makes that intent sayable, and this is what makes writing it without
     * one an error rather than a silently dropped key.
     */
    private boolean coherent(RawNode node, Kind kind, PointerResolver.Site site) {
        Set<String> allowed = new LinkedHashSet<>(RawNode.COMMON_KEYS);
        allowed.addAll(kind.ownKeys());
        boolean coherent = true;
        for (String key : node.presentNodeKeys()) {
            if (!allowed.contains(key)) {
                diagnostics.error(Diag.DIAG_003, site.location(), key,
                        "a " + kind.key() + " node once its '$ref' is resolved");
                coherent = false;
            }
        }
        if (!coherent) {
            failed = true;
        }
        return coherent;
    }

    /**
     * A {@code weighted} node's source, or the reason it has none.
     * <p>
     * The two ways of having none are two different rules, and each gets its own. A node with <em>no</em>
     * {@code choices} lacks a required key of its kind, which is {@code MODEL.081}/{@code DIAG.011}; a
     * node whose {@code choices} is present and empty is {@code MODEL.045}/{@code DIAG.007}, whose own
     * words are "a weighted node declares no choices". Both were {@code DIAG.007} until the review of
     * this task, because {@code DIAG.011} could then only say "declares only traits" - the code named the
     * wrong rule to avoid printing a false sentence, which is the wrong way round.
     */
    private Optional<ResolvedNode> weighted(RawNode node, Map<Identifier, ResolvedTrait> traits,
                                            Origin origin, PointerResolver.Site site) {
        if (node.choices().isEmpty()) {
            return noBlockSource(node, Kind.WEIGHTED, "choices", origin, site);
        }
        List<RawChoice> choices = node.choices().orElseThrow();
        if (choices.isEmpty()) {
            // MODEL.045, and reachable here as well as at decode: a list of nothing but a $spread of an
            // empty list is empty only once the spread has been expanded.
            diagnostics.error(Diag.DIAG_007, site.location());
            return silently();
        }
        List<ResolvedNode.Choice> resolved = alternatives(choices, traits, site, "choice");
        return resolved == null
                ? silently()
                : built(Kind.WEIGHTED, new ResolvedNode.Source.Weighted(resolved), traits);
    }

    /**
     * A {@code light_socket}'s source, or the reason it has none.
     * <p>
     * <b>Unlike {@link #weighted}, a socket with no list at all is still {@code DIAG.010},</b> and that
     * is the rule speaking rather than the message: {@code MODEL.072} refuses "a {@code light_socket}
     * declaring no candidate in any of the four lists", and a socket that declares no list declares no
     * candidate in any of them. There is no separate missing-required-key case to route to
     * {@code MODEL.081}, because a socket's required key is not one key but a candidate somewhere among
     * four. A socket reaches here with no list at all only when its kind arrived through a {@code $ref},
     * since a written one is refused at decode.
     */
    private Optional<ResolvedNode> socket(RawNode node, Map<Identifier, ResolvedTrait> traits,
                                          PointerResolver.Site site) {
        Map<Kind.Placement, List<ResolvedNode.Choice>> placements = new LinkedHashMap<>();
        boolean any = false;
        boolean ok = true;
        for (Map.Entry<Kind.Placement, List<RawChoice>> placement : node.placements().entrySet()) {
            List<ResolvedNode.Choice> candidates = alternatives(placement.getValue(), traits,
                    site.inside("'" + placement.getKey().key() + "'"), "candidate");
            if (candidates == null) {
                ok = false;   // DIAG.903: every list of this socket is checked before it fails
                continue;
            }
            placements.put(placement.getKey(), candidates);
            any |= !candidates.isEmpty();
        }
        if (!ok) {
            return silently();
        }
        if (!any) {
            diagnostics.error(Diag.DIAG_010, site.location());   // MODEL.072, likewise
            return silently();
        }
        return built(Kind.LIGHT_SOCKET, new ResolvedNode.Source.Socket(placements), traits);
    }

    /**
     * Every alternative of a list, completed, or {@code null} when one of them was refused.
     *
     * @param inherited this list's owner's traits, which every element of it inherits
     *                  ({@code TRAIT.005}). A {@code choices} entry and a socket candidate are both
     *                  <em>alternatives</em> by {@code MODEL.030}, which is exactly the set of positions
     *                  that rule names, and is why one method serves both
     */
    private List<ResolvedNode.Choice> alternatives(List<RawChoice> choices,
                                                   Map<Identifier, ResolvedTrait> inherited,
                                                   PointerResolver.Site site, String position) {
        List<ResolvedNode.Choice> resolvedChoices = new ArrayList<>();
        boolean ok = true;
        for (int index = 0; index < choices.size(); index++) {
            RawChoice choice = choices.get(index);
            Optional<ResolvedNode> value = complete(choice.node(), inherited, Origin.unknown(),
                    site.inside(position + " " + index));
            if (value.isEmpty()) {
                ok = false;
                continue;
            }
            resolvedChoices.add(new ResolvedNode.Choice(value.get(), choice.size(), choice.when(),
                    choice.spreadFrom()));
        }
        return ok ? List.copyOf(resolvedChoices) : null;
    }

    /**
     * Records {@code diag} and fails this resolution.
     * <p>
     * Through {@link Diagnostics#error} rather than {@link Diagnostics#nested}, so that the row travels
     * with the message: {@code nested} is for a failure that <em>has</em> no catalogue row - a message a
     * deeper codec produced, or a DFU type error - and five of resolution's rows went through it until
     * this round, which quietly made {@link Diagnostics#all()} not the list of every catalogue diagnostic
     * recorded. {@link Outcome} carries the row for the steps that find their failure elsewhere.
     */
    private <T> Optional<T> fail(Diag diag, Object... args) {
        diagnostics.error(diag, args);
        failed = true;
        return Optional.empty();
    }

    /** A bare noun for what a pointer landed on, to stand in "names a {@code <kind>}, not a list". */
    private static String describe(PointerResolver.Target target) {
        return switch (target) {
            case PointerResolver.Target.Node node ->
                    node.node().kind().map(Kind::key).orElse("block") + " node";
            case PointerResolver.Target.Choices choices -> "list of " + choices.choices().size();
            case PointerResolver.Target.Other other -> other.description();
        };
    }

    /**
     * A node's keys with no operand among them: what resolution produces, and what it merges.
     * <p>
     * A {@link RawNode} minus {@code $ref}, {@code $only}, {@code $without} and {@code $spread}, which is
     * the shape both halves of {@code REF.003} need - a filter drops keys of it and an overlay replaces
     * keys of it - and which cannot be spelled as a {@code RawNode} without four fields that must be
     * empty and are not checked to be.
     */
    private record Fields(Optional<Kind> kind, Optional<String> block,
                          Optional<List<RawChoice>> choices, Optional<String> tag,
                          Optional<Marker> aliasOf, Map<Kind.Placement, List<RawChoice>> placements,
                          Map<Identifier, Trait> traits) {

        static Fields of(RawNode node) {
            return new Fields(node.kind(), node.block(), node.choices(), node.tag(), node.aliasOf(),
                    node.placements(), node.traits());
        }

        RawNode node() {
            return new RawNode(kind, block, choices, tag, aliasOf,
                    Kind.Placement.ordered(placements),
                    Map.copyOf(traits), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        /**
         * {@code REF.051} and {@code REF.052}: the keys of this target a reference contributes.
         * <p>
         * {@code REF.054} is why these are compared against key names and never split on {@code /}:
         * "{@code $only} and {@code $without} name top-level keys of the target node only. They are not
         * paths; to reach inside a key, point at it with a fragment." So {@code traits} keeps or drops
         * the whole map, and a trait id is not addressable here.
         * <p>
         * {@code DIAG.035} refuses both at once, at decode.
         */
        Fields filter(Optional<List<String>> only, Optional<List<String>> without) {
            if (only.isEmpty() && without.isEmpty()) {
                return this;
            }
            Predicate<String> keep = only
                    .<Predicate<String>>map(keys -> keys::contains)
                    .orElseGet(() -> key -> !without.orElseThrow().contains(key));
            Map<Kind.Placement, List<RawChoice>> kept = new LinkedHashMap<>();
            placements.forEach((placement, candidates) -> {
                if (keep.test(placement.key())) {
                    kept.put(placement, candidates);
                }
            });
            return new Fields(
                    keep.test("kind") ? kind : Optional.empty(),
                    keep.test("block") ? block : Optional.empty(),
                    keep.test("choices") ? choices : Optional.empty(),
                    keep.test("tag") ? tag : Optional.empty(),
                    keep.test("of") ? aliasOf : Optional.empty(),
                    kept,
                    keep.test("traits") ? traits : Map.of());
        }

        /**
         * {@code REF.003}: a key present beside the {@code $ref} replaces the referenced node's value
         * for that key - and {@code REF.004}: {@code traits} beside it merge into the target's by id,
         * replacing whole ({@code TRAIT.006}).
         * <p>
         * Traits are the one exception in the precedence table of {@code 04-merging.md} §2, which says so
         * outright: "Traits are not in this table: they merge by id at each step rather than replacing
         * the set". A keyed replace has one answer to "what survived"; a deep merge has one per field.
         */
        Fields overlay(Fields own) {
            Map<Kind.Placement, List<RawChoice>> merged = new LinkedHashMap<>(placements);
            merged.putAll(own.placements());
            Map<Identifier, Trait> mergedTraits = new LinkedHashMap<>(traits);
            mergedTraits.putAll(own.traits());
            return new Fields(
                    own.kind().or(() -> kind),
                    own.block().or(() -> block),
                    own.choices().or(() -> choices),
                    own.tag().or(() -> tag),
                    own.aliasOf().or(() -> aliasOf),
                    merged,
                    mergedTraits);
        }
    }
}

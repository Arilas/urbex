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
 * a cycle is <em>found</em> - reported once, naming its members - rather than recursed into until the
 * JVM runs out of frames.
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
     *
     * @param registry the {@code definitions} registry ({@code REF.010})
     * @param palettes every other decoded version 2 palette, by id ({@code LOAD.025})
     * @return the resolved palette, or empty when a diagnostic refused it - never a partial one
     */
    public static Optional<ResolvedPalette> resolve(PaletteV2Definition file,
                                                    DefinitionIndex registry,
                                                    Map<Identifier, PaletteV2Definition> palettes,
                                                    Diagnostics diagnostics) {
        NodeResolver resolver =
                new NodeResolver(ResolutionScope.of(file, registry, palettes), diagnostics);
        return resolver.file(file);
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
                .flatMap(flat -> resolver.complete(flat, node.ref(), reached(site, node.ref())));
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

    private Optional<ResolvedPalette> file(PaletteV2Definition file) {
        Map<String, RawNode> defs = new LinkedHashMap<>();
        // Declaration order, so that DIAG.032 begins its cycle at the node the loader reached first.
        for (Map.Entry<String, RawNode> def : file.defs().entrySet()) {
            ResolutionScope scope = entryScope();
            entry(fileScope.document().defKey(def.getKey()), def.getValue(), scope,
                    PointerResolver.Site.definition(scope, def.getKey()))
                    .ifPresent(value -> defs.put(def.getKey(), value));
        }

        Map<Marker, ResolvedNode> palette = new LinkedHashMap<>();
        for (Map.Entry<Marker, RawNode> marker
                : file.palette().orElse(Map.of()).entrySet()) {
            ResolutionScope scope = entryScope();
            PointerResolver.Site site = PointerResolver.Site.marker(scope, marker.getKey());
            Optional<String> from = marker.getValue().ref();
            entry(fileScope.document().markerKey(marker.getKey()), marker.getValue(), scope, site)
                    .flatMap(flat -> complete(flat, from, reached(site, from)))
                    .ifPresent(value -> palette.put(marker.getKey(), value));
        }
        return failed ? Optional.empty() : Optional.of(new ResolvedPalette(defs, palette));
    }

    /**
     * The scope one entry resolves in: the file's, plus what that entry inherited.
     * <p>
     * Always nothing, in this task, which is why it takes no argument yet. {@code REF.060} makes
     * {@code $super} name "what would have stood at this marker or definition name had this file not
     * declared it" - a value the {@code extends} merge produces, and no chain is merged yet. So every
     * entry of every file inherits nothing and {@code REF.062} refuses a {@code $super} written in one,
     * which is the correct answer for a file declaring no {@code extends} and the only kind of file that
     * exists until {@code MERGE.001} is implemented. This method is the seam that merge fills, and it is
     * per-entry rather than per-file because {@code REF.061} scopes {@code $super} to its entry.
     */
    private ResolutionScope entryScope() {
        return fileScope.withInherited(Optional.empty());
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
        Optional<List<RawChoice>> choices = Optional.empty();
        if (node.choices().isPresent()) {
            choices = list(node.choices().get(), scope, site, "choice");
            if (choices.isEmpty()) {
                return Optional.empty();
            }
        }
        Map<Kind.Placement, List<RawChoice>> placements = new LinkedHashMap<>();
        for (Map.Entry<Kind.Placement, List<RawChoice>> placement : node.placements().entrySet()) {
            Optional<List<RawChoice>> candidates = list(placement.getValue(), scope,
                    site.inside("'" + placement.getKey().key() + "'"), "candidate");
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            placements.put(placement.getKey(), candidates.get());
        }

        Fields own = new Fields(node.kind(), node.block(), choices, node.tag(), node.aliasOf(),
                placements, node.traits());
        if (node.ref().isEmpty()) {
            return Optional.of(own.node());
        }
        return referenced(node, own, scope, site);
    }

    /**
     * {@code REF.002}: the referenced node is the base, and the referring node's own keys are applied
     * over it.
     */
    private Optional<RawNode> referenced(RawNode node, Fields own, ResolutionScope scope,
                                         PointerResolver.Site site) {
        String written = node.ref().orElseThrow();
        Optional<Reached> reached = reach(written, scope, site);
        if (reached.isEmpty()) {
            return Optional.empty();
        }
        Reached found = reached.orElseThrow();
        if (!(found.target() instanceof PointerResolver.Target.Node target)) {
            return fail(Diag.DIAG_034.message(site.location(), found.describe(),
                    "no node at '/" + String.join("/", found.path()) + "'",
                    "A '$ref' names a node, and a " + describe(found.target()) + " is not one."));
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
    private Optional<Reached> reach(String written, ResolutionScope scope,
                                    PointerResolver.Site site) {
        DataResult<Pointer> parsed = Pointer.parse(written, scope.document().imports(),
                site.location());
        if (parsed.error().isPresent()) {
            return fail(parsed.error().get().message());
        }
        Pointer pointer = parsed.result().orElseThrow();
        DataResult<PointerResolver.Addressed> addressed =
                PointerResolver.address(pointer, written, scope, site);
        if (addressed.error().isPresent()) {
            return fail(addressed.error().get().message());
        }
        PointerResolver.Addressed entry = addressed.result().orElseThrow();
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
            return fail(Diag.DIAG_034.message(site.location(),
                    Pointer.describe(written, pointer), at.error().get().message(),
                    "The entry exists; the path into it does not."));
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
            Optional<List<RawChoice>> spread = spread(choice.node().spread().orElseThrow(), scope,
                    site);
            if (spread.isEmpty()) {
                ok = false;
                continue;
            }
            expanded.addAll(spread.get());
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
                return Optional.empty();
            }
            resolvedChoices.add(new RawChoice(value.get(), choice.size(), choice.when()));
        }
        return Optional.of(List.copyOf(resolvedChoices));
    }

    /** The list a {@code $spread} names, or empty with {@code DIAG.037} when it names something else. */
    private Optional<List<RawChoice>> spread(String written, ResolutionScope scope,
                                             PointerResolver.Site site) {
        Optional<Reached> reached = reach(written, scope, site);
        if (reached.isEmpty()) {
            return Optional.empty();
        }
        Reached found = reached.orElseThrow();
        if (found.target() instanceof PointerResolver.Target.Choices choices) {
            return Optional.of(choices.choices());
        }
        // REF.071. The <kind> slot holds what the pointer did name, because that is the sentence that
        // tells the author whether to move the pointer or to change the node it lands on.
        return fail(Diag.DIAG_037.message(site.location(), found.describe(),
                describe(found.target())));
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
     * @param from the pointer this node was reached through, which {@code DIAG.011} names as the
     *             definition that "declares only traits"
     */
    private Optional<ResolvedNode> complete(RawNode node, Optional<String> from,
                                            PointerResolver.Site site) {
        Kind kind = node.kind().orElse(Kind.BLOCK);   // MODEL.011
        if (!coherent(node, kind, site)) {
            return Optional.empty();
        }
        return switch (kind) {
            case BLOCK -> node.block()
                    .map(block -> built(kind, new ResolvedNode.Source.Block(block), node))
                    .orElseGet(() -> noBlockSource(from, site));
            case TAG -> node.tag()
                    .map(tag -> built(kind, new ResolvedNode.Source.Tag(tag), node))
                    .orElseGet(() -> noBlockSource(from, site));
            case ALIAS -> node.aliasOf()
                    .map(of -> built(kind, new ResolvedNode.Source.Alias(of), node))
                    .orElseGet(() -> noBlockSource(from, site));
            // Neither takes `from`: a node that declared a kind and lacks its list is DIAG.007 or
            // DIAG.010, whose sentences are about the list rather than about the definition it came
            // through, and neither is DIAG.011.
            case WEIGHTED -> weighted(node, site);
            case LIGHT_SOCKET -> socket(node, site);
        };
    }

    private Optional<ResolvedNode> built(Kind kind, ResolvedNode.Source source, RawNode node) {
        return Optional.of(new ResolvedNode(kind, source, node.traits()));
    }

    /**
     * {@code MODEL.081}: this node is in a position that needs a block source and has none.
     *
     * @param from the {@code $ref} it was reached through, which is what {@code DIAG.011} means by
     *             "{@code <def>} declares only traits". Empty inside a list, where the pointer belongs to
     *             the entry rather than to this element - see the task report, which records that as the
     *             one place the message is less specific than the catalogue row implies
     */
    private Optional<ResolvedNode> noBlockSource(Optional<String> from, PointerResolver.Site site) {
        diagnostics.error(Diag.DIAG_011, site.location(),
                from.map(pointer -> "'" + pointer + "'").orElse("it"));
        failed = true;
        return Optional.empty();
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
     * An absent {@code choices} and one written empty are the same refusal here, {@code DIAG.007}, and
     * deliberately not {@code DIAG.011}: {@code MODEL.045}'s message names the remedy this node actually
     * needs - "a weighted node declares no choices. Give it at least one" - where {@code DIAG.011} would
     * say the node "declares only traits", which is false of a node that declared a kind. Both are
     * {@code MODEL.081} failures; only one of the two sentences is true.
     */
    private Optional<ResolvedNode> weighted(RawNode node, PointerResolver.Site site) {
        List<RawChoice> choices = node.choices().orElse(List.of());
        if (choices.isEmpty()) {
            // MODEL.045. Reachable here as well as at decode: a kind arriving from a $ref makes a node
            // weighted after its keys were checked, and a list of nothing but a $spread of an empty
            // list is empty only once the spread has been expanded.
            diagnostics.error(Diag.DIAG_007, site.location());
            return silently();
        }
        List<ResolvedNode.Choice> resolved = alternatives(choices, site, "choice");
        return resolved == null
                ? silently()
                : built(Kind.WEIGHTED, new ResolvedNode.Source.Weighted(resolved), node);
    }

    /**
     * A {@code light_socket}'s source, or the reason it has none.
     * <p>
     * As with {@link #weighted}, a socket with no candidate anywhere is {@code DIAG.010} and not
     * {@code DIAG.011} - {@code MODEL.072}'s sentence is the true one. A socket reaches here with no
     * placement list at all only when its kind arrived from a {@code $ref}, because a written one is
     * refused at decode.
     */
    private Optional<ResolvedNode> socket(RawNode node, PointerResolver.Site site) {
        Map<Kind.Placement, List<ResolvedNode.Choice>> placements = new LinkedHashMap<>();
        boolean any = false;
        for (Map.Entry<Kind.Placement, List<RawChoice>> placement : node.placements().entrySet()) {
            List<ResolvedNode.Choice> candidates = alternatives(placement.getValue(),
                    site.inside("'" + placement.getKey().key() + "'"), "candidate");
            if (candidates == null) {
                return silently();
            }
            placements.put(placement.getKey(), candidates);
            any |= !candidates.isEmpty();
        }
        if (!any) {
            diagnostics.error(Diag.DIAG_010, site.location());   // MODEL.072, likewise
            return silently();
        }
        return built(Kind.LIGHT_SOCKET, new ResolvedNode.Source.Socket(placements), node);
    }

    /** Every alternative of a list, completed, or {@code null} when one of them was refused. */
    private List<ResolvedNode.Choice> alternatives(List<RawChoice> choices,
                                                   PointerResolver.Site site, String position) {
        List<ResolvedNode.Choice> resolvedChoices = new ArrayList<>();
        boolean ok = true;
        for (int index = 0; index < choices.size(); index++) {
            RawChoice choice = choices.get(index);
            Optional<ResolvedNode> value = complete(choice.node(), Optional.empty(),
                    site.inside(position + " " + index));
            if (value.isEmpty()) {
                ok = false;
                continue;
            }
            resolvedChoices.add(new ResolvedNode.Choice(value.get(), choice.size(), choice.when()));
        }
        return ok ? List.copyOf(resolvedChoices) : null;
    }

    /** Records a message that is already a formatted diagnostic, and fails this resolution. */
    private <T> Optional<T> fail(String message) {
        diagnostics.nested(message);
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
            return new RawNode(kind, block, choices, tag, aliasOf, Map.copyOf(placements),
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

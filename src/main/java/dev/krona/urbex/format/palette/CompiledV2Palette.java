package dev.krona.urbex.format.palette;

import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.palette.traits.Light;
import dev.krona.urbex.varia.Rng;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A version 2 palette, compiled: stages 4 to 8 of {@code LOAD.001}, and the object generation asks.
 * <p>
 * <b>Everything is decided here, and nothing is decided at a position.</b> That is the brief version 2
 * was written to, stated as rules: {@code LOAD.010} ("Every {@code REJECT} rule […] is enforced at
 * load. None is deferred to generation"), {@code LOAD.011} ("No compiled palette can raise a diagnostic
 * during generation"), and the four {@code INVARIANT}s {@code LOAD.040}-{@code LOAD.043} that say what
 * {@link #at} may cost. Version 1 could fail on the first chunk that used a marker, hours after the
 * pack loaded clean, as an exception from a worldgen worker that killed the chunk.
 * <p>
 * <b>The order the stages run in is the specification's, not a convenience.</b> Sizes are checked on
 * the expanded list ({@code WEIGHT.005}); {@code when} and absent blocks then remove alternatives
 * ({@code WEIGHT.020}, {@code WEIGHT.030}); tags expand against this epoch ({@code MODEL.052}); shares
 * are apportioned as exact rationals over the whole tree ({@code WEIGHT.052}); and the one rounding
 * step materialises slots at the root ({@code WEIGHT.040}). Trait validation runs between exclusion and
 * materialisation, because {@code TRAIT.052} asks about "the blocks it resolves to" and an excluded
 * alternative is not one of them.
 * <p>
 * <b>What this deliberately does not decide.</b> {@code MODEL.062} - an {@code alias} whose target no
 * palette defines - is decided where a style's palette groups are merged and not here, because by
 * {@code MODEL.064} that merge "includes markers contributed by palettes this file never mentions". An
 * alias whose target this palette defines is compiled here; one whose target it does not is carried out
 * as a {@link Pending} for the merge to answer, rather than dropped. Dropping it was this class's first
 * shape and it made the question unanswerable: a marker that is silently absent cannot be reported as
 * unresolvable by a later stage, because that stage cannot tell it from a marker the file never wrote.
 */
public final class CompiledV2Palette {

    private final MarkerIndex markers;
    private final CompiledEntry[] entries;
    private final Map<Marker, Pending> pending;

    private CompiledV2Palette(MarkerIndex markers, CompiledEntry[] entries,
                              Map<Marker, Pending> pending) {
        this.markers = markers;
        this.entries = entries;
        this.pending = Collections.unmodifiableMap(new LinkedHashMap<>(pending));
    }

    /**
     * An {@code alias} this palette could not answer on its own, handed to the merge that can.
     * <p>
     * {@code MODEL.064}: "An {@code alias} names a marker and is answered by the merged palette the part
     * is generated with - including markers contributed by palettes this file never mentions." So the
     * target is a {@link Marker} and not an entry, and the traits are this alias's own, already compiled
     * and ready to go over whatever the merge resolves the target to ({@code MODEL.063},
     * {@code TRAIT.006}). A chain of aliases inside one palette has already been walked, so
     * {@link #target} is the first marker this palette does not define and {@link #own} carries every
     * link's traits folded in nearest-first.
     *
     * @param target the marker this alias resolves to, which some palette of the merge must define
     * @param own    the traits to apply over the target's, per slot
     */
    public record Pending(Marker target, TraitSet own) {
    }

    /**
     * The aliases this palette left for the merge ({@code MODEL.062}, {@code MODEL.064}).
     * <p>
     * Empty for every palette whose aliases all name markers it defines itself, which is every palette
     * that is not the shipped side-glass idiom.
     */
    public Map<Marker, Pending> pendingAliases() {
        return pending;
    }

    /**
     * {@code MODEL.063}: {@code base}'s slots with {@code own}'s traits applied over each of them.
     * <p>
     * Public because a {@link Pending} is resolved by the merge rather than here, and the overlay it
     * needs is the same one an in-palette alias got - one implementation, so an alias answered by
     * another palette cannot come out differently from an alias answered by its own.
     *
     * @param interned the merge's interning pool, so {@code LOAD.023} holds across the merged palette
     *                 and not only within each palette that fed it
     */
    public static CompiledEntry overlay(CompiledEntry base, TraitSet own,
                                        Map<TraitSet, TraitSet> interned) {
        if (own.isEmpty()) {
            return base;
        }
        if (base.isSocket()) {
            Map<Kind.Placement, CompiledEntry> placements = new LinkedHashMap<>();
            base.placements().forEach((placement, entry) ->
                    placements.put(placement, CompiledEntry.of(overlaid(entry, own, interned))));
            Map<Identifier, CompiledTrait> merged = new LinkedHashMap<>(base.ownTraits().traits());
            merged.putAll(own.traits());
            TraitSet built = TraitSet.of(merged);
            return CompiledEntry.socket(placements,
                    interned.computeIfAbsent(built, java.util.function.Function.identity()));
        }
        return CompiledEntry.of(overlaid(base, own, interned));
    }

    /** {@link #overlay}'s per-slot half, shared with the in-palette case. */
    private static CompiledEntry.Resolved[] overlaid(CompiledEntry base, TraitSet own,
                                                     Map<TraitSet, TraitSet> interned) {
        CompiledEntry.Resolved[] slots = new CompiledEntry.Resolved[base.slotCount()];
        Map<CompiledEntry.Resolved, CompiledEntry.Resolved> perSlot = new LinkedHashMap<>();
        for (int slot = 0; slot < slots.length; slot++) {
            CompiledEntry.Resolved from = base.slot(slot);
            slots[slot] = perSlot.computeIfAbsent(from, source -> {
                Map<Identifier, CompiledTrait> merged =
                        new LinkedHashMap<>(source.traits().traits());
                merged.putAll(own.traits());
                TraitSet built = TraitSet.of(merged);
                return new CompiledEntry.Resolved(source.state(),
                        interned.computeIfAbsent(built, java.util.function.Function.identity()));
            });
        }
        return slots;
    }

    // ---- Generation ----------------------------------------------------------------------------

    /**
     * The state and traits this marker places at this position ({@code LOAD.020}-{@code LOAD.022}).
     * <p>
     * <b>The four {@code INVARIANT}s, each visible in one line of this method.</b> {@code LOAD.040}: no
     * object is created - {@link MarkerIndex#index} returns an {@code int}, the arrays are of
     * references, and the {@link CompiledEntry.Resolved} handed back was built at compile time.
     * {@code LOAD.041}: the marker is an {@code int} codepoint, so there is no hash, no box and no
     * string. {@code LOAD.042}: no registry and no tag - the states were resolved and the tags expanded
     * before this palette existed. {@code LOAD.043}: the only inputs are the seed, the marker, the
     * position and this object, and {@code Rng.paletteSlotAt} is a pure function of them, so how many
     * other markers this chunk resolved first cannot change the answer.
     *
     * @param codepoint the marker, as a codepoint ({@code CHAR.002})
     * @return the slot, or {@code null} when this palette does not define the marker - which
     *         {@code LOAD.012} makes a load-time error at the part that used it, so a caller reaching
     *         here with an undefined marker has already been told
     */
    public CompiledEntry.Resolved at(int codepoint, long seed, int x, int y, int z) {
        int index = markers.index(codepoint);
        if (index == MarkerIndex.ABSENT) {
            return null;
        }
        CompiledEntry entry = entries[index];
        int slots = entry.slotCount();
        if (slots == 0) {
            return null;   // a light_socket defers to chunk assembly (MODEL.073)
        }
        return entry.slot(Rng.paletteSlotAt(seed, codepoint, x, y, z, slots));
    }

    /** {@link #at(int, long, int, int, int)} for a caller holding a {@link Marker}. */
    public CompiledEntry.Resolved at(Marker marker, long seed, int x, int y, int z) {
        return at(marker.codepoint(), seed, x, y, z);
    }

    /** The compiled entry for a marker, or {@code null} - for the report and for chunk assembly. */
    public CompiledEntry entry(int codepoint) {
        int index = markers.index(codepoint);
        return index == MarkerIndex.ABSENT ? null : entries[index];
    }

    /** Whether this palette defines a marker ({@code LOAD.012}'s question, asked at load). */
    public boolean defines(Marker marker) {
        return markers.index(marker.codepoint()) != MarkerIndex.ABSENT;
    }

    /** The dense remap, built once ({@code CHAR.031}). */
    public MarkerIndex markerIndex() {
        return markers;
    }

    /** Every marker this palette defines, in codepoint order. */
    public List<Marker> markers() {
        return markers.markers();
    }

    // ---- Compilation ---------------------------------------------------------------------------

    /**
     * Compiles a linked palette, or refuses it.
     *
     * @param resolved    what stage 3 produced
     * @param presence    what this installation has, for {@code when} and absent blocks
     * @param context     the registries {@code LOAD.003} says the caller hands the compiler
     * @param asset       what {@code 08-errors.md} §2 puts in the {@code <asset>} slot
     * @param diagnostics every refusal, collected ({@code DIAG.903}); a compile that reports an error
     *                    returns empty and never a partial palette
     */
    public static Optional<CompiledV2Palette> compile(NodeResolver.ResolvedPalette resolved,
                                                      Exclusion.Presence presence,
                                                      TraitContext context, String asset,
                                                      Diagnostics diagnostics) {
        return new Compiler(presence, context, asset, diagnostics).run(resolved);
    }

    /**
     * One compilation, with the state it needs for the length of it and no longer.
     * <p>
     * An instance rather than static methods threading six parameters, and {@code LOAD.031} is the rule
     * that makes it an instance rather than a holder of static fields: "Compilation may not retain state
     * in static fields that outlive the compiled palette." The interning pool and the two memos are
     * exactly the shape that rule's {@code > Why} records as having gone wrong - two static pools,
     * written from a decoding worker pool, that nothing emptied. These are unreachable the moment
     * {@link #run} returns.
     */
    private static final class Compiler {

        private final Exclusion.Presence presence;
        private final TraitContext context;
        private final String asset;
        private final Diagnostics diagnostics;

        /** {@code LOAD.023}: one {@link TraitSet} object per distinct set, for the whole compile. */
        private final Map<TraitSet, TraitSet> traitSets = new LinkedHashMap<>();

        /**
         * One compiled form per distinct resolved trait.
         * <p>
         * Memoised for the reason the resolver memoises an entry: a trait inherited by 128 slots is one
         * fact, and compiling it 128 times would report a broken satellite 128 times. Keyed by value,
         * and {@link ResolvedTrait}'s equality deliberately ignores provenance - so the same trait
         * reached through two spellings of one pointer is compiled once, which is {@code LOAD.030}.
         */
        private final Map<ResolvedTrait, Optional<CompiledTrait>> compiledTraits =
                new LinkedHashMap<>();

        Compiler(Exclusion.Presence presence, TraitContext context, String asset,
                 Diagnostics diagnostics) {
            this.presence = presence;
            this.context = context;
            this.asset = asset;
            this.diagnostics = diagnostics;
        }

        Optional<CompiledV2Palette> run(NodeResolver.ResolvedPalette resolved) {
            Map<Marker, ResolvedNode> pruned = new LinkedHashMap<>();
            Map<Marker, CompiledEntry> compiled = new LinkedHashMap<>();
            Map<Marker, ResolvedNode> aliases = new LinkedHashMap<>();
            boolean ok = true;

            for (Map.Entry<Marker, ResolvedNode> marker : resolved.palette().entrySet()) {
                PointerResolver.Site site = site(asset, marker.getKey());
                Optional<ResolvedNode> ready = prepare(marker.getValue(), site);
                if (ready.isEmpty()) {
                    ok = false;
                    continue;
                }
                pruned.put(marker.getKey(), ready.get());
                if (ready.get().source() instanceof ResolvedNode.Source.Alias) {
                    aliases.put(marker.getKey(), ready.get());
                    continue;
                }
                Optional<CompiledEntry> entry = entryOf(ready.get(), site);
                if (entry.isEmpty()) {
                    ok = false;
                    continue;
                }
                compiled.put(marker.getKey(), entry.get());
            }

            // MODEL.060, MODEL.063: an alias resolves to whatever its target resolves to and carries
            // the target's traits, then its own. Resolved after everything else so that a forward
            // alias reads the same as a backward one - MODEL.005 makes the order markers are declared
            // in a property of the file, and an alias that worked only when it was written second
            // would make it a rule.
            Map<Marker, Pending> pending = new LinkedHashMap<>();
            for (Map.Entry<Marker, ResolvedNode> alias : aliases.entrySet()) {
                Optional<Aliased> answer = aliased(alias.getKey(), alias.getValue(), compiled, pruned);
                if (answer.isEmpty()) {
                    ok = false;
                    continue;
                }
                switch (answer.get()) {
                    case Aliased.Here here -> compiled.put(alias.getKey(), here.entry());
                    case Aliased.Elsewhere elsewhere ->
                            pending.put(alias.getKey(), elsewhere.pending());
                }
            }

            if (!ok || diagnostics.hasFatal()) {
                return Optional.empty();
            }
            MarkerIndex index = MarkerIndex.of(compiled.keySet());
            CompiledEntry[] entries = new CompiledEntry[index.size()];
            compiled.forEach((marker, entry) -> entries[index.index(marker.codepoint())] = entry);
            return Optional.of(new CompiledV2Palette(index, entries, pending));
        }

        /**
         * What one {@code alias} resolved to: an entry this palette can build, or a target only the
         * merge can answer.
         * <p>
         * A sealed pair rather than a nullable entry, because the two outcomes are not "success" and
         * "failure" - {@link Elsewhere} is the shipped idiom, and {@code urbex:glass_side_variant_glass}
         * maps {@code '@'} to {@code 'a'} and declares nothing else at all.
         */
        private sealed interface Aliased {

            /** The target is a marker this palette defines, so the entry is built here. */
            record Here(CompiledEntry entry) implements Aliased {}

            /** The target is not defined here; {@code MODEL.062} is decided at the merge. */
            record Elsewhere(Pending pending) implements Aliased {}
        }

        /**
         * Stages 4 to 6 over one marker: sizes, exclusion, tag expansion, then trait validation.
         * <p>
         * Returns the node the entry is built from, which is not the node stage 3 produced:
         * alternatives have gone, and every {@code tag} has become the weighted list of its members.
         */
        private Optional<ResolvedNode> prepare(ResolvedNode node, PointerResolver.Site site) {
            if (!Apportion.checkSizes(node, site, diagnostics)) {
                return Optional.empty();
            }
            Optional<ResolvedNode> pruned = Exclusion.prune(node, presence, site, diagnostics);
            if (pruned.isEmpty()) {
                return Optional.empty();
            }
            Optional<ResolvedNode> expanded = expandTags(pruned.get(), site);
            if (expanded.isEmpty()) {
                return Optional.empty();
            }
            return validateTraits(expanded.get(), site) ? expanded : Optional.empty();
        }

        /**
         * {@code MODEL.052}: every {@code tag} node becomes the weighted list of its members, at load.
         * <p>
         * A list of equal weights rather than a source of its own, so that {@code MODEL.050}'s "drawn
         * uniformly" and {@code MODEL.044}'s weighted draw are one mechanism and one rounding step. A
         * tag nested inside a weighted list therefore divides the share its choice was given, which is
         * {@code WEIGHT.050} and needs nothing said about tags.
         * <p>
         * {@code MODEL.053} is the refusal: a tag that expands to no blocks is {@code DIAG.008}, "An
         * empty tag has nothing to place; name a tag with members, or name blocks directly." That covers
         * a tag this epoch does not know as well as one it knows and finds empty - see
         * {@link TraitContext#tagMembers}.
         */
        private Optional<ResolvedNode> expandTags(ResolvedNode node, PointerResolver.Site site) {
            switch (node.source()) {
                case ResolvedNode.Source.Tag tag -> {
                    List<String> members = context.tagMembers(tag.tag());
                    if (members.isEmpty()) {
                        diagnostics.error(Diag.DIAG_008, site.location(), "'" + tag.tag() + "'");
                        return Optional.empty();
                    }
                    List<ResolvedNode.Choice> choices = new ArrayList<>();
                    for (String member : members) {
                        choices.add(new ResolvedNode.Choice(
                                new ResolvedNode(Kind.BLOCK, new ResolvedNode.Source.Block(member),
                                        node.traits()),
                                Optional.of(new Size.Weight(1)), Optional.empty()));
                    }
                    return Optional.of(new ResolvedNode(Kind.WEIGHTED,
                            new ResolvedNode.Source.Weighted(choices), node.traits()));
                }
                case ResolvedNode.Source.Weighted weighted -> {
                    List<ResolvedNode.Choice> expanded = new ArrayList<>();
                    boolean ok = true;
                    for (int index = 0; index < weighted.choices().size(); index++) {
                        ResolvedNode.Choice choice = weighted.choices().get(index);
                        Optional<ResolvedNode> value =
                                expandTags(choice.node(), site.inside("choice " + index));
                        if (value.isEmpty()) {
                            ok = false;
                            continue;
                        }
                        expanded.add(new ResolvedNode.Choice(value.get(), choice.size(),
                                choice.when(), choice.spreadFrom()));
                    }
                    return ok
                            ? Optional.of(new ResolvedNode(node.kind(),
                                    new ResolvedNode.Source.Weighted(expanded), node.traits()))
                            : Optional.empty();
                }
                case ResolvedNode.Source.Socket socket -> {
                    Map<Kind.Placement, List<ResolvedNode.Choice>> expanded = new LinkedHashMap<>();
                    boolean ok = true;
                    for (Map.Entry<Kind.Placement, List<ResolvedNode.Choice>> list
                            : socket.placements().entrySet()) {
                        List<ResolvedNode.Choice> candidates = new ArrayList<>();
                        PointerResolver.Site inside = site.inside("'" + list.getKey().key() + "'");
                        for (int index = 0; index < list.getValue().size(); index++) {
                            ResolvedNode.Choice candidate = list.getValue().get(index);
                            Optional<ResolvedNode> value = expandTags(candidate.node(),
                                    inside.inside("candidate " + index));
                            if (value.isEmpty()) {
                                ok = false;
                                continue;
                            }
                            candidates.add(new ResolvedNode.Choice(value.get(), candidate.size(),
                                    candidate.when(), candidate.spreadFrom()));
                        }
                        expanded.put(list.getKey(), candidates);
                    }
                    return ok
                            ? Optional.of(new ResolvedNode(node.kind(),
                                    new ResolvedNode.Source.Socket(expanded), node.traits()))
                            : Optional.empty();
                }
                default -> {
                    return Optional.of(node);
                }
            }
        }

        /**
         * Stages 7 and 8 over one prepared marker: the slots, and the trait set on each of them.
         * <p>
         * A {@code block} node gets one slot rather than {@code Apportion.SLOTS} of them; see
         * {@link CompiledEntry}. A socket gets none of its own and one entry per surviving placement
         * list.
         */
        private Optional<CompiledEntry> entryOf(ResolvedNode node, PointerResolver.Site site) {
            return switch (node.source()) {
                case ResolvedNode.Source.Socket socket -> {
                    Map<Kind.Placement, CompiledEntry> placements = new LinkedHashMap<>();
                    boolean ok = true;
                    for (Map.Entry<Kind.Placement, List<ResolvedNode.Choice>> list
                            : socket.placements().entrySet()) {
                        PointerResolver.Site inside = site.inside("'" + list.getKey().key() + "'");
                        Optional<CompiledEntry.Resolved[]> slots =
                                weightedSlots(list.getValue(), inside);
                        if (slots.isEmpty()) {
                            ok = false;
                            continue;
                        }
                        placements.put(list.getKey(), CompiledEntry.of(slots.get()));
                    }
                    // The socket's own traits, compiled once, because it has no slots to keep them on.
                    yield ok
                            ? traitSet(node.traits(), site).map(own ->
                                    CompiledEntry.socket(placements, own))
                            : Optional.empty();
                }
                case ResolvedNode.Source.Weighted weighted ->
                        weightedSlots(weighted.choices(), site).map(CompiledEntry::of);
                default -> single(node, site)
                        .map(slot -> CompiledEntry.of(new CompiledEntry.Resolved[]{slot}));
            };
        }

        /** The 128 slots a weighted list compiles to, each carrying its own node's traits. */
        private Optional<CompiledEntry.Resolved[]> weightedSlots(List<ResolvedNode.Choice> choices,
                                                                 PointerResolver.Site site) {
            Optional<ResolvedNode[]> materialised =
                    Apportion.materialise(choices, site, diagnostics);
            if (materialised.isEmpty()) {
                return Optional.empty();
            }
            ResolvedNode[] nodes = materialised.get();
            CompiledEntry.Resolved[] slots = new CompiledEntry.Resolved[nodes.length];
            // Each distinct alternative is compiled once and its slot object shared, which is what
            // makes LOAD.023's interning reach the states as well as the trait sets: 128 slots over
            // three alternatives hold three Resolved objects.
            Map<ResolvedNode, CompiledEntry.Resolved> perAlternative = new LinkedHashMap<>();
            boolean ok = true;
            for (int slot = 0; slot < nodes.length; slot++) {
                CompiledEntry.Resolved resolved = perAlternative.get(nodes[slot]);
                if (resolved == null) {
                    Optional<CompiledEntry.Resolved> built = single(nodes[slot], site);
                    if (built.isEmpty()) {
                        ok = false;
                        continue;
                    }
                    resolved = built.get();
                    perAlternative.put(nodes[slot], resolved);
                }
                slots[slot] = resolved;
            }
            return ok ? Optional.of(slots) : Optional.empty();
        }

        /**
         * One alternative: its state ({@code MODEL.042}, {@code MODEL.043}) and its interned trait set.
         */
        private Optional<CompiledEntry.Resolved> single(ResolvedNode node,
                                                        PointerResolver.Site site) {
            if (!(node.source() instanceof ResolvedNode.Source.Block block)) {
                // Every other source has been expanded or flattened by now: a tag became a weighted
                // list, a weighted list became slots, a socket is compiled per placement, and an alias
                // is compiled from its target. Reaching here with one is this class calling itself
                // wrongly.
                throw new IllegalStateException("a " + node.kind().key()
                        + " node reached slot compilation; stages 5 to 7 should have removed it");
            }
            BlockStrings.Outcome outcome = BlockStrings.resolve(block.block(), context.blocks());
            if (outcome.malformed()) {
                // MODEL.043: the id resolves and the property expression does not apply to it.
                // Installing a mod cannot fix this, which is what separates it from MODEL.042 below.
                diagnostics.error(Diag.DIAG_006, site.location(), "'" + block.block() + "'");
                return Optional.empty();
            }
            Optional<TraitSet> traits = traitSet(node.traits(), site);
            if (traits.isEmpty()) {
                return Optional.empty();
            }
            // MODEL.042: an id no installed mod provides resolves to air, and the load succeeds.
            BlockState state = outcome.state().orElseGet(Blocks.AIR::defaultBlockState);
            return Optional.of(new CompiledEntry.Resolved(state, traits.get()));
        }

        /** A node's traits, compiled and interned ({@code LOAD.021}, {@code LOAD.023}). */
        private Optional<TraitSet> traitSet(Map<Identifier, ResolvedTrait> traits,
                                            PointerResolver.Site site) {
            if (traits.isEmpty()) {
                return Optional.of(TraitSet.EMPTY);
            }
            Map<Identifier, CompiledTrait> compiled = new LinkedHashMap<>();
            for (Map.Entry<Identifier, ResolvedTrait> trait : traits.entrySet()) {
                Optional<CompiledTrait> value = compiledTrait(trait.getValue(), site);
                if (value.isEmpty()) {
                    return Optional.empty();
                }
                compiled.put(trait.getKey(), value.get());
            }
            TraitSet built = TraitSet.of(compiled);
            return Optional.of(traitSets.computeIfAbsent(built, java.util.function.Function.identity()));
        }

        /**
         * One trait, with its satellites taken through the same pipeline the marker went through.
         * <p>
         * <b>A satellite is prepared as well as compiled,</b> which is what makes a {@code when} inside
         * an {@code urbex:damaged.into} weighted list mean the same thing it means anywhere else
         * ({@code WEIGHT.020}, {@code MODEL.076}'s "a placement list is a list like any other" read one
         * position over). What it does <em>not</em> inherit is traits, by {@code TRAIT.007}, and that
         * happened at stage 3.
         */
        private Optional<CompiledTrait> compiledTrait(ResolvedTrait trait,
                                                      PointerResolver.Site site) {
            Optional<CompiledTrait> memoised = compiledTraits.get(trait);
            if (memoised != null) {
                return memoised;
            }
            Map<String, CompiledEntry> satellites = new LinkedHashMap<>();
            boolean ok = true;
            for (Map.Entry<String, ResolvedNode> field : trait.satellites().entrySet()) {
                PointerResolver.Site at =
                        site.through("'" + trait.id() + "." + field.getKey() + "'");
                Optional<CompiledEntry> entry = prepare(field.getValue(), at)
                        .flatMap(ready -> entryOf(ready, at));
                if (entry.isEmpty()) {
                    ok = false;
                    continue;
                }
                satellites.put(field.getKey(), entry.get());
            }
            Optional<CompiledTrait> value = ok
                    ? Optional.of(new CompiledTrait(trait.type(),
                            trait.type().strippedOf(trait.value()), satellites, trait.provenance()))
                    : Optional.<CompiledTrait>empty();
            compiledTraits.put(trait, value);
            return value;
        }

        /**
         * {@code MODEL.060} and {@code MODEL.063}: an alias's entry is its target's, with its own
         * traits over the target's.
         * <p>
         * A chain of aliases is followed, and a cycle among them stops rather than recursing: a cycle
         * through {@code $ref} is {@code REF.032}'s and is refused at stage 3, but an {@code alias}
         * names a marker rather than a node and so cannot be seen by that graph.
         * <p>
         * <b>A target this palette does not define is not a failure here.</b> It comes back as
         * {@link Aliased.Elsewhere} carrying the marker to look up and the traits to apply, because by
         * {@code MODEL.062} the question belongs to the merge a part is generated with and by
         * {@code MODEL.064} that merge holds "markers contributed by palettes this file never
         * mentions". The empty return is reserved for the one case that really is this palette's fault:
         * a trait of the alias that did not compile.
         *
         * @return empty only when a diagnostic was recorded
         */
        private Optional<Aliased> aliased(Marker marker, ResolvedNode alias,
                                          Map<Marker, CompiledEntry> compiled,
                                          Map<Marker, ResolvedNode> pruned) {
            Set<Marker> seen = new LinkedHashSet<>();
            seen.add(marker);
            Map<Identifier, ResolvedTrait> traits = new LinkedHashMap<>(alias.traits());
            Marker target = ((ResolvedNode.Source.Alias) alias.source()).of();
            boolean here = true;
            while (!compiled.containsKey(target)) {
                ResolvedNode next = pruned.get(target);
                if (next == null || !(next.source() instanceof ResolvedNode.Source.Alias further)) {
                    // Not a marker of this palette at all, or one that is not an alias and did not
                    // compile. Either way the chain leaves here, and the merge is asked.
                    here = false;
                    break;
                }
                if (!seen.add(target)) {
                    // A cycle of aliases within one palette. It resolves to nothing anywhere, so it
                    // goes to the merge as a target the merge will not find either - which is where
                    // MODEL.062 reports it, once, with the rest.
                    here = false;
                    break;
                }
                // The nearer alias's traits win over the further one's, by TRAIT.006 applied down the
                // chain: putIfAbsent keeps what is already here, which is what this marker declared.
                next.traits().forEach(traits::putIfAbsent);
                target = further.of();
            }
            PointerResolver.Site site = site(asset, marker);
            Optional<TraitSet> own = traitSet(traits, site);
            if (own.isEmpty()) {
                return Optional.empty();
            }
            if (!here) {
                return Optional.of(new Aliased.Elsewhere(new Pending(target, own.get())));
            }
            return Optional.of(new Aliased.Here(
                    overlay(compiled.get(target), own.get(), traitSets)));
        }

        /**
         * The rules about a node's whole trait set, and the ones a trait states about itself.
         * <p>
         * Separate from {@link TraitType#validate} because two of them are not about a trait at all.
         * {@code TRAIT.064} is about a <em>pair</em>, and {@code TRAIT.021}/{@code TRAIT.031} are about
         * a declaration - {@link TraitType#references()} - which is checked once, generically, so that a
         * trait cannot be half-validated by forgetting to check its own reference. {@code TRAIT.022}'s
         * {@code > Why} is the measurement that argument comes from.
         */
        private boolean validateTraits(ResolvedNode node, PointerResolver.Site site) {
            boolean ok = validateNode(node, site);
            switch (node.source()) {
                case ResolvedNode.Source.Weighted weighted -> {
                    for (int index = 0; index < weighted.choices().size(); index++) {
                        ok &= validateTraits(weighted.choices().get(index).node(),
                                site.inside("choice " + index));
                    }
                }
                case ResolvedNode.Source.Socket socket -> {
                    for (Map.Entry<Kind.Placement, List<ResolvedNode.Choice>> list
                            : socket.placements().entrySet()) {
                        PointerResolver.Site inside = site.inside("'" + list.getKey().key() + "'");
                        for (int index = 0; index < list.getValue().size(); index++) {
                            ok &= validateTraits(list.getValue().get(index).node(),
                                    inside.inside("candidate " + index));
                        }
                    }
                }
                default -> {
                }
            }
            return ok;
        }

        private boolean validateNode(ResolvedNode node, PointerResolver.Site site) {
            boolean ok = true;
            // TRAIT.064, asked as the instance of TRAIT.092 that it is: two traits of the SELECTION
            // phase would roll against one position, and which replacement is written would depend on
            // which was consulted first. TRAIT.095 makes order matter *between* phases and TRAIT.092
            // forbids it mattering *within* one, so a second selection trait is the case that has to be
            // refused rather than ordered.
            //
            // Counted off TraitType.phase rather than testing for urbex:light beside urbex:optional by
            // name: a mod that registers a selection trait of its own has to be refused beside either
            // of them without this method knowing what it is called. Asked of the effective set,
            // because a node inheriting one and declaring the other carries both just as squarely as
            // one declaring both.
            if (node.traits().values().stream()
                    .filter(trait -> trait.type().phase() == TraitType.Phase.SELECTION)
                    .count() > 1) {
                diagnostics.error(Diag.DIAG_025, site.location());
                ok = false;
            }
            for (ResolvedTrait trait : node.traits().values()) {
                boolean declaredHere = !trait.provenance().inherited();
                if (declaredHere) {
                    // The payload's own rules, asked once, where the payload was written. A reference
                    // and a block entity are facts about the trait and about the node it was written
                    // on; asking them again of every alternative that inherited it would report one
                    // mistake once per slot.
                    ok &= references(trait, site);
                    Diagnostics own = new Diagnostics();
                    trait.type().validateValue(trait.value(), node, context, site, own);
                    report(own);
                    ok &= !own.hasFatal();
                    if (trait.type() == Light.TYPE) {
                        // TRAIT.053 is asked of the satellite, which MODEL.031 keeps out of the node's
                        // own states. The site names the field, so the message points at the block to
                        // change.
                        ResolvedNode unlit = trait.satellites().get(Light.UNLIT);
                        if (unlit != null) {
                            Diagnostics satellite = new Diagnostics();
                            Light.checkUnlit(unlit, context, site.through("'urbex:light.unlit'"),
                                    satellite);
                            report(satellite);
                            ok &= !satellite.hasFatal();
                        }
                    }
                }
                // TRAIT.052 is the one rule here that is asked per slot rather than per declaration,
                // and LOAD.021 is why: traits are a property of the slot, so a marker declaring a light
                // over a lantern and a stone block has a stone slot that can never look different. Only
                // a leaf is a slot - a weighted node is the thing slots are drawn from, and asking it
                // would answer for the list rather than for the alternative.
                if (trait.type() == Light.TYPE && isSlot(node)) {
                    Diagnostics emission = new Diagnostics();
                    Light.checkEmission(node, context, trait.provenance().declaredAt(), declaredHere,
                            emission);
                    report(emission);
                    ok &= !emission.hasFatal();
                }
            }
            return ok;
        }

        /**
         * Whether this node <em>is</em> a slot rather than a list slots are drawn from.
         * <p>
         * By the time trait validation runs, a {@code tag} has become a weighted list and every leaf is
         * a {@code block} or an {@code alias}. An alias counts, and contributes nothing: by
         * {@code MODEL.064} its target is answered by the merge a part is generated with, so
         * {@code TraitContext.statesOf} returns nothing for it and every check reads an unanswerable
         * question as "do not refuse".
         */
        private static boolean isSlot(ResolvedNode node) {
            return !(node.source() instanceof ResolvedNode.Source.Weighted)
                    && !(node.source() instanceof ResolvedNode.Source.Socket);
        }

        /**
         * Folds another collector's entries into this one, at the level each was recorded at.
         * <p>
         * Through the {@code AlreadyFormatted} pair rather than {@link Diagnostics#warn} and
         * {@link Diagnostics#error}, which would re-format a finished message against its own template
         * and throw for any row whose arity is not one. {@code DIAG.026} is arity two, so the warning
         * half of this was a latent crash until the second {@code WARN} rule made it reachable.
         * <p>
         * <b>And the non-catalogue half is folded too,</b> which is the same defect one field over.
         * {@link Diagnostics#nested} holds failures with no catalogue row - a message from a deeper
         * codec, a DFU type error - and they count as fatal. Folding only {@link Diagnostics#all()}
         * would let a trait refuse a compile and contribute no text: {@code hasFatal} would be true,
         * {@code run} would return empty, and the outer collector would hold nothing, so every caller's
         * {@code asError().orElseThrow()} would throw instead of reporting. No trait calls
         * {@code nested} today; the point is that one could, and this is the method where that class of
         * silence has already bitten once.
         */
        private void report(Diagnostics from) {
            from.all().forEach(entry -> {
                if (entry.level() == Diagnostics.Level.WARN) {
                    diagnostics.warnAlreadyFormatted(entry.diag(), entry.message());
                } else {
                    diagnostics.errorAlreadyFormatted(entry.diag(), entry.message());
                }
            });
            from.nestedMessages().forEach(diagnostics::nested);
        }

        /**
         * {@code TRAIT.021} and {@code TRAIT.031}, read off {@link TraitType#references()}.
         * <p>
         * One check for both rules, and for any trait that declares a reference afterwards. That is the
         * whole of what {@code TRAIT.090}'s declaration buys: the 48-name table an addon importer kept
         * was a second copy of a fact the format did not state, and this reads the fact.
         */
        private boolean references(ResolvedTrait trait, PointerResolver.Site site) {
            boolean ok = true;
            for (Map.Entry<TraitType.ReferenceTarget, List<Identifier>> named
                    : trait.type().referencedBy(trait.value()).entrySet()) {
                for (Identifier id : named.getValue()) {
                    if (context.holds(named.getKey().registry(), id)) {
                        continue;
                    }
                    // DIAG.021 takes the field and the registry as slots, because TRAIT.090 makes
                    // both of them values: a trait declares which of its fields are references into
                    // which registry, and a message naming 'pool' and 'conditions' outright would be
                    // true only of the two traits this repository happens to ship.
                    diagnostics.error(Diag.DIAG_021, site.location(), trait.id(),
                            named.getKey().field(), "'" + id + "'",
                            named.getKey().registry().identifier());
                    ok = false;
                }
            }
            return ok;
        }
    }

    /** {@code 08-errors.md} §2's location for a marker of a named asset ({@code DIAG.902}). */
    private static PointerResolver.Site site(String asset, Marker marker) {
        return new PointerResolver.Site(asset, "marker " + marker, List.of());
    }

    /** Every distinct trait set this palette compiled to, for a test that counts them. */
    public List<TraitSet> traitSets() {
        Set<TraitSet> sets = Collections.newSetFromMap(new LinkedHashMap<>());
        for (CompiledEntry entry : entries) {
            collectSets(entry, sets);
        }
        return List.copyOf(sets);
    }

    private static void collectSets(CompiledEntry entry, Set<TraitSet> into) {
        for (int slot = 0; slot < entry.slotCount(); slot++) {
            into.add(entry.slot(slot).traits());
        }
        entry.placements().values().forEach(placement -> collectSets(placement, into));
    }
}

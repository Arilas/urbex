package dev.krona.urbex.format.palette;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a trait's validation is allowed to ask the world it is being compiled into.
 * <p>
 * {@code TRAIT.093}: "A trait's validation runs at load, against the compiling world's registries". So
 * validation needs the registries, and {@code LOAD.003} says which ones: "Every block string resolves
 * against a block registry <b>handed to the compiler by its caller</b>, never one it fetches". This is
 * that hand-off, as one value, so that no trait can reach a static registry reference of its own -
 * which is the failure {@code LOAD.002}'s {@code > Why} records, where "which registry answered
 * depended on whether the server field was populated yet".
 * <p>
 * <b>Assets are a map keyed by registry, not a {@code Set<Identifier>} of conditions.</b>
 * {@code TRAIT.090} makes a trait declare "which of its fields are references <em>into which
 * registry</em>", so the check that reads that declaration has to be able to ask about any registry a
 * trait names. Only {@code urbex:conditions} is named by anything today; a second one costs a map entry
 * rather than a second parameter and a second code path.
 *
 * @param blocks the block registry {@code LOAD.003} says the caller supplies
 * @param assets every asset id the compiling snapshot holds, by the registry that holds it
 */
public record TraitContext(HolderLookup<Block> blocks,
                           Map<ResourceKey<? extends Registry<?>>, Set<Identifier>> assets,
                           TagEpoch tags) {

    public TraitContext(HolderLookup<Block> blocks,
                        Map<ResourceKey<? extends Registry<?>>, Set<Identifier>> assets,
                        TagEpoch tags) {
        this.blocks = blocks;
        Map<ResourceKey<? extends Registry<?>>, Set<Identifier>> copy = new LinkedHashMap<>();
        assets.forEach((registry, ids) -> copy.put(registry, Set.copyOf(ids)));
        this.assets = Map.copyOf(copy);
        this.tags = tags;
    }

    /**
     * Which blocks a block tag holds, at the epoch this palette is compiled under
     * ({@code MODEL.052}).
     * <p>
     * A component of its own rather than a second question asked of {@code blocks}, and the reason is
     * the sentence {@code TagSnapshot} already carries about version 1: the read of the block
     * registry's tag bindings is kept to one place "so 'when are block tags read' has a single
     * answer". Making the epoch an input says the same thing in the type - a compiler that is handed
     * one cannot read a different one - which is {@code LOAD.003}'s argument applied to tags rather
     * than to blocks.
     */
    @FunctionalInterface
    public interface TagEpoch {

        /** The block ids in this tag, or empty for a tag this epoch does not know. */
        List<String> members(Identifier tag);

        /** The epoch a block registry's own tag bindings are. */
        static TagEpoch of(HolderLookup<Block> blocks) {
            return tag -> blocks.get(TagKey.create(Registries.BLOCK, tag))
                    .map(named -> named.stream()
                            .flatMap(holder -> holder.unwrapKey().stream())
                            .map(key -> key.identifier().toString())
                            .toList())
                    .orElseGet(List::of);
        }
    }

    /** Whether {@code registry} holds an asset with this id ({@code TRAIT.021}, {@code TRAIT.031}). */
    public boolean holds(ResourceKey<? extends Registry<?>> registry, Identifier id) {
        return assets.getOrDefault(registry, Set.of()).contains(id);
    }

    /**
     * The state a block string names, or empty when this installation has no such block.
     * <p>
     * {@code MODEL.042} is the empty case and it is an acceptance, not a failure: "A {@code block}
     * naming an id no installed mod provides resolves to air, and the load succeeds." The caller decides
     * what air means where it is - a marker generates air, a satellite leaves the trait inert - which is
     * why this reports the absence rather than substituting the air state itself.
     * <p>
     * A property expression that does not apply to a block this game <em>does</em> have is
     * {@code MODEL.043}, a rejection, and is {@link BlockStrings#resolve} 's business rather than this
     * method's: it is a mistake in the file, and this method's callers are the ones for whom an absent
     * block is ordinary.
     */
    public Optional<BlockState> state(String block) {
        return BlockStrings.resolve(block, blocks).state();
    }

    /**
     * {@code MODEL.050}, {@code MODEL.052}: the blocks a {@code tag} node's tag holds, expanded now.
     * <p>
     * "A tag is expanded at load, against the tag epoch the palette is compiled under, and never read
     * during generation" - which is also {@code LOAD.042}, and is why this is called from the compile
     * stage and from nowhere the resolver reaches at a position.
     * <p>
     * A tag no registry knows and a tag that is bound and empty both come back empty. They are the same
     * thing to {@code MODEL.053} - "a {@code tag} that expands to no blocks is refused" - and the rule
     * distinguishing them would need the loader to know the difference between a tag absent from this
     * epoch and one present with no members, which the lookup does not report.
     */
    public List<String> tagMembers(String written) {
        Identifier id = Identifier.tryParse(written.startsWith("#") ? written.substring(1) : written);
        if (id == null) {
            return List.of();
        }
        // Sorted, so that one tag epoch expands to one order however the tag's own entries were
        // assembled. The members take equal shares, so order decides only which of them WEIGHT.060's
        // tie break gives a spare slot to - but that is a difference a golden file sees, and a tag is
        // assembled from however many datapacks contributed to it.
        return tags.members(id).stream().sorted().toList();
    }

    /**
     * Every block state this node can place, for a trait that has to ask about all of them.
     * <p>
     * {@code TRAIT.052} is the caller that needs it - "{@code urbex:light} on a node <b>none</b> of whose
     * resolved states emit light is refused" - so the walk covers both kinds of alternative
     * ({@code MODEL.030}) and stops at satellites, which are not alternatives ({@code MODEL.031}) and
     * are not states this node places.
     * <p>
     * An {@code alias} contributes nothing, and that is deliberate rather than an omission: by
     * {@code MODEL.064} its target is answered by the merged palette a part is generated with, which
     * this compilation does not have. Contributing nothing makes a trait's question about it
     * unanswerable rather than answered wrongly, and every caller reads an unanswerable question as
     * "do not refuse" - the over-rejection {@code ACCEPT} exists as a class to prevent.
     */
    public List<BlockState> statesOf(ResolvedNode node) {
        List<BlockState> states = new java.util.ArrayList<>();
        collectStates(node, states);
        return List.copyOf(states);
    }

    private void collectStates(ResolvedNode node, List<BlockState> into) {
        switch (node.source()) {
            case ResolvedNode.Source.Block block -> state(block.block()).ifPresent(into::add);
            case ResolvedNode.Source.Tag tag ->
                    tagMembers(tag.tag()).forEach(member -> state(member).ifPresent(into::add));
            case ResolvedNode.Source.Weighted weighted -> weighted.choices()
                    .forEach(choice -> collectStates(choice.node(), into));
            case ResolvedNode.Source.Socket socket -> socket.placements().values()
                    .forEach(list -> list.forEach(choice -> collectStates(choice.node(), into)));
            case ResolvedNode.Source.Alias ignored -> {
            }
        }
    }

    /**
     * The block and tag references this node's tree <em>writes</em>, in declaration order.
     * <p>
     * For a message rather than for a decision, and that is the reason it exists beside
     * {@link #statesOf}: a diagnostic derived from a value is only true if the value is the one the file
     * wrote. {@code DIAG.022}'s {@code <block>} slot naming {@code minecraft:stone_bricks} is a sentence
     * the author can find in their file; the same slot filled by reversing a {@link BlockState} back
     * through the block registry would print a canonical id that may not be the text they typed, and
     * would read a registry to say so.
     */
    public List<String> writtenBlocks(ResolvedNode node) {
        List<String> written = new java.util.ArrayList<>();
        collectWritten(node, written);
        return List.copyOf(written);
    }

    private void collectWritten(ResolvedNode node, List<String> into) {
        switch (node.source()) {
            case ResolvedNode.Source.Block block -> into.add(block.block());
            case ResolvedNode.Source.Tag tag -> into.add(tag.tag());
            case ResolvedNode.Source.Weighted weighted -> weighted.choices()
                    .forEach(choice -> collectWritten(choice.node(), into));
            case ResolvedNode.Source.Socket socket -> socket.placements().values()
                    .forEach(list -> list.forEach(choice -> collectWritten(choice.node(), into)));
            case ResolvedNode.Source.Alias ignored -> {
            }
        }
    }

    /** Whether this node can place a block at all, as far as this compilation can tell. */
    public boolean placesAnything(ResolvedNode node) {
        return node.source() instanceof ResolvedNode.Source.Alias || !statesOf(node).isEmpty();
    }

    /** A context with no assets in any registry, reading tags off the block registry. */
    public static TraitContext of(HolderLookup<Block> blocks) {
        return new TraitContext(blocks, Map.of(), TagEpoch.of(blocks));
    }

    /** A context holding exactly these {@code urbex:conditions} ids. */
    public static TraitContext withConditions(HolderLookup<Block> blocks, Set<Identifier> conditions) {
        return new TraitContext(blocks, Map.of(conditionsRegistry(),
                new LinkedHashSet<>(conditions)), TagEpoch.of(blocks));
    }

    /** The same context, reading tags from {@code epoch} instead of from the block registry. */
    public TraitContext withTags(TagEpoch epoch) {
        return new TraitContext(blocks, assets, epoch);
    }

    /**
     * The {@code urbex:conditions} registry key, named here rather than imported from
     * {@code CustomRegistries}.
     * <p>
     * The format package does not depend on the mod's registry setup in either direction: a
     * {@link ResourceKey} is a name, and constructing it from the same two strings is what keeps
     * {@code TRAIT.020}'s "a {@code conditions} asset" a statement about an id rather than about a
     * class. {@code CustomRegistries.CONDITIONS_REGISTRY_KEY} builds the identical key and
     * {@code TraitTest} asserts the two are equal, so the duplication cannot drift silently.
     */
    public static ResourceKey<? extends Registry<?>> conditionsRegistry() {
        return ResourceKey.createRegistryKey(
                Identifier.fromNamespaceAndPath("urbex", "conditions"));
    }

    /** The vanilla block registry key, for a trait declaring a reference into it. */
    public static ResourceKey<? extends Registry<?>> blockRegistry() {
        return Registries.BLOCK;
    }
}

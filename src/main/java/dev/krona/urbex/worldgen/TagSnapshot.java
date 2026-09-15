package dev.krona.urbex.worldgen;

import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * Every block-tag answer one generation epoch gives, expanded once when that epoch opens.
 *
 * <p>Block tags are the one piece of Urbex's compiled state that a {@code /reload} genuinely
 * changes. The thirteen asset registries are Fabric dynamic registries, loaded with the world and
 * frozen (issue #61), so an edited building or palette needs the world reopened whatever a reload
 * does - but {@code urbex:needspoi}, {@code urbex:foliage} and the damage tags come
 * back with every one. That single difference used to cost a whole {@link DimensionRuntime} per
 * loaded level: {@code CityGenerator} expanded those tags into {@code BlockState} sets in its
 * constructor, so refreshing them meant rebuilding the generator, the road field, the world-style
 * field and every seed-derived cache beside them - none of which a tag can affect (issue #128).</p>
 *
 * <p>Separating them buys two things. A tag reload becomes one reference write into
 * {@link TagEpoch}, so the caches a world spent its chunks building survive it. And an <em>asset</em>
 * reload becomes impossible to write by accident: no tag-derived state is left on the objects that
 * hold the {@link AssetSnapshot}, so nothing tempts a future reload into republishing them.</p>
 *
 * <p>Captured per chunk rather than read per block, exactly like the asset snapshot beside it:
 * {@link ChunkGenContext} takes {@link TagEpoch#current()} once at the start of a generation and
 * every question that chunk asks is answered by that one instance. A reload landing mid-chunk
 * therefore cannot show one slice of a building the old membership and the next slice the new one.
 * That is also why the old {@code Tools.hasTag} is gone: a live registry read from the driver loop
 * is precisely the incoherence this type removes.</p>
 *
 * <p><strong>Block tags only.</strong> The one biome tag Urbex reads ({@code c:is_void}, in
 * {@code CityFeature}) is asked of a {@code Holder<Biome>} that comes from the level's own frozen
 * biome registry, so a reload cannot change it and there is nothing to capture.</p>
 */
public final class TagSnapshot {

    private static final TagKey<Block> SAPLINGS =
            TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("saplings"));

    private final Set<BlockState> statesNeedingTodo;
    private final Set<BlockState> statesNeedingPoiUpdate;
    private final Set<Block> foliage;
    private final Set<Block> notBreakable;
    private final Set<Block> easyBreakable;

    private TagSnapshot(Set<BlockState> statesNeedingTodo,
                        Set<BlockState> statesNeedingPoiUpdate,
                        Set<Block> foliage,
                        Set<Block> notBreakable,
                        Set<Block> easyBreakable) {
        this.statesNeedingTodo = Set.copyOf(statesNeedingTodo);
        this.statesNeedingPoiUpdate = Set.copyOf(statesNeedingPoiUpdate);
        this.foliage = Set.copyOf(foliage);
        this.notBreakable = Set.copyOf(notBreakable);
        this.easyBreakable = Set.copyOf(easyBreakable);
    }

    /**
     * Expands every block tag this world's generation can ask about, from the block registry's
     * current tag bindings.
     */
    public static TagSnapshot capture() {
        Set<BlockState> needingTodo = new HashSet<>();
        addStates(SAPLINGS, needingTodo);
        addStates(BlockTags.SMALL_FLOWERS, needingTodo);

        return new TagSnapshot(
                needingTodo,
                statesIn(UrbexTags.NEEDSPOI_TAG),
                blocksIn(UrbexTags.FOLIAGE_TAG),
                blocksIn(UrbexTags.NOT_BREAKABLE_TAG),
                blocksIn(UrbexTags.EASY_BREAKABLE_TAG));
    }

    /** Whether {@code state} carries POI data, so its write has to be deferred past generation. */
    public boolean needsPoiUpdate(BlockState state) {
        return statesNeedingPoiUpdate.contains(state);
    }

    /** Whether {@code state} is one of the plants that only survives being placed after the fact. */
    public boolean needsTodo(BlockState state) {
        return statesNeedingTodo.contains(state);
    }

    /** {@code urbex:foliage}: what a column scan may look straight through. */
    public boolean isFoliage(BlockState state) {
        return foliage.contains(state.getBlock());
    }

    /** {@code urbex:notbreakable}: what an explosion leaves alone. */
    public boolean isNotBreakable(BlockState state) {
        return notBreakable.contains(state.getBlock());
    }

    /** {@code urbex:easybreakable}: what an explosion damages as if it were hit twice as hard. */
    public boolean isEasyBreakable(BlockState state) {
        return easyBreakable.contains(state.getBlock());
    }

    private static Set<BlockState> statesIn(TagKey<Block> tag) {
        Set<BlockState> states = new HashSet<>();
        addStates(tag, states);
        return states;
    }

    private static void addStates(TagKey<Block> tag, Set<BlockState> into) {
        for (Holder<Block> block : blocksTagged(tag)) {
            into.addAll(block.value().getStateDefinition().getPossibleStates());
        }
    }

    private static Set<Block> blocksIn(TagKey<Block> tag) {
        Set<Block> blocks = new HashSet<>();
        for (Holder<Block> block : blocksTagged(tag)) {
            blocks.add(block.value());
        }
        return blocks;
    }

    /**
     * The one read of the block registry's tag bindings left in Urbex, so "when are block tags
     * read" has a single answer: while an epoch is being captured, on the thread that captures it.
     */
    private static Iterable<Holder<Block>> blocksTagged(TagKey<Block> tag) {
        @SuppressWarnings("deprecation") DefaultedRegistry<Block> registry = BuiltInRegistries.BLOCK;
        return registry.getTagOrEmpty(tag);
    }
}

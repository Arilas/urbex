package dev.krona.urbex.worldgen;

import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Every block-tag answer one generation epoch gives, expanded once when that epoch opens.
 *
 * <p>Block tags are the one piece of Urbex's compiled state that a {@code /reload} genuinely
 * changes. The thirteen asset registries are Fabric dynamic registries, loaded with the world and
 * frozen (issue #61), so an edited building or palette needs the world reopened whatever a reload
 * does - but {@code urbex:lights}, {@code urbex:needspoi}, {@code urbex:rotatable} and the rest come
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
    private final Set<BlockState> statesNeedingLightingUpdate;
    private final Set<BlockState> statesNeedingPoiUpdate;
    private final Set<Block> foliage;
    private final Set<Block> notBreakable;
    private final Set<Block> easyBreakable;
    /**
     * One entry per {@code rotatable} tag the loaded pack can name. Keyed rather than a single set
     * because the tag is authored per world style: two cities in one world can come from packs whose
     * {@code rotatable} tags differ, so the answer follows the chunk's style (issue #117).
     */
    private final Map<TagKey<Block>, Set<Block>> rotatable;

    private TagSnapshot(Set<BlockState> statesNeedingTodo,
                        Set<BlockState> statesNeedingLightingUpdate,
                        Set<BlockState> statesNeedingPoiUpdate,
                        Set<Block> foliage,
                        Set<Block> notBreakable,
                        Set<Block> easyBreakable,
                        Map<TagKey<Block>, Set<Block>> rotatable) {
        this.statesNeedingTodo = Set.copyOf(statesNeedingTodo);
        this.statesNeedingLightingUpdate = Set.copyOf(statesNeedingLightingUpdate);
        this.statesNeedingPoiUpdate = Set.copyOf(statesNeedingPoiUpdate);
        this.foliage = Set.copyOf(foliage);
        this.notBreakable = Set.copyOf(notBreakable);
        this.easyBreakable = Set.copyOf(easyBreakable);
        this.rotatable = Map.copyOf(rotatable);
    }

    /**
     * Expands every block tag this world's generation can ask about, from the block registry's
     * current tag bindings.
     *
     * <p>It takes the compiled assets because {@code rotatable} is authored, not fixed: which tags
     * matter is a property of the loaded pack. Every world style in the snapshot contributes its
     * tag, and {@code urbex:rotatable} is always expanded because that is what a style declaring
     * none resolves to. So every tag {@link #isRotatable} can be handed is one this expanded, which
     * is what makes a miss there a wiring bug rather than a datapack's problem.</p>
     */
    public static TagSnapshot capture(AssetSnapshot assets) {
        Set<BlockState> needingTodo = new HashSet<>();
        addStates(SAPLINGS, needingTodo);
        addStates(BlockTags.SMALL_FLOWERS, needingTodo);

        Map<TagKey<Block>, Set<Block>> rotatable = new HashMap<>();
        rotatable.put(UrbexTags.ROTATABLE_TAG, blocksIn(UrbexTags.ROTATABLE_TAG));
        for (WorldStyle style : assets.worldStyles().all()) {
            rotatable.computeIfAbsent(style.getRotatableTag(), TagSnapshot::blocksIn);
        }

        return new TagSnapshot(
                needingTodo,
                statesIn(UrbexTags.LIGHTS_TAG),
                statesIn(UrbexTags.NEEDSPOI_TAG),
                blocksIn(UrbexTags.FOLIAGE_TAG),
                blocksIn(UrbexTags.NOT_BREAKABLE_TAG),
                blocksIn(UrbexTags.EASY_BREAKABLE_TAG),
                rotatable);
    }

    /** Whether {@code state} carries POI data, so its write has to be deferred past generation. */
    public boolean needsPoiUpdate(BlockState state) {
        return statesNeedingPoiUpdate.contains(state);
    }

    /** Whether placing {@code state} has to tell the client to relight around it. */
    public boolean needsLightingUpdate(BlockState state) {
        return statesNeedingLightingUpdate.contains(state);
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

    /**
     * Whether {@code state} rotates with the part it sits in, under the governing world style's
     * {@code rotatable} tag.
     *
     * @throws IllegalStateException for a tag this snapshot did not expand. Reachable only from a
     *                               {@link WorldStyle} that is not in the {@link AssetSnapshot} this
     *                               was captured against - and since a world compiles its assets
     *                               once and never swaps them, that is a wiring error in Urbex,
     *                               not something a datapack can provoke. Loud rather than
     *                               {@code false}: silently not rotating is issue #117 again, and
     *                               that one took a ladder attached to nothing to notice.
     */
    public boolean isRotatable(TagKey<Block> tag, BlockState state) {
        Set<Block> blocks = rotatable.get(tag);
        if (blocks == null) {
            throw new IllegalStateException("Block tag '" + tag.location() + "' was never expanded by this "
                    + "tag snapshot; it belongs to a world style outside the compiled assets.");
        }
        return blocks.contains(state.getBlock());
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

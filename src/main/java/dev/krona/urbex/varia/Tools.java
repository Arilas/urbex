package dev.krona.urbex.varia;

import dev.krona.urbex.Urbex;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Tools {

    /** Block ids already reported by {@link #stringToState}, so each is named once, not per use. */
    private static final Set<String> WARNED_MISSING_BLOCKS = ConcurrentHashMap.newKeySet();

    public static String stateToString(BlockState state) {
        StringBuilder stringbuilder = new StringBuilder();
        stringbuilder.append(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        String props = state.getValues().map(Property.Value::toString).collect(Collectors.joining(","));
        if (!props.isEmpty()) {
            stringbuilder.append('[');
            stringbuilder.append(props);
            stringbuilder.append(']');
        }

        return stringbuilder.toString();
    }

    /**
     * {@link #resolveState}, with air for a block this game does not have.
     * <p>
     * For the callers that have one block to place and no list to fall back on: a palette's single
     * {@code block}, and a light pool's stand-in state. An entry in a weighted list must use
     * {@link #resolveState} and drop itself instead, so the survivors share the draw rather than
     * competing with an invisible entry (issue #91).
     *
     * @param blockLookup what to resolve against, handed down from
     *                    {@link dev.krona.urbex.worldgen.lost.cityassets.AssetCompiler} out of the
     *                    world's own {@code RegistryAccess}. It used to be picked here, and which
     *                    one you got depended on whether {@code ServerAccess.getServer()} happened
     *                    to be populated yet: the overworld's lookup if it was,
     *                    {@code BuiltInRegistries.BLOCK} if it was not. The two are the same
     *                    registry - blocks are static and frozen at mod init - so nothing was ever
     *                    wrong, but "which registry did this parse against" was answered by timing
     *                    rather than by the caller (issues #60, #128).
     * @param owner       the asset the string came from, used only in the warning. This is the
     *                    asset <em>id</em>, not the file path, and for a palette written inline in a
     *                    part or building it is the synthetic {@code urbex:__local__<path>} name
     *                    {@link dev.krona.urbex.worldgen.lost.cityassets.Palette#inline} builds
     *                    rather than the owning part - close enough to find, but not a filename. May
     *                    be null; every production caller passes one.
     */
    public static BlockState stringToState(String s, HolderLookup<Block> blockLookup, @Nullable Object owner) {
        BlockState resolved = resolveState(s, blockLookup, owner);
        return resolved == null ? Blocks.AIR.defaultBlockState() : resolved;
    }

    /**
     * Resolves a datapack block string, or {@code null} if this game has no such block.
     * <p>
     * <b>Null is "this game does not have that block", not "this string is wrong".</b> The
     * difference decides whether the world loads, so it is drawn at the block id and nowhere else:
     * an id no installed mod provides, an id a Minecraft version renamed, and an id that is not even
     * a legal {@link Identifier} are all null - while a block this game <em>does</em> have, written
     * with a property expression it does not, still throws. That second case is a mistake in the
     * file and nothing about installing a mod would fix it, so it keeps the load error and the
     * message that names the palette, marker, placement and candidate.
     * <p>
     * A caller choosing from a weighted list drops a null entry and lets the survivors share the
     * draw; a caller with one block to place uses air ({@link #stringToState}). Neither refuses the
     * world, which is the decision issue #91 records: a pack naming one absent block generates the
     * rest of itself.
     * <p>
     * Each distinct string is warned about once, however many entries name it, and that warning is
     * the only report. This deliberately raises no load-time diagnostic: making an absent block
     * refuse the world is the strict half of #91 and was not chosen, because a pack written around
     * optional cross-mod blocks would then refuse to load on a vanilla install.
     * <p>
     * The pre-flattening {@code name@meta} form lands here as an unparseable id rather than as an
     * upgrade: {@code @} is not a legal path character so {@link Identifier#tryParse} rejects it,
     * and {@code BlockStateData.upgradeBlock(String)} does not handle that form anyway (measured: it
     * returns {@code minecraft:red_sandstone@2} unchanged). It used to take the whole world load
     * with it from inside {@code Palette}'s constructor.
     */
    @Nullable
    public static BlockState resolveState(String s, HolderLookup<Block> blockLookup, @Nullable Object owner) {
        int properties = s.indexOf('[');
        Identifier id = Identifier.tryParse(properties < 0 ? s : s.substring(0, properties));
        if (id == null) {
            return missing(s, owner);
        }
        boolean known = blockLookup.get(ResourceKey.create(Registries.BLOCK, id)).isPresent();

        if (properties >= 0) {
            if (!known) {
                // The crash half of #91: a property-carrying string whose block is absent threw out
                // of the parser below, and since compilation moved to load time that refused the
                // whole world rather than one chunk.
                return missing(s, owner);
            }
            try {
                return BlockStateParser.parseForBlock(blockLookup, new StringReader(s), false).blockState();
            } catch (CommandSyntaxException e) {
                throw new RuntimeException(e);
            }
        }

        if (known) {
            return blockLookup.getOrThrow(ResourceKey.create(Registries.BLOCK, id)).value().defaultBlockState();
        }
        Identifier upgradedId = Identifier.tryParse(BlockStateData.upgradeBlock(s));
        Optional<Holder.Reference<Block>> upgraded = upgradedId == null
                ? Optional.empty()
                : blockLookup.get(ResourceKey.create(Registries.BLOCK, upgradedId));
        return upgraded.map(block -> block.value().defaultBlockState()).orElseGet(() -> missing(s, owner));
    }

    @Nullable
    private static BlockState missing(String s, @Nullable Object owner) {
        if (WARNED_MISSING_BLOCKS.add(s)) {
            Urbex.LOGGER.warn(
                    "Block '{}'{} does not exist in this Minecraft version. Entries naming it are "
                            + "skipped and the remaining ones share the draw; a palette character "
                            + "left with nothing generates as air. If this is not a block from an "
                            + "uninstalled mod, it was most likely renamed - check the current id.",
                    s, owner == null ? "" : " (in " + owner + ")");
        }
        return null;
    }

    /**
     * {@code min + rand.nextInt(maxExclusive - min)}, degrading to {@code min} when the range is
     * empty or inverted. Profile and datapack bounds are user-editable; equal min/max used to
     * reach {@code nextInt(0)} and crash chunk generation (issue #47). Draws exactly one value
     * when the range is valid, so worlds generated with valid bounds do not shift.
     */
    public static int randomBetween(RandomSource rand, int min, int maxExclusive) {
        return maxExclusive <= min ? min : min + rand.nextInt(maxExclusive - min);
    }

    public static <T> T getRandomFromList(RandomSource random, List<T> list, Function<T, Float> weightGetter) {
        if (list.isEmpty()) {
            return null;
        }
        List<T> elements = new ArrayList<>();
        float totalweight = 0;
        for (T pair : list) {
            elements.add(pair);
            totalweight += weightGetter.apply(pair);
        }
        float r = random.nextFloat() * totalweight;
        for (T pair : elements) {
            r -= weightGetter.apply(pair);
            if (r <= 0) {
                return pair;
            }
        }
        return elements.get(elements.size() - 1);
    }

    public static int getSeaLevel(LevelReader level) {
        if (level instanceof WorldGenLevel wgLevel) {
            if (wgLevel.getChunkSource() instanceof ServerChunkCache scc) {
                return scc.getGenerator().getSeaLevel();
            }
        }
        //noinspection deprecation
        return level.getSeaLevel();
    }

    /**
     * A part-wiring field: one part id, an array of them, or the {@code {"replace": false,
     * "values": [...]}} form that appends to what the {@code extends} chain already put there.
     * <p>
     * <b>There is no default.</b> A field this codec does not find is {@link Optional#empty()},
     * which reads as "this file did not mention it" and lets the chain supply it; a field nothing in
     * the chain declares is a load error raised by
     * {@link dev.krona.urbex.worldgen.lost.cityassets.Resolved#require} after the chain is applied,
     * naming the asset and the field. It used to fall back to a bare asset name written here in
     * Java, so a world style that never mentioned primary roads still generated Urbex's own
     * wide-road parts - a reference no datapack file wrote, and nothing to grep for when it
     * misbehaved.
     * <p>
     * Absence has to stay decodable per file rather than being a required field, because a child
     * that overrides one part family must not have to restate the other seven: that is what
     * {@link Mergeable#fold} folds together, and what the {@code {"replace": false}} arm appends to.
     */
    public static <T> RecordCodecBuilder<T, Optional<Mergeable<String>>> listOrStringList(
            String fieldName, Function<T, Optional<Mergeable<String>>> getter) {
        return Codec.either(Codec.STRING, Mergeable.codec(Codec.STRING))
                .optionalFieldOf(fieldName)
                .xmap(opt -> opt.map(either ->
                                either.map(s -> new Mergeable<>(true, List.of(s)), Function.identity())),
                        opt -> opt.map(m -> m.replace() && m.values().size() == 1
                                ? Either.left(m.values().get(0))
                                : Either.right(m)))
                .forGetter(getter);
    }

}

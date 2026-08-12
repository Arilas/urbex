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
     * Resolves a datapack block string against {@code blockLookup}, naming {@code owner} if it cannot be
     * resolved.
     * <p>
     * <b>An unknown id returns air.</b> That is not a decision made here - it is what this has
     * always done, and it is why {@code minecraft:chain} (renamed {@code minecraft:iron_chain} in
     * 26.x) made the whole {@code urbex:chains} decoration invisible without anyone noticing. It
     * used to happen by accident, through {@code BuiltInRegistries.BLOCK} being a
     * {@link DefaultedRegistry} whose {@code getValue} hands back {@code minecraft:air} rather than
     * null; it is spelled out below instead, so that #91 - which decides whether a missing block is
     * a skipped entry, a load error, or air - has one line to change rather than a registry quirk to
     * discover. The warning is the part that costs nothing and can move no golden, because the state
     * returned is unchanged.
     * <p>
     * The pre-flattening {@code name@meta} form never reaches {@code upgradeBlock}:
     * {@code Identifier.parse} throws for it first, {@code @} not being a legal path character - and
     * {@code BlockStateData.upgradeBlock(String)} does not handle that form anyway (measured: it
     * returns {@code minecraft:red_sandstone@2} unchanged). That is why the one such string the
     * bundled pack shipped was a hard crash at palette compile rather than an automatic upgrade.
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
        if (s.contains("[")) {
            try {
                BlockStateParser.BlockResult parser = BlockStateParser.parseForBlock(blockLookup, new StringReader(s), false);
                return parser.blockState();
            } catch (CommandSyntaxException e) {
                throw new RuntimeException(e);
            }
        }

        Optional<Holder.Reference<Block>> requested = blockLookup.get(blockKey(s));
        if (requested.isPresent()) {
            return requested.get().value().defaultBlockState();
        }

        Optional<Holder.Reference<Block>> upgraded = blockLookup.get(blockKey(BlockStateData.upgradeBlock(s)));
        if (upgraded.isPresent()) {
            return upgraded.get().value().defaultBlockState();
        }
        if (WARNED_MISSING_BLOCKS.add(s)) {
            Urbex.LOGGER.warn(
                    "Block '{}'{} does not exist in this Minecraft version; it will generate as air. " +
                            "It was most likely renamed - check the current id and update the asset.",
                    s, owner == null ? "" : " (in " + owner + ")");
        }
        return Blocks.AIR.defaultBlockState();
    }

    private static ResourceKey<Block> blockKey(String id) {
        return ResourceKey.create(Registries.BLOCK, Identifier.parse(id));
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

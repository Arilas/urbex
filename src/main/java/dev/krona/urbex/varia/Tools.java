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
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
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
     * Resolves a datapack block string, naming {@code owner} if it cannot be resolved.
     * <p>
     * <b>An unknown id still returns air, exactly as before.</b> {@code BuiltInRegistries.BLOCK} is
     * a {@link DefaultedRegistry}, so {@code getValue} hands back the registry's default value -
     * {@code minecraft:air} - for an id it does not know, never null. The {@code value == null}
     * guard below has therefore never fired, and an id that a Minecraft version renames becomes air
     * everywhere it is used with no exception and no log line. That is how {@code minecraft:chain}
     * (renamed {@code minecraft:iron_chain} in 26.x) made the whole {@code urbex:chains} decoration
     * invisible without anyone noticing. Turning it into a load error is a contract change for every
     * third-party pack and is tracked separately; the warning below is the part that costs nothing
     * and can move no golden, because the state returned is unchanged.
     * <p>
     * The legacy branch under it is dead in both directions, and is kept only because deleting it is
     * a separate decision. {@code Identifier.parse} on the line above throws for a pre-flattening
     * {@code name@meta} string - {@code @} is not a legal path character - so such a string can
     * never reach {@code upgradeBlock}; and {@code BlockStateData.upgradeBlock(String)} does not
     * handle that form anyway (measured: it returns {@code minecraft:red_sandstone@2} unchanged).
     * That is why the one such string the bundled pack shipped was a hard crash at palette compile
     * rather than an automatic upgrade.
     *
     * @param owner the asset the string came from, used only in the warning. This is the asset
     *              <em>id</em>, not the file path, and for a palette written inline in a part or
     *              building it is the synthetic {@code urbex:__local__<path>} name
     *              {@link dev.krona.urbex.worldgen.lost.cityassets.Palette#inline} builds rather
     *              than the owning part - close enough to find, but not a filename. May be null;
     *              every production caller passes one.
     */
    public static BlockState stringToState(String s, @Nullable Object owner) {
        if (s.contains("[")) {
            try {
                HolderLookup<Block> blocks = ServerAccess.getServer() == null
                        ? BuiltInRegistries.BLOCK
                        : WorldTools.getOverworld().holderLookup(Registries.BLOCK);
                BlockStateParser.BlockResult parser = BlockStateParser.parseForBlock(blocks, new StringReader(s), false);
                return parser.blockState();
            } catch (CommandSyntaxException e) {
                throw new RuntimeException(e);
            }
        }

        Identifier requested = Identifier.parse(s);
        if (BuiltInRegistries.BLOCK.containsKey(requested)) {
            return BuiltInRegistries.BLOCK.getValue(requested).defaultBlockState();
        }

        String converted = BlockStateData.upgradeBlock(s);
        Identifier convertedId = Identifier.parse(converted);
        if (!BuiltInRegistries.BLOCK.containsKey(convertedId) && WARNED_MISSING_BLOCKS.add(s)) {
            Urbex.LOGGER.warn(
                    "Block '{}'{} does not exist in this Minecraft version; it will generate as air. " +
                            "It was most likely renamed - check the current id and update the asset.",
                    s, owner == null ? "" : " (in " + owner + ")");
        }
        Block value = BuiltInRegistries.BLOCK.getValue(convertedId);
        if (value == null) {
            throw new RuntimeException("Cannot find block: '" + s + "'!");
        }
        return value.defaultBlockState();
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

    public static Iterable<Holder<Block>> getBlocksForTag(TagKey<Block> rl) {
        @SuppressWarnings("deprecation") DefaultedRegistry<Block> registry = BuiltInRegistries.BLOCK;
        return registry.getTagOrEmpty(rl);
    }

    public static boolean hasTag(Block block, TagKey<Block> tag) {
        //noinspection deprecation
        return BuiltInRegistries.BLOCK.get(block.builtInRegistryHolder().key()).get().is(tag);
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

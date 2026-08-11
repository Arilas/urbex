package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.Preset;

import java.util.Optional;
import java.util.Set;

public record DestructionSettings(
        Optional<Float> ruinChance,
        Optional<Float> ruinMinlevelPercent,
        Optional<Float> ruinMaxlevelPercent,
        Optional<Boolean> rubbleLayer,
        Optional<Float> rubbleDirtScale,
        Optional<Float> rubbleLeaveScale,
        Optional<Float> explosionChance,
        Optional<Integer> explosionMinRadius,
        Optional<Integer> explosionMaxRadius,
        Optional<Integer> explosionMinHeight,
        Optional<Integer> explosionMaxHeight,
        Optional<Float> miniExplosionChance,
        Optional<Integer> miniExplosionMinRadius,
        Optional<Integer> miniExplosionMaxRadius,
        Optional<Integer> miniExplosionMinHeight,
        Optional<Integer> miniExplosionMaxHeight,
        Optional<Boolean> explosionsInCitiesOnly,
        Optional<Integer> debrisToNearbyChunkFactor) {

    public static final Set<String> KEYS = Set.of("ruinChance", "ruinMinlevelPercent", "ruinMaxlevelPercent", "rubbleLayer", "rubbleDirtScale", "rubbleLeaveScale", "explosionChance", "explosionMinRadius", "explosionMaxRadius", "explosionMinHeight", "explosionMaxHeight", "miniExplosionChance", "miniExplosionMinRadius", "miniExplosionMaxRadius", "miniExplosionMinHeight", "miniExplosionMaxHeight", "explosionsInCitiesOnly", "debrisToNearbyChunkFactor");

    private record Part1(
            Optional<Float> ruinChance,
            Optional<Float> ruinMinlevelPercent,
            Optional<Float> ruinMaxlevelPercent,
            Optional<Boolean> rubbleLayer,
            Optional<Float> rubbleDirtScale,
            Optional<Float> rubbleLeaveScale,
            Optional<Float> explosionChance,
            Optional<Integer> explosionMinRadius,
            Optional<Integer> explosionMaxRadius,
            Optional<Integer> explosionMinHeight,
            Optional<Integer> explosionMaxHeight,
            Optional<Float> miniExplosionChance,
            Optional<Integer> miniExplosionMinRadius,
            Optional<Integer> miniExplosionMaxRadius) {
        private static final MapCodec<Part1> CODEC = RecordCodecBuilder.mapCodec(i ->
                i.group(
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("ruinChance").forGetter(Part1::ruinChance),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("ruinMinlevelPercent").forGetter(Part1::ruinMinlevelPercent),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("ruinMaxlevelPercent").forGetter(Part1::ruinMaxlevelPercent),
                        Codec.BOOL.optionalFieldOf("rubbleLayer").forGetter(Part1::rubbleLayer),
                        Codec.floatRange(0.0f, 100.0f).optionalFieldOf("rubbleDirtScale").forGetter(Part1::rubbleDirtScale),
                        Codec.floatRange(0.0f, 100.0f).optionalFieldOf("rubbleLeaveScale").forGetter(Part1::rubbleLeaveScale),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("explosionChance").forGetter(Part1::explosionChance),
                        Codec.intRange(1, 1000).optionalFieldOf("explosionMinRadius").forGetter(Part1::explosionMinRadius),
                        Codec.intRange(1, 3000).optionalFieldOf("explosionMaxRadius").forGetter(Part1::explosionMaxRadius),
                        Codec.intRange(1, 256).optionalFieldOf("explosionMinHeight").forGetter(Part1::explosionMinHeight),
                        Codec.intRange(1, 256).optionalFieldOf("explosionMaxHeight").forGetter(Part1::explosionMaxHeight),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("miniExplosionChance").forGetter(Part1::miniExplosionChance),
                        Codec.intRange(1, 1000).optionalFieldOf("miniExplosionMinRadius").forGetter(Part1::miniExplosionMinRadius),
                        Codec.intRange(1, 3000).optionalFieldOf("miniExplosionMaxRadius").forGetter(Part1::miniExplosionMaxRadius)
                ).apply(i, Part1::new));
    }

    private record Part2(
            Optional<Integer> miniExplosionMinHeight,
            Optional<Integer> miniExplosionMaxHeight,
            Optional<Boolean> explosionsInCitiesOnly,
            Optional<Integer> debrisToNearbyChunkFactor) {
        private static final MapCodec<Part2> CODEC = RecordCodecBuilder.mapCodec(i ->
                i.group(
                        Codec.intRange(1, 256).optionalFieldOf("miniExplosionMinHeight").forGetter(Part2::miniExplosionMinHeight),
                        Codec.intRange(1, 256).optionalFieldOf("miniExplosionMaxHeight").forGetter(Part2::miniExplosionMaxHeight),
                        Codec.BOOL.optionalFieldOf("explosionsInCitiesOnly").forGetter(Part2::explosionsInCitiesOnly),
                        Codec.intRange(1, 10000).optionalFieldOf("debrisToNearbyChunkFactor").forGetter(Part2::debrisToNearbyChunkFactor)
                ).apply(i, Part2::new));
    }

    private static final Codec<DestructionSettings> RAW = Codec.mapPair(Part1.CODEC, Part2.CODEC).xmap(
            pair -> new DestructionSettings(
                    pair.getFirst().ruinChance(),
                    pair.getFirst().ruinMinlevelPercent(),
                    pair.getFirst().ruinMaxlevelPercent(),
                    pair.getFirst().rubbleLayer(),
                    pair.getFirst().rubbleDirtScale(),
                    pair.getFirst().rubbleLeaveScale(),
                    pair.getFirst().explosionChance(),
                    pair.getFirst().explosionMinRadius(),
                    pair.getFirst().explosionMaxRadius(),
                    pair.getFirst().explosionMinHeight(),
                    pair.getFirst().explosionMaxHeight(),
                    pair.getFirst().miniExplosionChance(),
                    pair.getFirst().miniExplosionMinRadius(),
                    pair.getFirst().miniExplosionMaxRadius(),
                    pair.getSecond().miniExplosionMinHeight(),
                    pair.getSecond().miniExplosionMaxHeight(),
                    pair.getSecond().explosionsInCitiesOnly(),
                    pair.getSecond().debrisToNearbyChunkFactor()),
            s -> com.mojang.datafixers.util.Pair.of(
                    new Part1(s.ruinChance(), s.ruinMinlevelPercent(), s.ruinMaxlevelPercent(), s.rubbleLayer(), s.rubbleDirtScale(), s.rubbleLeaveScale(), s.explosionChance(), s.explosionMinRadius(), s.explosionMaxRadius(), s.explosionMinHeight(), s.explosionMaxHeight(), s.miniExplosionChance(), s.miniExplosionMinRadius(), s.miniExplosionMaxRadius()),
                    new Part2(s.miniExplosionMinHeight(), s.miniExplosionMaxHeight(), s.explosionsInCitiesOnly(), s.debrisToNearbyChunkFactor()))
    ).codec();
    public static final Codec<DestructionSettings> CODEC = UnknownKeys.warning(RAW, KEYS, "destruction");

    public void apply(Preset p) {
        ruinChance.ifPresent(v -> p.RUIN_CHANCE = v);
        ruinMinlevelPercent.ifPresent(v -> p.RUIN_MINLEVEL_PERCENT = v);
        ruinMaxlevelPercent.ifPresent(v -> p.RUIN_MAXLEVEL_PERCENT = v);
        rubbleLayer.ifPresent(v -> p.RUBBLELAYER = v);
        rubbleDirtScale.ifPresent(v -> p.RUBBLE_DIRT_SCALE = v);
        rubbleLeaveScale.ifPresent(v -> p.RUBBLE_LEAVE_SCALE = v);
        explosionChance.ifPresent(v -> p.EXPLOSION_CHANCE = v);
        explosionMinRadius.ifPresent(v -> p.EXPLOSION_MINRADIUS = v);
        explosionMaxRadius.ifPresent(v -> p.EXPLOSION_MAXRADIUS = v);
        explosionMinHeight.ifPresent(v -> p.EXPLOSION_MINHEIGHT = v);
        explosionMaxHeight.ifPresent(v -> p.EXPLOSION_MAXHEIGHT = v);
        miniExplosionChance.ifPresent(v -> p.MINI_EXPLOSION_CHANCE = v);
        miniExplosionMinRadius.ifPresent(v -> p.MINI_EXPLOSION_MINRADIUS = v);
        miniExplosionMaxRadius.ifPresent(v -> p.MINI_EXPLOSION_MAXRADIUS = v);
        miniExplosionMinHeight.ifPresent(v -> p.MINI_EXPLOSION_MINHEIGHT = v);
        miniExplosionMaxHeight.ifPresent(v -> p.MINI_EXPLOSION_MAXHEIGHT = v);
        explosionsInCitiesOnly.ifPresent(v -> p.EXPLOSIONS_IN_CITIES_ONLY = v);
        debrisToNearbyChunkFactor.ifPresent(v -> p.DEBRIS_TO_NEARBYCHUNK_FACTOR = v);
    }
}
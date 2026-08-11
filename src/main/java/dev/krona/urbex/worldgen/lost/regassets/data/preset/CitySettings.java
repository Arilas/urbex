package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.Preset;

import java.util.Optional;
import java.util.Set;

public record CitySettings(
        Optional<Double> cityChance,
        Optional<Integer> cityMinRadius,
        Optional<Integer> cityMaxRadius,
        Optional<Double> cityPerlinScale,
        Optional<Double> cityPerlinOffset,
        Optional<Double> cityPerlinInnerScale,
        Optional<Float> cityThreshold,
        Optional<Integer> citySpawnDistance1,
        Optional<Integer> citySpawnDistance2,
        Optional<Double> citySpawnMultiplier1,
        Optional<Double> citySpawnMultiplier2,
        Optional<Float> cityStyleThreshold,
        Optional<String> cityStyleAlternative,
        Optional<Boolean> cityAvoidVoid,
        Optional<Integer> cityLevel0Height,
        Optional<Integer> cityLevel1Height,
        Optional<Integer> cityLevel2Height,
        Optional<Integer> cityLevel3Height,
        Optional<Integer> cityLevel4Height,
        Optional<Integer> cityLevel5Height,
        Optional<Integer> cityLevel6Height,
        Optional<Integer> cityLevel7Height,
        Optional<Integer> cityMinHeight,
        Optional<Integer> cityMaxHeight,
        Optional<Float> scatteredChanceMultiplier) {

    public static final Set<String> KEYS = Set.of("cityChance", "cityMinRadius", "cityMaxRadius", "cityPerlinScale", "cityPerlinOffset", "cityPerlinInnerScale", "cityThreshold", "citySpawnDistance1", "citySpawnDistance2", "citySpawnMultiplier1", "citySpawnMultiplier2", "cityStyleThreshold", "cityStyleAlternative", "cityAvoidVoid", "cityLevel0Height", "cityLevel1Height", "cityLevel2Height", "cityLevel3Height", "cityLevel4Height", "cityLevel5Height", "cityLevel6Height", "cityLevel7Height", "cityMinHeight", "cityMaxHeight", "scatteredChanceMultiplier");

    private record Part1(
            Optional<Double> cityChance,
            Optional<Integer> cityMinRadius,
            Optional<Integer> cityMaxRadius,
            Optional<Double> cityPerlinScale,
            Optional<Double> cityPerlinOffset,
            Optional<Double> cityPerlinInnerScale,
            Optional<Float> cityThreshold,
            Optional<Integer> citySpawnDistance1,
            Optional<Integer> citySpawnDistance2,
            Optional<Double> citySpawnMultiplier1,
            Optional<Double> citySpawnMultiplier2,
            Optional<Float> cityStyleThreshold,
            Optional<String> cityStyleAlternative,
            Optional<Boolean> cityAvoidVoid) {
        private static final MapCodec<Part1> CODEC = RecordCodecBuilder.mapCodec(i ->
                i.group(
                        Codec.doubleRange(-1.0, 1.0).optionalFieldOf("cityChance").forGetter(Part1::cityChance),
                        Codec.intRange(1, 2000).optionalFieldOf("cityMinRadius").forGetter(Part1::cityMinRadius),
                        Codec.intRange(1, 2000).optionalFieldOf("cityMaxRadius").forGetter(Part1::cityMaxRadius),
                        Codec.doubleRange(-1000000, 1000000).optionalFieldOf("cityPerlinScale").forGetter(Part1::cityPerlinScale),
                        Codec.doubleRange(-1000000, 1000000).optionalFieldOf("cityPerlinOffset").forGetter(Part1::cityPerlinOffset),
                        Codec.doubleRange(-1000000, 1000000).optionalFieldOf("cityPerlinInnerScale").forGetter(Part1::cityPerlinInnerScale),
                        Codec.floatRange(0.0f, 1.0f).optionalFieldOf("cityThreshold").forGetter(Part1::cityThreshold),
                        Codec.intRange(0, 10000000).optionalFieldOf("citySpawnDistance1").forGetter(Part1::citySpawnDistance1),
                        Codec.intRange(0, 10000000).optionalFieldOf("citySpawnDistance2").forGetter(Part1::citySpawnDistance2),
                        Codec.doubleRange(0, 1).optionalFieldOf("citySpawnMultiplier1").forGetter(Part1::citySpawnMultiplier1),
                        Codec.doubleRange(0, 1).optionalFieldOf("citySpawnMultiplier2").forGetter(Part1::citySpawnMultiplier2),
                        Codec.floatRange(-1.0f, 1.0f).optionalFieldOf("cityStyleThreshold").forGetter(Part1::cityStyleThreshold),
                        Codec.STRING.optionalFieldOf("cityStyleAlternative").forGetter(Part1::cityStyleAlternative),
                        Codec.BOOL.optionalFieldOf("cityAvoidVoid").forGetter(Part1::cityAvoidVoid)
                ).apply(i, Part1::new));
    }

    private record Part2(
            Optional<Integer> cityLevel0Height,
            Optional<Integer> cityLevel1Height,
            Optional<Integer> cityLevel2Height,
            Optional<Integer> cityLevel3Height,
            Optional<Integer> cityLevel4Height,
            Optional<Integer> cityLevel5Height,
            Optional<Integer> cityLevel6Height,
            Optional<Integer> cityLevel7Height,
            Optional<Integer> cityMinHeight,
            Optional<Integer> cityMaxHeight,
            Optional<Float> scatteredChanceMultiplier) {
        private static final MapCodec<Part2> CODEC = RecordCodecBuilder.mapCodec(i ->
                i.group(
                        Codec.intRange(1, 384).optionalFieldOf("cityLevel0Height").forGetter(Part2::cityLevel0Height),
                        Codec.intRange(1, 384).optionalFieldOf("cityLevel1Height").forGetter(Part2::cityLevel1Height),
                        Codec.intRange(1, 384).optionalFieldOf("cityLevel2Height").forGetter(Part2::cityLevel2Height),
                        Codec.intRange(1, 384).optionalFieldOf("cityLevel3Height").forGetter(Part2::cityLevel3Height),
                        Codec.intRange(1, 384).optionalFieldOf("cityLevel4Height").forGetter(Part2::cityLevel4Height),
                        Codec.intRange(1, 384).optionalFieldOf("cityLevel5Height").forGetter(Part2::cityLevel5Height),
                        Codec.intRange(1, 384).optionalFieldOf("cityLevel6Height").forGetter(Part2::cityLevel6Height),
                        Codec.intRange(1, 384).optionalFieldOf("cityLevel7Height").forGetter(Part2::cityLevel7Height),
                        Codec.intRange(-1024, 2048).optionalFieldOf("cityMinHeight").forGetter(Part2::cityMinHeight),
                        Codec.intRange(-1024, 2048).optionalFieldOf("cityMaxHeight").forGetter(Part2::cityMaxHeight),
                        Codec.floatRange(0.0f, 100.0f).optionalFieldOf("scatteredChanceMultiplier").forGetter(Part2::scatteredChanceMultiplier)
                ).apply(i, Part2::new));
    }

    private static final Codec<CitySettings> RAW = Codec.mapPair(Part1.CODEC, Part2.CODEC).xmap(
            pair -> new CitySettings(
                    pair.getFirst().cityChance(),
                    pair.getFirst().cityMinRadius(),
                    pair.getFirst().cityMaxRadius(),
                    pair.getFirst().cityPerlinScale(),
                    pair.getFirst().cityPerlinOffset(),
                    pair.getFirst().cityPerlinInnerScale(),
                    pair.getFirst().cityThreshold(),
                    pair.getFirst().citySpawnDistance1(),
                    pair.getFirst().citySpawnDistance2(),
                    pair.getFirst().citySpawnMultiplier1(),
                    pair.getFirst().citySpawnMultiplier2(),
                    pair.getFirst().cityStyleThreshold(),
                    pair.getFirst().cityStyleAlternative(),
                    pair.getFirst().cityAvoidVoid(),
                    pair.getSecond().cityLevel0Height(),
                    pair.getSecond().cityLevel1Height(),
                    pair.getSecond().cityLevel2Height(),
                    pair.getSecond().cityLevel3Height(),
                    pair.getSecond().cityLevel4Height(),
                    pair.getSecond().cityLevel5Height(),
                    pair.getSecond().cityLevel6Height(),
                    pair.getSecond().cityLevel7Height(),
                    pair.getSecond().cityMinHeight(),
                    pair.getSecond().cityMaxHeight(),
                    pair.getSecond().scatteredChanceMultiplier()),
            s -> com.mojang.datafixers.util.Pair.of(
                    new Part1(s.cityChance(), s.cityMinRadius(), s.cityMaxRadius(), s.cityPerlinScale(), s.cityPerlinOffset(), s.cityPerlinInnerScale(), s.cityThreshold(), s.citySpawnDistance1(), s.citySpawnDistance2(), s.citySpawnMultiplier1(), s.citySpawnMultiplier2(), s.cityStyleThreshold(), s.cityStyleAlternative(), s.cityAvoidVoid()),
                    new Part2(s.cityLevel0Height(), s.cityLevel1Height(), s.cityLevel2Height(), s.cityLevel3Height(), s.cityLevel4Height(), s.cityLevel5Height(), s.cityLevel6Height(), s.cityLevel7Height(), s.cityMinHeight(), s.cityMaxHeight(), s.scatteredChanceMultiplier()))
    ).codec();
    public static final Codec<CitySettings> CODEC = UnknownKeys.warning(RAW, KEYS, "cities");

    public void apply(Preset p) {
        cityChance.ifPresent(v -> p.CITY_CHANCE = v);
        cityMinRadius.ifPresent(v -> p.CITY_MINRADIUS = v);
        cityMaxRadius.ifPresent(v -> p.CITY_MAXRADIUS = v);
        cityPerlinScale.ifPresent(v -> p.CITY_PERLIN_SCALE = v);
        cityPerlinOffset.ifPresent(v -> p.CITY_PERLIN_OFFSET = v);
        cityPerlinInnerScale.ifPresent(v -> p.CITY_PERLIN_INNERSCALE = v);
        cityThreshold.ifPresent(v -> p.CITY_THRESHOLD = v);
        citySpawnDistance1.ifPresent(v -> p.CITY_SPAWN_DISTANCE1 = v);
        citySpawnDistance2.ifPresent(v -> p.CITY_SPAWN_DISTANCE2 = v);
        citySpawnMultiplier1.ifPresent(v -> p.CITY_SPAWN_MULTIPLIER1 = v);
        citySpawnMultiplier2.ifPresent(v -> p.CITY_SPAWN_MULTIPLIER2 = v);
        cityStyleThreshold.ifPresent(v -> p.CITY_STYLE_THRESHOLD = v);
        cityStyleAlternative.ifPresent(v -> p.CITY_STYLE_ALTERNATIVE = v);
        cityAvoidVoid.ifPresent(v -> p.CITY_AVOID_VOID = v);
        cityLevel0Height.ifPresent(v -> p.CITY_LEVEL0_HEIGHT = v);
        cityLevel1Height.ifPresent(v -> p.CITY_LEVEL1_HEIGHT = v);
        cityLevel2Height.ifPresent(v -> p.CITY_LEVEL2_HEIGHT = v);
        cityLevel3Height.ifPresent(v -> p.CITY_LEVEL3_HEIGHT = v);
        cityLevel4Height.ifPresent(v -> p.CITY_LEVEL4_HEIGHT = v);
        cityLevel5Height.ifPresent(v -> p.CITY_LEVEL5_HEIGHT = v);
        cityLevel6Height.ifPresent(v -> p.CITY_LEVEL6_HEIGHT = v);
        cityLevel7Height.ifPresent(v -> p.CITY_LEVEL7_HEIGHT = v);
        cityMinHeight.ifPresent(v -> p.CITY_MINHEIGHT = v);
        cityMaxHeight.ifPresent(v -> p.CITY_MAXHEIGHT = v);
        scatteredChanceMultiplier.ifPresent(v -> p.SCATTERED_CHANCE_MULTIPLIER = v);
    }
}

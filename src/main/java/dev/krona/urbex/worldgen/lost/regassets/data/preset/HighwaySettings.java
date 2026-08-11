package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.Preset;

import java.util.Optional;
import java.util.Set;

public record HighwaySettings(
        Optional<Boolean> highwayRequiresTwoCities,
        Optional<Integer> highwayLevelFromCities,
        Optional<Integer> highwayDistanceMask,
        Optional<Float> highwayMainPerlinScale,
        Optional<Float> highwaySecondaryPerlinScale,
        Optional<Float> highwayPerlinFactor,
        Optional<Boolean> highwaySupports) {

    public static final Set<String> KEYS = Set.of("highwayRequiresTwoCities", "highwayLevelFromCities", "highwayDistanceMask", "highwayMainPerlinScale", "highwaySecondaryPerlinScale", "highwayPerlinFactor", "highwaySupports");

    private static final Codec<HighwaySettings> RAW = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.BOOL.optionalFieldOf("highwayRequiresTwoCities").forGetter(HighwaySettings::highwayRequiresTwoCities),
                    Codec.intRange(0, 3).optionalFieldOf("highwayLevelFromCities").forGetter(HighwaySettings::highwayLevelFromCities),
                    Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("highwayDistanceMask").forGetter(HighwaySettings::highwayDistanceMask),
                    Codec.floatRange(1.0f, 1000.0f).optionalFieldOf("highwayMainPerlinScale").forGetter(HighwaySettings::highwayMainPerlinScale),
                    Codec.floatRange(1.0f, 1000.0f).optionalFieldOf("highwaySecondaryPerlinScale").forGetter(HighwaySettings::highwaySecondaryPerlinScale),
                    Codec.floatRange(-100f, 100f).optionalFieldOf("highwayPerlinFactor").forGetter(HighwaySettings::highwayPerlinFactor),
                    Codec.BOOL.optionalFieldOf("highwaySupports").forGetter(HighwaySettings::highwaySupports)
            ).apply(i, HighwaySettings::new));
    public static final Codec<HighwaySettings> CODEC = UnknownKeys.warning(RAW, KEYS, "highways");

    public void apply(Preset p) {
        highwayRequiresTwoCities.ifPresent(v -> p.HIGHWAY_REQUIRES_TWO_CITIES = v);
        highwayLevelFromCities.ifPresent(v -> p.HIGHWAY_LEVEL_FROM_CITIES_MODE = v);
        highwayDistanceMask.ifPresent(v -> p.HIGHWAY_DISTANCE_MASK = v);
        highwayMainPerlinScale.ifPresent(v -> p.HIGHWAY_MAINPERLIN_SCALE = v);
        highwaySecondaryPerlinScale.ifPresent(v -> p.HIGHWAY_SECONDARYPERLIN_SCALE = v);
        highwayPerlinFactor.ifPresent(v -> p.HIGHWAY_PERLIN_FACTOR = v);
        highwaySupports.ifPresent(v -> p.HIGHWAY_SUPPORTS = v);
    }
}
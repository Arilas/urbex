package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.Preset;

import java.util.Optional;
import java.util.Set;

public record AtmosphereSettings(
        Optional<Float> horizon,
        Optional<Float> fogRed,
        Optional<Float> fogGreen,
        Optional<Float> fogBlue,
        Optional<Float> fogDensity) {

    public static final Set<String> KEYS = Set.of("horizon", "fogRed", "fogGreen", "fogBlue", "fogDensity");

    private static final Codec<AtmosphereSettings> RAW = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.floatRange(-1f, 256f).optionalFieldOf("horizon").forGetter(AtmosphereSettings::horizon),
                    Codec.floatRange(-1f, 1f).optionalFieldOf("fogRed").forGetter(AtmosphereSettings::fogRed),
                    Codec.floatRange(-1f, 1f).optionalFieldOf("fogGreen").forGetter(AtmosphereSettings::fogGreen),
                    Codec.floatRange(-1f, 1f).optionalFieldOf("fogBlue").forGetter(AtmosphereSettings::fogBlue),
                    Codec.floatRange(-1f, 1f).optionalFieldOf("fogDensity").forGetter(AtmosphereSettings::fogDensity)
            ).apply(i, AtmosphereSettings::new));
    public static final Codec<AtmosphereSettings> CODEC = UnknownKeys.warning(RAW, KEYS, "atmosphere");

    public void apply(Preset p) {
        horizon.ifPresent(v -> p.HORIZON = v);
        fogRed.ifPresent(v -> p.FOG_RED = v);
        fogGreen.ifPresent(v -> p.FOG_GREEN = v);
        fogBlue.ifPresent(v -> p.FOG_BLUE = v);
        fogDensity.ifPresent(v -> p.FOG_DENSITY = v);
    }
}
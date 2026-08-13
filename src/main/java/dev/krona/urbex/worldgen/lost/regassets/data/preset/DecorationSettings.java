package dev.krona.urbex.worldgen.lost.regassets.data.preset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.config.PresetDraft;

import java.util.Optional;
import java.util.Set;

public record DecorationSettings(
        Optional<Float> randomLeafBlockChance,
        Optional<Integer> randomLeafBlockThickness,
        Optional<Boolean> avoidFoliage,
        Optional<Float> lightingDensity,
        Optional<Float> lootDensity) {

    public static final Set<String> KEYS = Set.of("randomLeafBlockChance", "randomLeafBlockThickness", "avoidFoliage", "lightingDensity", "lootDensity");

    private static final Codec<DecorationSettings> RAW = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.floatRange(0.0f, 1.0f).optionalFieldOf("randomLeafBlockChance").forGetter(DecorationSettings::randomLeafBlockChance),
                    Codec.intRange(1, 8).optionalFieldOf("randomLeafBlockThickness").forGetter(DecorationSettings::randomLeafBlockThickness),
                    Codec.BOOL.optionalFieldOf("avoidFoliage").forGetter(DecorationSettings::avoidFoliage),
                    Codec.floatRange(0.0f, 1.0f).optionalFieldOf("lightingDensity").forGetter(DecorationSettings::lightingDensity),
                    Codec.floatRange(0.0f, 1.0f).optionalFieldOf("lootDensity").forGetter(DecorationSettings::lootDensity)
            ).apply(i, DecorationSettings::new));
    public static final Codec<DecorationSettings> CODEC = UnknownKeys.warning(RAW, KEYS, "decoration");

    public void apply(PresetDraft p) {
        randomLeafBlockChance.ifPresent(v -> p.CHANCE_OF_RANDOM_LEAFBLOCKS = v);
        randomLeafBlockThickness.ifPresent(v -> p.THICKNESS_OF_RANDOM_LEAFBLOCKS = v);
        avoidFoliage.ifPresent(v -> p.AVOID_FOLIAGE = v);
        lightingDensity.ifPresent(v -> p.LIGHTING_DENSITY = v);
        lootDensity.ifPresent(v -> p.LOOT_DENSITY = v);
    }
}

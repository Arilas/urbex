package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record CityStyleEdge(String citystyle, float threshold) {

    private static final Codec<Float> THRESHOLD = Codec.FLOAT.validate(value ->
            Float.isFinite(value) && value > 0.0f && value <= 1.0f
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Edge threshold must be finite and satisfy 0 < threshold <= 1"));

    public static final Codec<CityStyleEdge> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_REFERENCE_CODEC.fieldOf("citystyle").forGetter(CityStyleEdge::citystyle),
                    THRESHOLD.fieldOf("threshold").forGetter(CityStyleEdge::threshold)
            ).apply(instance, CityStyleEdge::new));
}

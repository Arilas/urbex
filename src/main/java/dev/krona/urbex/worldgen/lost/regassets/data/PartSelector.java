package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

public record PartSelector(HighwayParts highwayParts, RailwayParts railwayParts) {

    public static final Codec<PartSelector> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    HighwayParts.CODEC.optionalFieldOf("highways").forGetter(l -> l.highwayParts.get()),
                    RailwayParts.CODEC.optionalFieldOf("railways").forGetter(l -> l.railwayParts.get())
            ).apply(instance, (highways, railways) -> new PartSelector(
                    highways.orElse(HighwayParts.DEFAULT),
                    railways.orElse(RailwayParts.DEFAULT))));

    public static final PartSelector DEFAULT =
            new PartSelector(HighwayParts.DEFAULT, RailwayParts.DEFAULT);

    public Optional<PartSelector> get() {
        if (this == DEFAULT) {
            return Optional.empty();
        }
        return Optional.of(this);
    }

}

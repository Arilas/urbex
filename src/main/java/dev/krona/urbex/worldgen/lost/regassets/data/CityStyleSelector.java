package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * A selector for a citystyle (for a worldstyle)
 */
public record CityStyleSelector(float factor, String citystyle, BiomeMatcher biomeMatcher,
                                Optional<CityStyleEdge> edge) {

    public CityStyleSelector(float factor, String citystyle, BiomeMatcher biomeMatcher) {
        this(factor, citystyle, biomeMatcher, Optional.empty());
    }

    public CityStyleSelection selection() {
        return new CityStyleSelection(citystyle, edge);
    }

    public static final Codec<CityStyleSelector> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.fieldOf("factor").forGetter(CityStyleSelector::factor),
                    Codec.STRING.fieldOf("citystyle").forGetter(CityStyleSelector::citystyle),
                    BiomeMatcher.CODEC.optionalFieldOf("biomes").forGetter(l -> Optional.ofNullable(l.biomeMatcher)),
                    CityStyleEdge.CODEC.optionalFieldOf("edge").forGetter(CityStyleSelector::edge)
            ).apply(instance, (factor, citystyle, biomes, edge) ->
                    new CityStyleSelector(factor, citystyle, biomes.orElse(null), edge)));
}

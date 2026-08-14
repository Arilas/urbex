package dev.krona.urbex.worldgen.lost.regassets.data;

import java.util.Optional;

public record CityStyleSelection(String citystyle, Optional<CityStyleEdge> edge) {

    public static CityStyleSelection baseOnly(String citystyle) {
        return new CityStyleSelection(citystyle, Optional.empty());
    }

    public String styleAt(float cityFactor) {
        return edge.filter(value -> cityFactor < value.threshold())
                .map(CityStyleEdge::citystyle)
                .orElse(citystyle);
    }
}

package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.varia.Counter;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.ObjectSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.Selectors;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiChunkTest {

    @Test
    void multibuildingDrawExcludesStylesThatExplicitlyOptOut() {
        CityStyle border = TestProfiles.cityStyle();
        CityStyle centre = cityStyleWithMultiBuilding();
        Counter<CityStyle> styles = new Counter<>();
        styles.add(border);
        styles.add(border);
        styles.add(centre);

        assertEquals(List.of(centre), MultiChunk.eligibleMultiBuildingStyles(styles),
                "an edge style with an empty multibuilding selector must not be sampled as a multibuilding source");
    }

    private static CityStyle cityStyleWithMultiBuilding() {
        ObjectSelector multi = new ObjectSelector(1.0f, "urbex:multi1", 0, Integer.MAX_VALUE, 0);
        Selectors selectors = new Selectors(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new Mergeable<>(true, List.of(multi))));
        CityStyleDefinition definition = new CityStyleDefinition(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(TestWiring.streetSettings()), Optional.of(selectors));
        return new CityStyle(Identifier.fromNamespaceAndPath("urbextest", "centre"), List.of(definition));
    }
}

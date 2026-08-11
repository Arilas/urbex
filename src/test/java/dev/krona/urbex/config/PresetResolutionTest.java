package dev.krona.urbex.config;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless: {@code Presets.resolve(Identifier, Function)} needs no registry or level. */
class PresetResolutionTest {

    private static PresetRE decode(String json) {
        JsonElement element = com.google.gson.JsonParser.parseString(json);
        return PresetRE.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("urbex", path);
    }

    @Test
    void parentlessPresetGetsCodeDefaults() {
        Identifier presetId = id("leaf");
        Map<Identifier, PresetRE> lookup = Map.of(presetId, decode("{}"));

        Preset p = Presets.resolve(presetId, lookup::get);

        assertTrue(p.USE_AVG_HEIGHTMAP);
        assertEquals(0.15f, p.LIGHTING_DENSITY);
        assertEquals(71, p.GROUNDLEVEL);
    }

    @Test
    void childOverridesOnlyItsOwnFields() {
        Identifier parentId = id("parent");
        Identifier childId = id("child");

        Map<Identifier, PresetRE> lookup = new HashMap<>();
        lookup.put(parentId, decode("{\"cities\":{\"cityChance\":0.5}}"));
        lookup.put(childId, decode("{\"parent\":\"urbex:parent\",\"destruction\":{\"ruinChance\":0.9}}"));

        Preset p = Presets.resolve(childId, lookup::get);

        assertEquals(0.5, p.CITY_CHANCE);
        assertEquals(0.9f, p.RUIN_CHANCE);
        // untouched fields keep their code defaults
        assertEquals(0.3f, p.BUILDING_CHANCE);
        assertEquals(0.8f, p.RUIN_MINLEVEL_PERCENT);
    }

    @Test
    void grandparentChainAppliesRootFirst() {
        Identifier rootId = id("root");
        Identifier middleId = id("middle");
        Identifier leafId = id("leaf");

        Map<Identifier, PresetRE> lookup = new HashMap<>();
        lookup.put(rootId, decode("{\"cities\":{\"cityChance\":0.1}}"));
        lookup.put(middleId, decode("{\"parent\":\"urbex:root\","
                + "\"cities\":{\"cityChance\":0.2},\"buildings\":{\"buildingChance\":0.3}}"));
        lookup.put(leafId, decode("{\"parent\":\"urbex:middle\",\"buildings\":{\"buildingChance\":0.4}}"));

        Preset p = Presets.resolve(leafId, lookup::get);

        assertEquals(0.2, p.CITY_CHANCE);       // middle overrides root; leaf doesn't touch it
        assertEquals(0.4f, p.BUILDING_CHANCE);  // leaf wins over middle
    }

    @Test
    void cycleIsError() {
        Identifier aId = id("a");
        Identifier bId = id("b");

        Map<Identifier, PresetRE> lookup = new HashMap<>();
        lookup.put(aId, decode("{\"parent\":\"urbex:b\"}"));
        lookup.put(bId, decode("{\"parent\":\"urbex:a\"}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Presets.resolve(aId, lookup::get));
        assertTrue(ex.getMessage().contains("urbex:a"));
        assertTrue(ex.getMessage().contains("urbex:b"));
    }

    @Test
    void danglingParentIsError() {
        Identifier leafId = id("leaf");
        Map<Identifier, PresetRE> lookup = Map.of(leafId, decode("{\"parent\":\"urbex:ghost\"}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Presets.resolve(leafId, lookup::get));
        assertTrue(ex.getMessage().contains("urbex:ghost"));
    }
}

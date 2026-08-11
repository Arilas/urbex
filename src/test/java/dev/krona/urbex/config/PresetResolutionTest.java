package dev.krona.urbex.config;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PresetRE;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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

    /** Builds a {@code PresetRE} with only the {@code extends} field set. */
    private static PresetRE presetWithExtends(Identifier extendsId) {
        return new PresetRE(Optional.of(extendsId), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    @Test
    void extendslessPresetGetsCodeDefaults() {
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
        lookup.put(childId, decode("{\"extends\":\"urbex:parent\",\"destruction\":{\"ruinChance\":0.9}}"));

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
        lookup.put(middleId, decode("{\"extends\":\"urbex:root\","
                + "\"cities\":{\"cityChance\":0.2},\"buildings\":{\"buildingChance\":0.3}}"));
        lookup.put(leafId, decode("{\"extends\":\"urbex:middle\",\"buildings\":{\"buildingChance\":0.4}}"));

        Preset p = Presets.resolve(leafId, lookup::get);

        assertEquals(0.2, p.CITY_CHANCE);       // middle overrides root; leaf doesn't touch it
        assertEquals(0.4f, p.BUILDING_CHANCE);  // leaf wins over middle
    }

    @Test
    void cycleIsError() {
        Identifier aId = id("a");
        Identifier bId = id("b");

        Map<Identifier, PresetRE> lookup = new HashMap<>();
        lookup.put(aId, decode("{\"extends\":\"urbex:b\"}"));
        lookup.put(bId, decode("{\"extends\":\"urbex:a\"}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Presets.resolve(aId, lookup::get));
        assertTrue(ex.getMessage().contains("urbex:a"));
        assertTrue(ex.getMessage().contains("urbex:b"));
    }

    @Test
    void danglingExtendsIsError() {
        Identifier leafId = id("leaf");
        Map<Identifier, PresetRE> lookup = Map.of(leafId, decode("{\"extends\":\"urbex:ghost\"}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Presets.resolve(leafId, lookup::get));
        assertTrue(ex.getMessage().contains("urbex:ghost"));
    }

    @Test
    void danglingExtendsNamesBothEnds() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Presets.resolve(Identifier.fromNamespaceAndPath("urbex", "child"),
                        id -> id.getPath().equals("child")
                                ? presetWithExtends(Identifier.fromNamespaceAndPath("urbex", "nope"))
                                : null));
        assertTrue(e.getMessage().contains("urbex:nope"));
        assertTrue(e.getMessage().contains("urbex:child"));
    }

    /**
     * The pure core ({@code Presets.resolve(Identifier, Function)}) caches nothing itself - unlike
     * the registry-backed {@code Presets.resolve(RegistryAccess, Identifier)} wrapper, whose static
     * {@code CACHE} is keyed by id alone and only cleared by {@code AssetRegistries.reset()}. This
     * is exactly what lets {@code CitiesTab.registeredPresets} call the pure core directly with a
     * lookup bound to its own {@code RegistryAccess} and see a fresh resolution every time, instead
     * of a resolution some earlier registry context (e.g. a previously played world) left behind.
     * <p>
     * A live {@code RegistryAccess}/{@code Registry} can't be constructed headless without a full
     * game bootstrap, so this test stands in for the GUI call site: it proves the property the fix
     * actually depends on (the pure resolver is stateless across calls) using the same {@code
     * Map::get}-shaped lookup {@code CitiesTab} passes in.
     */
    @Test
    void pureResolveReflectsALookupChangeBetweenCalls() {
        Identifier presetId = id("mutable");
        Map<Identifier, PresetRE> lookup = new HashMap<>();
        lookup.put(presetId, decode("{\"cities\":{\"cityChance\":0.1}}"));

        Preset first = Presets.resolve(presetId, lookup::get);
        assertEquals(0.1, first.CITY_CHANCE);

        // Simulates a datapack toggle / new registry context redefining the same id - nothing about
        // the pure core remembers the first call.
        lookup.put(presetId, decode("{\"cities\":{\"cityChance\":0.9}}"));
        Preset second = Presets.resolve(presetId, lookup::get);

        assertEquals(0.9, second.CITY_CHANCE);
    }
}

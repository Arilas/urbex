package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.LandscapeType;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.DimensionCaches;
import dev.krona.urbex.worldgen.LevelShape;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.TerrainSampler;
import dev.krona.urbex.worldgen.TestWorldStyles;
import dev.krona.urbex.worldgen.WorldStyleField;
import dev.krona.urbex.worldgen.lost.cityassets.AssetIndex;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.cityassets.MultiBuilding;
import dev.krona.urbex.worldgen.lost.cityassets.PredefinedCity;
import dev.krona.urbex.worldgen.lost.cityassets.PredefinedIndex;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.BiomeMatcher;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleEdge;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelection;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityStyleScopeTest {

    private static Holder<Biome> HOT;
    private static Holder<Biome> COLD;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HOT = Holder.direct(biome(1.0f));
        COLD = Holder.direct(biome(0.0f));
    }

    @Test
    void centredCityObserversResolveBaseAndEdgeFromTheFamilyChosenAtTheCentre() {
        WorldStyle worldStyle = TestWorldStyles.minimal("centred-family", List.of(
                selector(1.0f, "alpha_base", "alpha_edge", 0.5f, null)));
        PredefinedCity city = predefined("centre", 0, 0, 64, null);
        PlanningContext planning = context(41L, centredPreset(), worldStyle, List.of(city),
                (x, z) -> HOT, "alpha_base", "alpha_edge");

        CityStyleSelection family = City.getCityStyleSelectionForCityCenter(coord(0, 0), planning);

        assertEquals("urbextest:alpha_base", family.citystyle());
        assertEquals("urbextest:alpha_base", City.getCityStyle(coord(1, 0), planning, planning.preset()).getName());
        assertEquals("urbextest:alpha_edge", City.getCityStyle(coord(3, 0), planning, planning.preset()).getName());
    }

    @Test
    void centredCityFamilyUsesTheCentreBiomeRatherThanTheObserversBiome() {
        WorldStyle worldStyle = TestWorldStyles.minimal("centred-biome", List.of(
                selector(1.0f, "hot", null, 0, matches(HOT)),
                selector(1.0f, "cold", null, 0, matches(COLD))));
        PredefinedCity city = predefined("centre", 0, 0, 64, null);
        PlanningContext planning = context(42L, centredPreset(), worldStyle, List.of(city),
                (x, z) -> x == 0 && z == 0 ? HOT : COLD, "hot", "cold");

        assertEquals("urbextest:hot", City.getCityStyle(coord(3, 0), planning, planning.preset()).getName());
    }

    @Test
    void centredSelectorGapStaysNullInsteadOfRerollingAtTheObserverBiome() {
        WorldStyle worldStyle = TestWorldStyles.minimal("centred-gap", List.of(
                selector(1.0f, "cold", null, 0, matches(COLD))));
        PredefinedCity city = predefined("centre", 0, 0, 64, null);
        PlanningContext planning = context(142L, centredPreset(), worldStyle, List.of(city),
                (x, z) -> x == 0 && z == 0 ? HOT : COLD, "cold");

        assertNull(City.getCityStyle(coord(3, 0), planning, planning.preset()),
                "the HOT centre selected no family; the COLD observer must not get another draw");
    }

    @Test
    void overlappingCentresKeepTheirFactorWeightsAfterBaseEdgeResolution() {
        WorldStyle worldStyle = TestWorldStyles.minimal("overlap", List.of(
                selector(1.0f, "hot_base", "hot_edge", 0.5f, matches(HOT)),
                selector(1.0f, "cold_base", "cold_edge", 0.5f, matches(COLD))));
        List<PredefinedCity> cities = List.of(
                predefined("hot-centre", 0, 0, 64, null),
                predefined("cold-centre", 4, 0, 64, null));
        int hotBase = 0;
        int samples = 400;
        for (long seed = 0; seed < samples; seed++) {
            PlanningContext planning = context(seed, centredPreset(), worldStyle, cities,
                    (x, z) -> x == 0 ? HOT : COLD,
                    "hot_base", "hot_edge", "cold_base", "cold_edge");
            String selected = City.getCityStyle(coord(1, 0), planning, planning.preset()).getName();
            if (selected.equals("urbextest:hot_base")) {
                hotBase++;
            } else {
                assertEquals("urbextest:cold_edge", selected,
                        "the 0.25-factor centre must contribute its resolved edge, not its base");
            }
        }

        double hotShare = (double) hotBase / samples;
        assertTrue(hotShare > 0.67 && hotShare < 0.83,
                "0.75-factor centre won " + hotShare + " of overlap draws");
    }

    @Test
    void selectorGapAtAnOverlappingCentreKeepsItsFactorWeight() {
        WorldStyle worldStyle = TestWorldStyles.minimal("overlap-gap", List.of(
                selector(1.0f, "cold", null, 0, matches(COLD))));
        List<PredefinedCity> cities = List.of(
                predefined("hot-centre", 0, 0, 64, null),
                predefined("cold-centre", 4, 0, 64, null));
        int cold = 0;
        int samples = 400;
        for (long seed = 0; seed < samples; seed++) {
            PlanningContext planning = context(seed, centredPreset(), worldStyle, cities,
                    (x, z) -> x == 0 ? HOT : COLD, "cold");
            if (City.getCityStyle(coord(1, 0), planning, planning.preset()) != null) {
                cold++;
            }
        }

        double coldShare = (double) cold / samples;
        assertTrue(coldShare > 0.17 && coldShare < 0.33,
                "the 0.25-factor matched centre won " + coldShare
                        + " of draws; the 0.75-factor selector gap must remain a null candidate");
    }

    @Test
    void explicitPredefinedStyleIsBaseOnlyAtLowFactor() {
        WorldStyle worldStyle = TestWorldStyles.minimal("explicit", List.of(
                selector(1.0f, "ordinary_base", "ordinary_edge", 0.5f, null)));
        PredefinedCity city = predefined("explicit-centre", 0, 0, 64, "urbextest:explicit");
        PlanningContext planning = context(43L, centredPreset(), worldStyle, List.of(city),
                (x, z) -> HOT, "explicit", "ordinary_base", "ordinary_edge");

        CityStyleSelection family = City.getCityStyleSelectionForCityCenter(coord(0, 0), planning);
        assertEquals(CityStyleSelection.baseOnly("urbextest:explicit"), family);
        assertEquals("urbextest:explicit", City.getCityStyle(coord(3, 0), planning, planning.preset()).getName());
    }

    @Test
    void predefinedCityWithoutStyleFollowsTheOrdinaryFamily() {
        WorldStyle worldStyle = TestWorldStyles.minimal("implicit", List.of(
                selector(1.0f, "ordinary_base", "ordinary_edge", 0.5f, null)));
        PredefinedCity city = predefined("implicit-centre", 0, 0, 64, null);
        PlanningContext planning = context(44L, centredPreset(), worldStyle, List.of(city),
                (x, z) -> HOT, "ordinary_base", "ordinary_edge");

        assertEquals("urbextest:ordinary_edge",
                City.getCityStyle(coord(3, 0), planning, planning.preset()).getName());
    }

    @Test
    void explicitPredefinedStyleOverridesPerlinFamilyAtLowFactorAcrossItsWholeRadius() {
        Preset preset = perlinPreset();
        WorldStyle worldStyle = TestWorldStyles.minimal("perlin-explicit", List.of(
                selector(1.0f, "ordinary_base", "ordinary_edge", 1.0f, null)));
        PredefinedCity city = predefined("perlin-explicit-centre", 0, 0, 192,
                "urbextest:explicit");
        PlanningContext planning = context(145L, preset, worldStyle, List.of(city),
                (x, z) -> HOT, "explicit", "ordinary_base", "ordinary_edge");

        assertEquals("urbextest:explicit",
                City.getCityStyle(coord(11, 0), planning, preset).getName(),
                "the 1/12-factor chunk is inside the declared 192-block radius and the exact "
                        + "predefined style must remain base-only there");
    }

    @Test
    void unstyledPredefinedCityOverridesPerlinRegionWithItsCentreFamilyAndEdge() {
        Preset preset = perlinPreset();
        WorldStyle worldStyle = TestWorldStyles.minimal("perlin-implicit", List.of(
                selector(1.0f, "hot_base", "hot_edge", 0.5f, matches(HOT)),
                selector(1.0f, "cold_base", "cold_edge", 0.5f, matches(COLD))));
        PredefinedCity city = predefined("perlin-implicit-centre", 5, 5, 192, null);
        PlanningContext planning = context(146L, preset, worldStyle, List.of(city),
                (x, z) -> x == 5 && z == 5 ? HOT : COLD,
                "hot_base", "hot_edge", "cold_base", "cold_edge");

        assertEquals("urbextest:hot_base",
                City.getCityStyle(coord(5, 5), planning, preset).getName(),
                "the predefined centre chooses its ordinary family from the centre biome");
        assertEquals("urbextest:hot_edge",
                City.getCityStyle(coord(16, 5), planning, preset).getName(),
                "the 1/12-factor observer keeps that centre family and resolves its edge");
    }

    @Test
    void onePerlinRegionUsesOneFamilyWhileLocalFactorsChooseBaseAndEdge() {
        Preset preset = perlinPreset();
        RegionSample sample = varyingRegion(preset, 0.25f);
        WorldStyle worldStyle = TestWorldStyles.minimal("perlin-family", List.of(
                selector(1.0f, "alpha_base", "alpha_edge", 0.25f, null),
                selector(1.0f, "beta_base", "beta_edge", 0.25f, null)));
        PlanningContext planning = context(45L, preset, worldStyle, List.of(),
                (x, z) -> HOT, "alpha_base", "alpha_edge", "beta_base", "beta_edge");

        CityStyleSelection family = City.getCityStyleSelectionForPerlinRegion(sample.low(), planning);
        assertEquals(family, City.getCityStyleSelectionForPerlinRegion(sample.high(), planning));
        assertEquals(family.styleAt(sample.lowFactor()),
                City.getCityStyle(sample.low(), planning, preset).getName());
        assertEquals(family.styleAt(sample.highFactor()),
                City.getCityStyle(sample.high(), planning, preset).getName());
        assertNotEquals(City.getCityStyle(sample.low(), planning, preset).getName(),
                City.getCityStyle(sample.high(), planning, preset).getName());
    }

    @Test
    void adjacentPerlinRegionsMayDeterministicallyChooseDifferentFamilies() {
        Preset preset = perlinPreset();
        WorldStyle worldStyle = TestWorldStyles.minimal("perlin-adjacent", List.of(
                selector(1.0f, "alpha_base", null, 0, null),
                selector(1.0f, "beta_base", null, 0, null)));
        PlanningContext planning = context(46L, preset, worldStyle, List.of(),
                (x, z) -> HOT, "alpha_base", "beta_base");

        boolean found = false;
        for (int regionX = -16; regionX < 16 && !found; regionX++) {
            ChunkCoord left = coord(regionX * 16, 0);
            ChunkCoord right = coord((regionX + 1) * 16, 0);
            CityStyleSelection a = City.getCityStyleSelectionForPerlinRegion(left, planning);
            CityStyleSelection b = City.getCityStyleSelectionForPerlinRegion(right, planning);
            found = !a.equals(b);
        }

        assertTrue(found, "the addressed region draws never produced adjacent distinct families");
    }

    @Test
    void mixedWorldStylePerlinRegionsNeverCrossBaseEdgeFamilies() {
        long seed = 148L;
        Preset preset = perlinPreset();
        WorldStyle alpha = TestWorldStyles.minimal("perlin-alpha", List.of(
                selector(1.0f, "alpha_base", "alpha_edge", 0.2f, null)));
        WorldStyle beta = TestWorldStyles.minimal("perlin-beta", List.of(
                selector(1.0f, "beta_base", "beta_edge", 0.35f, null)));
        WorldStyleField field = new WorldStyleField(seed, List.of(
                new WorldStyleField.Weighted(1.0f, alpha),
                new WorldStyleField.Weighted(1.0f, beta)));
        PlanningContext planning = context(seed, preset, field, List.of(),
                (x, z) -> HOT, "alpha_base", "alpha_edge", "beta_base", "beta_edge");
        CityRarityMap rarity = new CityRarityMap(seed, preset.cityPerlinScale(),
                preset.cityPerlinOffset(), preset.cityPerlinInnerScale());

        WorldStyle previous = null;
        boolean adjacentRegionsDiffer = false;
        boolean sawAlphaBase = false;
        boolean sawAlphaEdge = false;
        boolean sawBetaBase = false;
        boolean sawBetaEdge = false;
        for (int regionX = -12; regionX <= 12; regionX++) {
            ChunkCoord anchor = coord(regionX * 16, 0);
            // atChunk addresses the field by region coordinate (regionX, 0), so this is an
            // independent statement of which world-style family the whole region must use.
            WorldStyle regionStyle = field.atCityCenter(coord(regionX, 0));
            assertSame(regionStyle, field.atChunk(planning, anchor));
            if (previous != null && previous != regionStyle) {
                adjacentRegionsDiffer = true;
            }
            previous = regionStyle;

            for (int x = anchor.chunkX(); x < anchor.chunkX() + 16; x++) {
                for (int z = anchor.chunkZ(); z < anchor.chunkZ() + 16; z++) {
                    ChunkCoord observer = coord(x, z);
                    assertSame(regionStyle, field.atChunk(planning, observer),
                            "one 16x16 Perlin region must not change world-style family");
                    float factor = rarity.getCityFactor(x, z);
                    String expected;
                    if (regionStyle == alpha) {
                        expected = factor < 0.2f ? "urbextest:alpha_edge" : "urbextest:alpha_base";
                        sawAlphaEdge |= factor < 0.2f;
                        sawAlphaBase |= factor >= 0.2f;
                    } else {
                        expected = factor < 0.35f ? "urbextest:beta_edge" : "urbextest:beta_base";
                        sawBetaEdge |= factor < 0.35f;
                        sawBetaBase |= factor >= 0.35f;
                    }
                    CityStyle actual = City.getCityStyle(observer, planning, preset);
                    assertNotNull(actual);
                    assertEquals(expected, actual.getName(),
                            "a base must resolve only the edge declared by its own world-style family");
                }
            }
        }

        assertTrue(adjacentRegionsDiffer, "fixed adjacent regions never selected different world styles");
        assertTrue(sawAlphaBase && sawAlphaEdge && sawBetaBase && sawBetaEdge,
                "fixture must exercise both members of both disjoint families");
    }

    @Test
    void perlinFamilyUsesTheRegionAnchorBiomeRatherThanTheObserversBiome() {
        Preset preset = perlinPreset();
        WorldStyle worldStyle = TestWorldStyles.minimal("perlin-biome", List.of(
                selector(1.0f, "hot", null, 0, matches(HOT)),
                selector(1.0f, "cold", null, 0, matches(COLD))));
        PlanningContext planning = context(47L, preset, worldStyle, List.of(),
                (x, z) -> x == 0 && z == 0 ? HOT : COLD, "hot", "cold");

        assertEquals("urbextest:hot", City.getCityStyle(coord(15, 15), planning, preset).getName());
    }

    @Test
    void perlinSelectorGapStaysNullInsteadOfRerollingAtTheObserverBiome() {
        Preset preset = perlinPreset();
        WorldStyle worldStyle = TestWorldStyles.minimal("perlin-gap", List.of(
                selector(1.0f, "cold", null, 0, matches(COLD))));
        PlanningContext planning = context(147L, preset, worldStyle, List.of(),
                (x, z) -> x == 0 && z == 0 ? HOT : COLD, "cold");

        assertNull(City.getCityStyle(coord(15, 15), planning, preset),
                "the HOT region anchor selected no family; the COLD observer must not get another draw");
    }

    private static Preset centredPreset() {
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbextest", "centred"));
        draft.CITY_CHANCE = 0;
        draft.CITY_MINRADIUS = 64;
        draft.CITY_MAXRADIUS = 64;
        return draft.resolve();
    }

    private static Preset perlinPreset() {
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbextest", "perlin"));
        draft.CITY_CHANCE = -1;
        draft.CITY_PERLIN_SCALE = 3;
        draft.CITY_PERLIN_OFFSET = 0;
        draft.CITY_PERLIN_INNERSCALE = 0.5;
        return draft.resolve();
    }

    private static RegionSample varyingRegion(Preset preset, float threshold) {
        CityRarityMap map = new CityRarityMap(45L, preset.cityPerlinScale(),
                preset.cityPerlinOffset(), preset.cityPerlinInnerScale());
        for (int regionX = -12; regionX <= 12; regionX++) {
            for (int regionZ = -12; regionZ <= 12; regionZ++) {
                ChunkCoord low = null;
                ChunkCoord high = null;
                float lowFactor = Float.POSITIVE_INFINITY;
                float highFactor = Float.NEGATIVE_INFINITY;
                int startX = regionX * 16;
                int startZ = regionZ * 16;
                for (int x = startX; x < startX + 16; x++) {
                    for (int z = startZ; z < startZ + 16; z++) {
                        float factor = map.getCityFactor(x, z);
                        if (factor < lowFactor) {
                            low = coord(x, z);
                            lowFactor = factor;
                        }
                        if (factor > highFactor) {
                            high = coord(x, z);
                            highFactor = factor;
                        }
                    }
                }
                if (lowFactor < threshold && highFactor >= threshold) {
                    return new RegionSample(low, lowFactor, high, highFactor);
                }
            }
        }
        throw new AssertionError("fixture did not find a Perlin region straddling " + threshold);
    }

    private static CityStyleSelector selector(float weight, String base, String edge, float threshold,
                                               BiomeMatcher matcher) {
        return new CityStyleSelector(weight, "urbextest:" + base, matcher,
                edge == null ? Optional.empty()
                        : Optional.of(new CityStyleEdge("urbextest:" + edge, threshold)));
    }

    private static BiomeMatcher matches(Holder<Biome> wanted) {
        return new BiomeMatcher(Optional.empty(), Optional.empty(), Optional.empty()) {
            @Override
            public boolean test(Holder<Biome> biome) {
                return biome == wanted;
            }
        };
    }

    private static PlanningContext context(long seed, Preset preset, WorldStyle worldStyle,
                                           List<PredefinedCity> cities, BiomeAt biomeAt,
                                           String... cityStylePaths) {
        return context(seed, preset, WorldStyleField.single(seed, worldStyle), cities, biomeAt,
                cityStylePaths);
    }

    private static PlanningContext context(long seed, Preset preset, WorldStyleField worldStyles,
                                           List<PredefinedCity> cities, BiomeAt biomeAt,
                                           String... cityStylePaths) {
        Map<Identifier, CityStyle> styles = new LinkedHashMap<>();
        for (String path : cityStylePaths) {
            CityStyle style = TestWorldStyles.cityStyle(path);
            styles.put(style.getId(), style);
        }
        Map<Identifier, PredefinedCity> citiesById = new LinkedHashMap<>();
        for (PredefinedCity city : cities) {
            citiesById.put(city.getId(), city);
        }
        AssetIndex<PredefinedCity> cityIndex =
                new AssetIndex<>("urbex:predefinedcities", citiesById);
        AssetIndex<MultiBuilding> multiIndex = AssetIndex.empty("urbex:multibuildings");
        AssetSnapshot empty = AssetSnapshot.empty();
        AssetSnapshot assets = new AssetSnapshot(empty.palettes(), empty.conditions(),
                empty.styles(), empty.parts(), empty.buildings(), multiIndex, empty.scattered(),
                empty.worldStyles(), new AssetIndex<>("urbex:citystyles", styles), cityIndex,
                empty.stuff(), empty.stuffByTag(), PredefinedIndex.build(cityIndex, multiIndex));
        return new PlanningContext(seed, Level.OVERWORLD, preset, assets,
                worldStyles, (x, z) -> {
                    throw new AssertionError("city-style selection must not read the road field");
                },
                new DimensionCaches(seed), LevelShape.VANILLA_OVERWORLD, terrain(biomeAt));
    }

    private static PredefinedCity predefined(String path, int x, int z, int radius, String cityStyle) {
        return new PredefinedCity(Identifier.fromNamespaceAndPath("urbextest", path), List.of(
                new PredefinedCityDefinition(Optional.empty(), Optional.of("minecraft:overworld"),
                        Optional.of(x), Optional.of(z), Optional.of(radius), Optional.ofNullable(cityStyle),
                        Optional.empty(), Optional.empty())));
    }

    private static TerrainSampler terrain(BiomeAt biomeAt) {
        return new TerrainSampler() {
            @Override
            public ChunkHeightmap heightmap(ChunkCoord coord) {
                return new ChunkHeightmap(LandscapeType.DEFAULT, 64);
            }

            @Override
            public void sampleAccurateHeight(ChunkHeightmap heightmap, int chunkX, int chunkZ) {
                throw new AssertionError("city-style selection must not accurately sample terrain");
            }

            @Override
            public Holder<Biome> biome(BlockPos pos) {
                return biomeAt.at(pos.getX() >> 4, pos.getZ() >> 4);
            }

            @Override
            public RegistryAccess registryAccess() {
                return null;
            }
        };
    }

    private static Biome biome(float temperature) {
        BiomeSpecialEffects effects = new BiomeSpecialEffects(0, Optional.empty(), Optional.empty(),
                Optional.empty(), BiomeSpecialEffects.GrassColorModifier.NONE);
        return new Biome.BiomeBuilder().temperature(temperature).downfall(0.5f)
                .specialEffects(effects).mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY).build();
    }

    private static ChunkCoord coord(int x, int z) {
        return new ChunkCoord(Level.OVERWORLD, x, z);
    }

    private interface BiomeAt {
        Holder<Biome> at(int chunkX, int chunkZ);
    }

    private record RegionSample(ChunkCoord low, float lowFactor, ChunkCoord high, float highFactor) {
    }
}

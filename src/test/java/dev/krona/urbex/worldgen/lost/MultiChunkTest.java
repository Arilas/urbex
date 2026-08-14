package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.LandscapeType;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.varia.Counter;
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
import dev.krona.urbex.worldgen.lost.cityassets.PredefinedIndex;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.ObjectSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.Selectors;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MultiChunkTest {

    private static Holder<Biome> BIOME;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BiomeSpecialEffects effects = new BiomeSpecialEffects(0, Optional.empty(), Optional.empty(),
                Optional.empty(), BiomeSpecialEffects.GrassColorModifier.NONE);
        BIOME = Holder.direct(new Biome.BiomeBuilder().temperature(0.5f).downfall(0.5f)
                .specialEffects(effects).mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY).build());
    }

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

    @Test
    void allOptOutAreaReturnsWithoutTryingToSampleAMultibuildingSource() {
        PlanningContext planning = allOptOutPlanning();

        MultiChunk multiChunk = assertDoesNotThrow(
                () -> MultiChunk.getOrCreate(planning, coord(0, 0)));

        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                assertNull(multiChunk.getMultiBuilding(coord(x, z)),
                        "an all-opt-out area must contain no multibuildings");
            }
        }
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

    private static PlanningContext allOptOutPlanning() {
        long seed = 149L;
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbextest", "multi-opt-out"));
        draft.CITY_CHANCE = 0;
        draft.CITY_MINRADIUS = 16;
        draft.CITY_MAXRADIUS = 16;
        Preset preset = draft.resolve();
        CityStyle optOut = TestWorldStyles.cityStyle("opt_out");
        WorldStyle worldStyle = TestWorldStyles.minimal("multi-opt-out", List.of(
                new CityStyleSelector(1.0f, optOut.getName(), null)));
        AssetSnapshot empty = AssetSnapshot.empty();
        AssetSnapshot assets = new AssetSnapshot(empty.variants(), empty.palettes(), empty.conditions(),
                empty.styles(), empty.parts(), empty.buildings(), empty.multiBuildings(), empty.scattered(),
                empty.worldStyles(), new AssetIndex<>("urbex:citystyles", Map.of(optOut.getId(), optOut)),
                empty.predefinedCities(), empty.stuff(), empty.stuffByTag(), PredefinedIndex.empty());
        return new PlanningContext(seed, Level.OVERWORLD, preset, assets,
                WorldStyleField.single(seed, worldStyle), (x, z) -> {
                    throw new AssertionError("an all-opt-out area must return before placement reads roads");
                }, new DimensionCaches(seed), LevelShape.VANILLA_OVERWORLD, flatTerrain());
    }

    private static TerrainSampler flatTerrain() {
        return new TerrainSampler() {
            @Override
            public ChunkHeightmap heightmap(ChunkCoord coord) {
                return new ChunkHeightmap(LandscapeType.DEFAULT, 64);
            }

            @Override
            public void sampleAccurateHeight(ChunkHeightmap heightmap, int chunkX, int chunkZ) {
                throw new AssertionError("multibuilding source selection must not sample accurate terrain");
            }

            @Override
            public Holder<Biome> biome(BlockPos pos) {
                return BIOME;
            }

            @Override
            public RegistryAccess registryAccess() {
                return null;
            }
        };
    }

    private static ChunkCoord coord(int x, int z) {
        return new ChunkCoord(Level.OVERWORLD, x, z);
    }
}

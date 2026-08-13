package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.LandscapeType;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.plan.RoadField;
import dev.krona.urbex.plan.RoadType;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import dev.krona.urbex.worldgen.DimensionCaches;
import dev.krona.urbex.worldgen.LevelShape;
import dev.krona.urbex.worldgen.PlanningContext;
import dev.krona.urbex.worldgen.TerrainSampler;
import dev.krona.urbex.worldgen.TestWorldStyles;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ChunkPlan#effectiveRoadType} must return {@link RoadType#NONE} for a non-city chunk
 * without ever consulting the road field - {@code isCityRaw} gates it. This is exercised through
 * the void-chunk branch of {@code isCityRaw} (a floating-landscape preset whose heightmap reports
 * ground level 0), the cheapest way to force a non-city verdict without faking the whole
 * city-factor radius scan.
 * <p>
 * The road field is a stub that throws, which is what makes "without consulting it" an assertion
 * rather than a comment. Until #129 the whole planning context had to be a
 * {@code java.lang.reflect.Proxy} over {@code IDimensionInfo} to say that much, because the
 * interface also handed out a level and a generator and there was no smaller thing to build.
 * <p>
 * Bootstrapped because {@code ChunkCoord}/{@code Level.OVERWORLD} need the vanilla registries.
 */
class ChunkPlanTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void effectiveRoadTypeIsNoneForANonCityChunkWithoutConsultingTheRoadField() {
        Preset preset = new Preset(Identifier.fromNamespaceAndPath("urbex", "test-void"));
        preset.LANDSCAPE_TYPE = LandscapeType.FLOATING;
        ChunkCoord coord = new ChunkCoord(Level.OVERWORLD, 3, 4);

        PlanningContext planning = new PlanningContext(
                1234L, Level.OVERWORLD, preset, AssetSnapshot.empty(),
                TestWorldStyles.singleStyleField(1234L),
                refusingRoadField(),
                new DimensionCaches(1234L),
                LevelShape.VANILLA_OVERWORLD,
                groundLevelZero());

        assertEquals(RoadType.NONE, ChunkPlan.effectiveRoadType(coord, planning, preset),
                "a void (non-city) chunk must report no road, without ever reading the road field");
    }

    /** Everything below sea level, so {@code isVoidChunk} answers yes for every chunk. */
    private static TerrainSampler groundLevelZero() {
        return new TerrainSampler() {
            @Override
            public ChunkHeightmap heightmap(ChunkCoord coord) {
                return new ChunkHeightmap(LandscapeType.FLOATING, 0);
            }

            @Override
            public void sampleAccurateHeight(ChunkHeightmap heightmap, int chunkX, int chunkZ) {
                throw new AssertionError("effectiveRoadType has no reason to sample accurate heights");
            }

            @Override
            public Holder<Biome> biome(BlockPos pos) {
                throw new AssertionError("effectiveRoadType has no reason to read a biome");
            }

            @Override
            public RegistryAccess registryAccess() {
                return null;
            }
        };
    }

    /** Reaching the road field at all means the early return did not fire. */
    private static RoadField refusingRoadField() {
        return (chunkX, chunkZ) -> {
            throw new AssertionError("effectiveRoadType read the road field for a void chunk; "
                    + "isCityRaw's void check should have returned NONE first");
        };
    }
}

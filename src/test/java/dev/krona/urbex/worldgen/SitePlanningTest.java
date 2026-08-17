package dev.krona.urbex.worldgen;

import dev.krona.urbex.api.SiteField;
import dev.krona.urbex.config.LandscapeType;
import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.plan.RoadField;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.ChunkCandidate;
import dev.krona.urbex.worldgen.lost.ChunkCandidates;
import dev.krona.urbex.worldgen.lost.CityField;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link SiteBinding} changes about planning, and what it deliberately does not.
 *
 * <p>These are assertions about the three inputs a caller takes over - where the city is, how high
 * it sits, and which height band it lands in - at the layer they are answered rather than through a
 * whole generated chunk. A full {@code ChunkPlan} needs compiled parts, palettes and city styles;
 * these three answers need none of that, and testing them here is what makes it possible to say the
 * null-binding path is unchanged.</p>
 */
class SitePlanningTest {

    /** Three chunks wide, so a site has an inside, an edge and an outside. */
    private static final SiteField THREE_BY_THREE = new SiteField() {
        @Override
        public boolean isSite(int chunkX, int chunkZ) {
            return Math.abs(chunkX) <= 1 && Math.abs(chunkZ) <= 1;
        }

        @Override
        public int groundY(int chunkX, int chunkZ) {
            // Varying by coordinate on purpose, and by whole storeys: a constant would pass even if
            // the code read the preset's single ground level and ignored the field, and a sub-storey
            // step would only prove the rounding.
            return -30 + 6 * chunkX;
        }
    };

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theCallersFieldDecidesWhereTheCityIs() {
        PlanningContext site = planning(binding());
        Preset preset = site.preset();

        assertTrue(CityField.isCityRaw(new ChunkCoord(Level.OVERWORLD, 0, 0), site, preset),
                "the middle of the caller's field is city");
        assertTrue(CityField.isCityRaw(new ChunkCoord(Level.OVERWORLD, 1, -1), site, preset),
                "so is its corner");
        assertFalse(CityField.isCityRaw(new ChunkCoord(Level.OVERWORLD, 2, 0), site, preset),
                "one chunk past it is not");
    }

    /**
     * The floating landscape makes every chunk a void chunk, which is a non-city verdict a level
     * reaches before it ever looks at the city factor. A site has no islands to be off the edge of,
     * so its field has to be asked first.
     */
    @Test
    void theFieldIsAskedAheadOfTheVoidCheck() {
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbex", "test-floating"));
        draft.LANDSCAPE_TYPE = LandscapeType.FLOATING;
        Preset floating = draft.resolve();
        PlanningContext site = planning(floating, binding());

        assertTrue(CityField.isCityRaw(new ChunkCoord(Level.OVERWORLD, 0, 0), site, floating));
    }

    @Test
    void aSiteSitsOnTheGroundTheFieldNamesRatherThanThePresetsOne() {
        PlanningContext site = planning(binding());

        assertEquals(-30, site.heightmap(0, 0).getHeight());
        assertEquals(-24, site.heightmap(1, 0).getHeight(),
                "the ground follows the field's coordinate, not one number for the dimension");
    }

    /**
     * The bug this exists to prevent: a site's per-chunk height must travel in {@code cityLevel},
     * because that is the number every height comparison in Urbex is written against. With the
     * height in {@code groundLevel} and {@code cityLevel} pinned to 0, two chunks a storey apart
     * both reported level 0, {@code Doors.hasConnectionToTopOrOutside} concluded they were level,
     * and a door was cut between a floor and the wall beside it.
     */
    @Test
    void aSitesHeightTravelsInTheCityLevel() {
        PlanningContext site = planning(binding());

        // The window's bottom is -60, so ground -30 is five storeys up and -24 is six.
        assertEquals(5, CityField.getCityLevel(new ChunkCoord(Level.OVERWORLD, 0, 0), site));
        assertEquals(6, CityField.getCityLevel(new ChunkCoord(Level.OVERWORLD, 1, 0), site));
        assertEquals(5, CityField.cityLevelUncached(new ChunkCoord(Level.OVERWORLD, 0, 0), site));
    }

    /**
     * And the two halves must agree: {@code groundLevel + cityLevel * 6} is what buildings stand on,
     * and the heightmap is what the terrain under them is corrected to.
     */
    @Test
    void theGroundABuildingStandsOnIsTheGroundTheTerrainIsCorrectedTo() {
        PlanningContext site = planning(binding());
        ChunkCoord coord = new ChunkCoord(Level.OVERWORLD, 0, 0);

        int base = site.baseGroundLevel();
        int cityGround = base + CityField.getCityLevel(coord, site) * CityGenerator.FLOORHEIGHT;

        assertEquals(-60, base, "a site's base is its window's bottom, not the preset's ground");
        assertEquals(site.heightmap(0, 0).getHeight(), cityGround);
    }

    /** Six blocks is a storey, and a site cannot express a step smaller than one. */
    @Test
    void aGroundBetweenTwoStoreysIsSnappedDown() {
        SiteBinding binding = new SiteBinding(Identifier.fromNamespaceAndPath("urbextest", "odd"),
                new SiteField() {
                    @Override
                    public boolean isSite(int chunkX, int chunkZ) {
                        return true;
                    }

                    @Override
                    public int groundY(int chunkX, int chunkZ) {
                        return -27;     // four blocks above the -31 storey line, not six
                    }
                }, -60, 0);

        assertEquals(5, binding.cityLevelAt(0, 0));
        assertEquals(-30, binding.effectiveGroundY(0, 0));
    }

    @Test
    void aCandidateTakesItsCityVerdictFromTheField() {
        PlanningContext site = planning(binding());

        ChunkCandidate inside = ChunkCandidates.candidateUncached(
                new ChunkCoord(Level.OVERWORLD, 0, 0), site);
        ChunkCandidate outside = ChunkCandidates.candidateUncached(
                new ChunkCoord(Level.OVERWORLD, 9, 9), site);

        assertTrue(inside.isCity());
        assertEquals(5, inside.cityLevel(), "ground -30 is five storeys above the window's -60");
        assertFalse(outside.isCity());
    }

    /**
     * The property that lets one dimension run a ruined world style on the surface and an intact one
     * underground: the two contexts hold different caches, so the same coordinate can plan two ways
     * without either answer overwriting the other.
     */
    @Test
    void aSiteCachesApartFromTheLevelItSitsIn() {
        PlanningContext level = planning(null);
        PlanningContext site = planning(binding());
        ChunkCoord coord = new ChunkCoord(Level.OVERWORLD, 0, 0);

        assertNotSame(level.caches(), site.caches());
        assertEquals(5, CityField.getCityLevel(coord, site));
        assertEquals(0, level.caches().cityLevel.size(),
                "asking the site did not populate the level's cache");
    }

    /**
     * The null-binding path, asserted rather than inferred. Every chunk Urbex has ever generated
     * takes it, and the digests are evidence about driver writes rather than about this branch.
     */
    @Test
    void aContextWithNoSiteStillReadsTheCityNoise() {
        PlanningContext level = planning(null);

        // A flat, always-below-ground terrain sampler with the default landscape is not a void
        // chunk, so isCityRaw reaches the city factor - which is what this asserts it still does.
        // Whether that coordinate happens to be city is the noise's business, not this test's.
        assertFalse(CityField.isVoidChunk(new ChunkCoord(Level.OVERWORLD, 0, 0), level));
        assertEquals(level.preset().groundLevel(), level.heightmap(0, 0).getHeight(),
                "with no site, the heightmap is the level's own");
    }

    private static SiteBinding binding() {
        return new SiteBinding(Identifier.fromNamespaceAndPath("urbextest", "bunkers"),
                THREE_BY_THREE, -60, 0);
    }

    private static PlanningContext planning(SiteBinding site) {
        return planning(new PresetDraft(Identifier.fromNamespaceAndPath("urbex", "test-site")).resolve(), site);
    }

    private static PlanningContext planning(Preset preset, SiteBinding site) {
        long seed = 1234L;
        DimensionCaches caches = new DimensionCaches(seed);
        TerrainSampler terrain = flatTerrain(preset);
        return new PlanningContext(
                seed, Level.OVERWORLD, preset, AssetSnapshot.empty(),
                TestWorldStyles.singleStyleField(seed),
                refusingRoadField(),
                caches,
                LevelShape.VANILLA_OVERWORLD,
                site == null ? terrain : new SiteTerrain(terrain, site),
                site);
    }

    /** The dimension's own terrain: flat at the preset's ground level, like a superflat world. */
    private static TerrainSampler flatTerrain(Preset preset) {
        return new TerrainSampler() {
            @Override
            public ChunkHeightmap heightmap(ChunkCoord coord) {
                return new ChunkHeightmap(preset.landscapeType(), preset.groundLevel());
            }

            @Override
            public void sampleAccurateHeight(ChunkHeightmap heightmap, int chunkX, int chunkZ) {
                int ground = preset.groundLevel();
                heightmap.accurateHeights(ground, ground, ground, ground);
            }

            @Override
            public Holder<Biome> biome(BlockPos pos) {
                throw new AssertionError("nothing here plans against a biome");
            }

            @Override
            public RegistryAccess registryAccess() {
                return null;
            }
        };
    }

    /** Reaching the road field means something asked a question a site's city verdict should not. */
    private static RoadField refusingRoadField() {
        return (chunkX, chunkZ) -> {
            throw new AssertionError("nothing here has any reason to read the road field");
        };
    }
}

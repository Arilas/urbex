package dev.krona.urbex.worldgen.lost.cityassets;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.regassets.MultiBuildingDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PredefinedCityDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedBuilding;
import dev.krona.urbex.worldgen.lost.regassets.data.PredefinedStreet;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where predefined cities, buildings and streets land, resolved once at compile time.
 * <p>
 * This is the coverage that replaces {@code CityPredefinedCacheLatchTest}. That test existed because
 * {@code City} held these five maps as lazily-latched statics, and the thing worth asserting was a
 * negative: that a world-creation preview - which had no level and might hold an empty snapshot -
 * could not latch "ready" over an empty map and permanently stop a later real level from populating
 * it (issue #67). It needed a {@code java.lang.reflect.Proxy} standing in for the whole of
 * {@code IDimensionInfo} and reflective reads of private static booleans to say so.
 * <p>
 * With the maps compiled into the snapshot there is no latch to defeat, no level to have or not
 * have, and nothing to reset between tests - so what is asserted here is the positive: the shape of
 * the index itself (issue #129).
 */
class PredefinedIndexTest {

    @BeforeAll
    static void bootstrap() {
        // ChunkCoord's dimension keys and Level.OVERWORLD need the vanilla registries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void anEmptyIndexAnswersNullEverywhereRatherThanThrowing() {
        PredefinedIndex index = PredefinedIndex.empty();
        ChunkCoord anywhere = new ChunkCoord(Level.OVERWORLD, 3, 4);

        assertTrue(index.isEmpty());
        assertNull(index.cityAt(anywhere));
        assertNull(index.buildingAt(anywhere));
        assertNull(index.streetAt(anywhere));
        assertNull(index.buildingCovering(anywhere));
        assertNull(index.streetCovering(anywhere));
        assertFalse(index.isOccupied(anywhere));
    }

    @Test
    void aCityIsIndexedAtItsOwnChunkAndItsContentAtChunksRelativeToIt() {
        PredefinedIndex index = index(city("urbex:spawncity", 10, 20,
                List.of(new PredefinedBuilding("urbex:townhall", 1, 2, false, false)),
                List.of(new PredefinedStreet(3, 4))));

        assertNotNull(index.cityAt(coord(10, 20)), "the city sits at its own chunkx/chunkz");
        assertNull(index.cityAt(coord(11, 22)));

        assertEquals("urbex:townhall", index.buildingAt(coord(11, 22)).building(),
                "a building's chunkx/chunkz are relative to the city");
        assertNull(index.buildingAt(coord(1, 2)), "not absolute");

        assertNotNull(index.streetAt(coord(13, 24)), "and so are a street's");
        assertTrue(index.isOccupied(coord(13, 24)));
    }

    @Test
    void aSingleChunkBuildingOccupiesOnlyItsOwnChunk() {
        PredefinedIndex index = index(city("urbex:spawncity", 0, 0,
                List.of(new PredefinedBuilding("urbex:townhall", 0, 0, false, false)), List.of()));

        PredefinedIndex.BuildingAt at = index.buildingCovering(coord(0, 0));
        assertNotNull(at);
        assertEquals(0, at.offsetX());
        assertEquals(0, at.offsetZ());
        assertFalse(index.isOccupied(coord(1, 0)));
    }

    @Test
    void aMultiBuildingOccupiesEveryChunkItCoversWithThatChunksOffset() {
        PredefinedIndex index = index(
                city("urbex:spawncity", 5, 5,
                        List.of(new PredefinedBuilding("urbex:bigblock", 0, 0, true, false)), List.of()),
                multi("urbex:bigblock", 2, 3));

        assertEquals(new PredefinedIndex.BuildingAt(
                        new PredefinedBuilding("urbex:bigblock", 0, 0, true, false), 1, 2),
                index.buildingCovering(coord(6, 7)),
                "the far corner carries its offset within the multi-building");
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 3; z++) {
                assertTrue(index.isOccupied(coord(5 + x, 5 + z)), "every covered chunk is occupied");
            }
        }
        assertFalse(index.isOccupied(coord(7, 5)), "and nothing past its width");
        assertFalse(index.isOccupied(coord(5, 8)), "or past its depth");

        assertNotNull(index.buildingAt(coord(5, 5)), "the declared position is still the top-left");
        assertNull(index.buildingAt(coord(6, 6)), "which is not every chunk it covers");
    }

    /**
     * A predefined building may name a multi-building no loaded pack registers. That is a dangling
     * reference {@link AssetGraph} reports as a load problem, and the compiler's job is to reach that
     * aggregated report - so the index skips the entry rather than throwing from the middle of the
     * build. The map this replaced called {@code getOrThrow} here, on a worldgen worker thread, on
     * whichever chunk first asked.
     */
    @Test
    void aBuildingNamingAnUnknownMultiBuildingIsSkippedRatherThanThrowing() {
        PredefinedIndex index = index(city("urbex:spawncity", 0, 0,
                List.of(new PredefinedBuilding("urbex:nosuchthing", 0, 0, true, false)), List.of()));

        assertNotNull(index.buildingAt(coord(0, 0)), "it is still declared where it was declared");
        assertFalse(index.isOccupied(coord(0, 0)), "but it covers nothing");
    }

    @Test
    void twoCitiesOnOneChunkResolveToOneEntryRatherThanFailing() {
        // A pack authoring error: which of the two wins is AssetIndex iteration order, and this only
        // pins that the index is built rather than that a particular one survives.
        PredefinedIndex index = index(
                city("urbex:first", 0, 0, List.of(), List.of()),
                city("urbex:second", 0, 0, List.of(), List.of()));

        assertNotNull(index.cityAt(coord(0, 0)));
    }

    private static ChunkCoord coord(int x, int z) {
        return new ChunkCoord(Level.OVERWORLD, x, z);
    }

    private static PredefinedIndex index(Object... assets) {
        Map<Identifier, PredefinedCity> cities = new java.util.LinkedHashMap<>();
        Map<Identifier, MultiBuilding> multis = new java.util.LinkedHashMap<>();
        for (Object asset : assets) {
            if (asset instanceof PredefinedCity city) {
                cities.put(city.getId(), city);
            } else {
                MultiBuilding multi = (MultiBuilding) asset;
                multis.put(multi.getId(), multi);
            }
        }
        return PredefinedIndex.build(
                new AssetIndex<>("urbex:predefinedcities", cities),
                new AssetIndex<>("urbex:multibuildings", multis));
    }

    private static PredefinedCity city(String id, int chunkX, int chunkZ,
                                       List<PredefinedBuilding> buildings, List<PredefinedStreet> streets) {
        return new PredefinedCity(Identifier.parse(id), List.of(new PredefinedCityDefinition(
                Optional.empty(), Optional.of("minecraft:overworld"), Optional.of(chunkX),
                Optional.of(chunkZ), Optional.of(7), Optional.of("urbex:citystyle_common"),
                Optional.of(new Mergeable<>(true, buildings)),
                Optional.of(new Mergeable<>(true, streets)))));
    }

    private static MultiBuilding multi(String id, int dimX, int dimZ) {
        List<List<String>> grid = new java.util.ArrayList<>();
        for (int x = 0; x < dimX; x++) {
            List<String> row = new java.util.ArrayList<>();
            for (int z = 0; z < dimZ; z++) {
                row.add("urbex:townhall");
            }
            grid.add(List.copyOf(row));
        }
        return new MultiBuilding(Identifier.parse(id), List.of(new MultiBuildingDefinition(
                Optional.empty(), Optional.of(dimX), Optional.of(dimZ), Optional.of(List.copyOf(grid)))));
    }
}

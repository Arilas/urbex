package dev.krona.urbex.gui.preview;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.ChunkHeightmap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preview answers terrain questions instead of refusing them.
 * <p>
 * Every one of these used to go through {@code NullDimensionInfo}, whose {@code getWorld()} is
 * {@code null} - so planning code that wanted a height or a biome either branched on
 * {@code getWorld() != null} or threw. The bitmap was always there; what was missing was a way to
 * ask it that did not also promise a level (issue #129).
 */
class PreviewTerrainTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static PreviewTerrain terrain() {
        return new PreviewTerrain(new Preset(Identifier.fromNamespaceAndPath("urbex", "test")), null);
    }

    @Test
    void theBitmapIsPlainsOutsideItsBounds() {
        PreviewTerrain terrain = terrain();
        assertEquals('p', terrain.biomeChar(-1, 0));
        assertEquals('p', terrain.biomeChar(0, -1));
        assertEquals('p', terrain.biomeChar(PreviewTerrain.WIDTH, 0));
        assertEquals('p', terrain.biomeChar(0, PreviewTerrain.HEIGHT));
    }

    @Test
    void heightFollowsTheBitmapsTerrain() {
        PreviewTerrain terrain = terrain();
        // Row 0 of the bitmap opens with desert and carries a river at x=37.
        assertEquals('d', terrain.biomeChar(0, 0));
        assertEquals(65, height(terrain, 0, 0), "desert sits at the baseline");
        assertEquals('=', terrain.biomeChar(37, 0));
        assertEquals(65, height(terrain, 37, 0), "so does a river");
        // The mountain range in the lower half: peak interior, then its foothills.
        assertEquals('+', terrain.biomeChar(15, 21));
        assertEquals(125, height(terrain, 15, 21));
        assertEquals('#', terrain.biomeChar(6, 18));
        assertEquals(95, height(terrain, 6, 18));
        // And the ocean.
        assertEquals('-', terrain.biomeChar(55, 7));
        assertEquals(60, height(terrain, 55, 7));
    }

    @Test
    void accurateHeightsCollapseToTheChunksOwnHeight() {
        PreviewTerrain terrain = terrain();
        ChunkHeightmap heightmap = terrain.heightmap(new ChunkCoord(Level.OVERWORLD, 15, 21));
        terrain.sampleAccurateHeight(heightmap, 15, 21);

        assertEquals(125, heightmap.getMinHeight(), "the preview's terrain is flat within a chunk");
        assertEquals(125, heightmap.getMaxHeight());
    }

    @Test
    void aBiomeWithoutRegistriesIsNullRatherThanAThrow() {
        // The Customize screen opens with no parent screen and so no registry access at all. Every
        // caller reachable in that state is gated on registryAccess(); what must not happen is an
        // exception out of a screen the player is looking at (issue #67).
        assertNull(terrain().biome(new BlockPos(0, 64, 0)));
        assertNull(terrain().registryAccess());
    }

    @Test
    void everyRowOfTheBitmapIsTheDeclaredWidth() {
        PreviewTerrain terrain = terrain();
        for (int z = 0; z < PreviewTerrain.HEIGHT; z++) {
            // Reading the last column of every row throws if a row is short, which is the whole
            // check: a mis-copied row would silently shift the coastline.
            assertTrue(terrain.biomeChar(PreviewTerrain.WIDTH - 1, z) != 0);
        }
    }

    private static int height(PreviewTerrain terrain, int chunkX, int chunkZ) {
        return terrain.heightmap(new ChunkCoord(Level.OVERWORLD, chunkX, chunkZ)).getHeight();
    }
}

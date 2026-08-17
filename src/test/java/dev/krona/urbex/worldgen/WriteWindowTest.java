package dev.krona.urbex.worldgen;

import dev.krona.urbex.api.SiteField;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a generation's write window is derived, and what it admits.
 *
 * <p>The arithmetic is four lines and obviously right, which is exactly why it is worth asserting:
 * an off-by-one at either end is a bunker whose ceiling is one block into the caller's rock, or a
 * level whose bottom row of bedrock stops being writable.</p>
 */
class WriteWindowTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A field nothing here asks about; these tests are about the window, not the coverage. */
    private static final SiteField ANY = (x, z) -> true;

    private static SiteBinding site(int minY, int maxY) {
        return new SiteBinding(Identifier.fromNamespaceAndPath("urbextest", "site"), ANY, minY, maxY);
    }

    @Test
    void noSiteMeansTheWholeLevel() {
        WriteWindow window = WriteWindow.of(null, -64, 319);

        assertEquals(new WriteWindow(-64, 319), window);
        assertTrue(window.contains(-64));
        assertTrue(window.contains(319));
    }

    @Test
    void aSiteNarrowsTheWindowToItsOwn() {
        WriteWindow window = WriteWindow.of(site(-40, 20), -64, 319);

        assertEquals(new WriteWindow(-40, 20), window);
        assertFalse(window.contains(-41));
        assertTrue(window.contains(-40));
        assertTrue(window.contains(20));
        assertFalse(window.contains(21));
    }

    /**
     * A caller that does not know which dimension it will be asked about names a window wide enough
     * for any of them. The level is what decides, not the guess.
     */
    @Test
    void aSiteWiderThanTheLevelIsCutBackToTheLevel() {
        WriteWindow window = WriteWindow.of(site(-2048, 2048), -64, 319);

        assertEquals(new WriteWindow(-64, 319), window);
    }

    @Test
    void aSiteOverlappingOneEndKeepsTheOtherEndOfTheLevel() {
        assertEquals(new WriteWindow(-64, 20), WriteWindow.of(site(-2048, 20), -64, 319));
        assertEquals(new WriteWindow(-40, 319), WriteWindow.of(site(-40, 2048), -64, 319));
    }

    @Test
    void aDeferredWriteIsJudgedByItsAnchorsHeightAlone() {
        WriteWindow window = WriteWindow.of(site(-40, 20), -64, 319);

        assertTrue(window.contains(new BlockPos(10_000, 0, -10_000)),
                "horizontal distance is not this window's business");
        assertFalse(window.contains(new BlockPos(0, 21, 0)));
    }
}

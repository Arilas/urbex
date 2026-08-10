package dev.krona.urbex.gui.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache key and the seed-parsing rule are the two pieces of CityPreview that don't need a
 * running game (no GL, no registries) to verify, so they're covered directly rather than through a
 * full recompute.
 */
public class CityPreviewKeyTest {

    @Test
    public void needsRecomputeOnlyWhenTheKeyActuallyChanges() {
        CityPreview preview = new CityPreview(null);

        // A key seen for the first time always needs a recompute.
        assertTrue(preview.needsRecompute(7, "standard", 42L, CityPreview.Mode.MAP));
        // The exact same (profileJsonHash, worldStyle, seed, mode) tuple again: no-op.
        assertFalse(preview.needsRecompute(7, "standard", 42L, CityPreview.Mode.MAP));
    }

    @Test
    public void switchingModeAloneForcesARecompute() {
        CityPreview preview = new CityPreview(null);

        // Same (profile, worldStyle, seed) but a different mode is a different picture, so each of the
        // three modes must be a distinct cache key that triggers its own recompute - and repeats of
        // any one of them stay no-ops.
        assertTrue(preview.needsRecompute(7, "standard", 42L, CityPreview.Mode.MAP));
        assertTrue(preview.needsRecompute(7, "standard", 42L, CityPreview.Mode.CITY));
        assertTrue(preview.needsRecompute(7, "standard", 42L, CityPreview.Mode.TRANSPORT));
        assertFalse(preview.needsRecompute(7, "standard", 42L, CityPreview.Mode.TRANSPORT));
        // Going back to a mode already keyed elsewhere still recomputes (the key changed from the
        // last call), confirming mode is a full participant in the key rather than a sticky flag.
        assertTrue(preview.needsRecompute(7, "standard", 42L, CityPreview.Mode.MAP));
    }

    @Test
    public void seedFromUiParsesNumericTextAsTheLongItself() {
        // Vanilla's rule (WorldOptions.parseSeed): a numeric string is Long.parseLong'd, not hashed -
        // "1337" must come back as the number 1337, not "1337".hashCode().
        assertEquals(1337L, CityPreview.seedFromUi("1337", 999L));
    }

    // ---- fitPreview (BUG 1: the Cities tab preview was stretched into a tall smear) ----

    @Test
    public void fitPreviewKeepsTheSourceAspectRatioWhenWidthIsTheBindingBound() {
        // A short, wide box: width binds, so height derives from the 62:58 source ratio - never the
        // box's own height. 130 wide -> round(130 * 58 / 62) = 122 tall.
        int[] fit = CityPreview.fitPreview(130, 900, 62, 58);
        assertEquals(130, fit[0]);
        assertEquals(122, fit[1]);
        assertAspectPreserved(fit, 62, 58);
    }

    @Test
    public void fitPreviewFallsBackToHeightWhenThatIsTheBindingBound() {
        // A tall, narrow box: fitting to width would overflow the height, so height binds and width
        // shrinks to match. Result must stay within both bounds and keep the aspect ratio.
        int[] fit = CityPreview.fitPreview(200, 50, 62, 58);
        assertTrue(fit[0] <= 200, "width within bound");
        assertTrue(fit[1] <= 50, "height within bound");
        assertEquals(50, fit[1]);
        assertAspectPreserved(fit, 62, 58);
    }

    @Test
    public void fitPreviewNeverExceedsEitherBound() {
        int[] fit = CityPreview.fitPreview(130, 121, 62, 58);
        assertTrue(fit[0] <= 130 && fit[1] <= 121, "within both bounds: " + fit[0] + "x" + fit[1]);
        assertAspectPreserved(fit, 62, 58);
    }

    @Test
    public void fitPreviewReturnsZeroForNonPositiveInput() {
        assertArrayEquals(new int[]{0, 0}, CityPreview.fitPreview(0, 100, 62, 58));
        assertArrayEquals(new int[]{0, 0}, CityPreview.fitPreview(100, 0, 62, 58));
        assertArrayEquals(new int[]{0, 0}, CityPreview.fitPreview(100, 100, 0, 58));
        assertArrayEquals(new int[]{0, 0}, CityPreview.fitPreview(100, 100, 62, 0));
    }

    /** The fitted rectangle matches the source ratio to within a pixel of integer rounding. */
    private static void assertAspectPreserved(int[] fit, int srcW, int srcH) {
        double sourceRatio = (double) srcW / srcH;
        double fitRatio = (double) fit[0] / fit[1];
        assertTrue(Math.abs(sourceRatio - fitRatio) < 0.05,
                "aspect ratio preserved: source " + sourceRatio + " vs fit " + fitRatio);
    }
}

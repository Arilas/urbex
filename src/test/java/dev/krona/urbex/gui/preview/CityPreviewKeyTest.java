package dev.krona.urbex.gui.preview;

import org.junit.jupiter.api.Test;

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
        assertTrue(preview.needsRecompute(7, "standard", 42L));
        // The exact same (profileJsonHash, worldStyle, seed) triple again: no-op.
        assertFalse(preview.needsRecompute(7, "standard", 42L));
    }

    @Test
    public void seedFromUiParsesNumericTextAsTheLongItself() {
        // Vanilla's rule (WorldOptions.parseSeed): a numeric string is Long.parseLong'd, not hashed -
        // "1337" must come back as the number 1337, not "1337".hashCode().
        assertEquals(1337L, CityPreview.seedFromUi("1337", 999L));
    }
}

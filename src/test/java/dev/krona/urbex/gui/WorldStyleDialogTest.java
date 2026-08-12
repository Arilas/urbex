package dev.krona.urbex.gui;

import dev.krona.urbex.setup.WorldStyleMix;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headless, pure core of the world-style picker. {@link WorldStyleDialog#preselectIndex} names
 * the row to pre-select (and highlight) when the plain modal opens; {@link WorldStyleDialog#rowsFor},
 * {@link WorldStyleDialog#normalize}, {@link WorldStyleDialog#toMix} and
 * {@link WorldStyleDialog#canDisable} are the mix editor's whole model. Everything else in the dialog
 * is GL widget code, exercised only manually.
 */
class WorldStyleDialogTest {

    private static final List<String> CHOICES = List.of("standard", "floating", "lcmt");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static WorldStyleDialog.MixRow row(String style, boolean enabled, float weight) {
        return new WorldStyleDialog.MixRow(style, enabled, weight);
    }

    @Test
    void percentagesAreNormalizedOverTheEnabledRowsOnly() {
        List<WorldStyleDialog.MixRow> rows = List.of(
                row("urbex:standard", true, 0.1f),
                row("urbexmt:moderntweaks", true, 0.9f),
                row("urbex:cavern", false, 1.0f));
        // The player types the balance they mean; the dialog does the dividing. -1 renders as a dash.
        assertEquals(List.of(10, 90, -1), WorldStyleDialog.normalize(rows));
    }

    @Test
    void weightsThatDoNotSumToOneStillReadAsPercentages() {
        assertEquals(List.of(25, 75), WorldStyleDialog.normalize(List.of(
                row("urbex:standard", true, 1.0f),
                row("urbexmt:moderntweaks", true, 3.0f))));
    }

    @Test
    void onlyEnabledRowsReachTheMix() {
        List<WorldStyleDialog.MixRow> rows = List.of(
                row("urbex:standard", true, 0.1f),
                row("urbexmt:moderntweaks", true, 0.9f),
                row("urbex:cavern", false, 1.0f));
        assertEquals(WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9"),
                WorldStyleDialog.toMix(rows));
    }

    @Test
    void theLastEnabledRowCannotBeDisabled() {
        // No sequence of clicks can produce an empty mix, so Done never has to refuse one.
        assertFalse(WorldStyleDialog.canDisable(List.of(
                row("urbex:standard", true, 1.0f),
                row("urbexmt:moderntweaks", false, 1.0f)), 0));
        assertTrue(WorldStyleDialog.canDisable(List.of(
                row("urbex:standard", true, 1.0f),
                row("urbexmt:moderntweaks", true, 1.0f)), 0));
        // A row that is already off is always free to toggle.
        assertTrue(WorldStyleDialog.canDisable(List.of(
                row("urbex:standard", true, 1.0f),
                row("urbexmt:moderntweaks", false, 1.0f)), 1));
    }

    @Test
    void rowsOpenShowingWhatCurrentlyGenerates() {
        List<WorldStyleDialog.MixRow> rows = WorldStyleDialog.rowsFor(
                List.of("urbex:standard", "urbexmt:moderntweaks", "urbex:cavern"),
                WorldStyleMix.parse("urbex:standard*0.1+urbexmt:moderntweaks*0.9"));
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).enabled());
        assertEquals(0.1f, rows.get(0).weight());
        assertTrue(rows.get(1).enabled());
        assertEquals(0.9f, rows.get(1).weight());
        // A registered style the mix does not name opens disabled, at a neutral weight.
        assertFalse(rows.get(2).enabled());
        assertEquals(1.0f, rows.get(2).weight());
    }

    @Test
    void aSingleStyleSelectionOpensAsOneEnabledRow() {
        List<WorldStyleDialog.MixRow> rows = WorldStyleDialog.rowsFor(
                List.of("urbex:standard", "urbexmt:moderntweaks"),
                WorldStyleMix.parse("urbexmt:moderntweaks"));
        assertFalse(rows.get(0).enabled());
        assertTrue(rows.get(1).enabled());
        assertEquals(List.of(-1, 100), WorldStyleDialog.normalize(rows));
    }

    @Test
    void theCurrentStyleSelectsItsOwnRow() {
        assertEquals(0, WorldStyleDialog.preselectIndex(CHOICES, "standard"));
        assertEquals(1, WorldStyleDialog.preselectIndex(CHOICES, "floating"));
        assertEquals(2, WorldStyleDialog.preselectIndex(CHOICES, "lcmt"));
    }

    @Test
    void aStyleNotAmongTheChoicesSelectsNothing() {
        // The disabled row has no style ("" is never a choice), and a stale style could survive a
        // registry change: either way the list opens with no pre-selection rather than a wrong one.
        assertEquals(-1, WorldStyleDialog.preselectIndex(CHOICES, ""));
        assertEquals(-1, WorldStyleDialog.preselectIndex(CHOICES, "gone"));
    }

    @Test
    void aNullCurrentSelectsNothing() {
        assertEquals(-1, WorldStyleDialog.preselectIndex(CHOICES, null));
    }
}

package dev.krona.urbex.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link WorldStyleDialog#preselectIndex} is the headless, pure core of the world-style picker: given
 * the ordered choices the tab injects and the currently effective style, it names the row to
 * pre-select (and highlight) when the modal opens. Everything else in the dialog is GL widget code,
 * exercised only manually.
 */
class WorldStyleDialogTest {

    private static final List<String> CHOICES = List.of("standard", "standard_everywhere", "lcmt");

    @Test
    void theCurrentStyleSelectsItsOwnRow() {
        assertEquals(0, WorldStyleDialog.preselectIndex(CHOICES, "standard"));
        assertEquals(1, WorldStyleDialog.preselectIndex(CHOICES, "standard_everywhere"));
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

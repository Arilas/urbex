package dev.krona.urbex.gui;

import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.setup.Config;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PresetSelection is pure state (no widgets), so it's exercised directly against a fresh instance
 * per test. STANDARD_PROFILES and Config are shared static state, so each test seeds/resets them
 * explicitly rather than relying on production data or leftover state from other tests.
 */
class PresetSelectionTest {

    @BeforeAll
    static void bootstrap() {
        // publish() touches CityFeature, which extends the vanilla Feature<> registry base class -
        // its class init needs the registries bootstrapped, same as other registry-touching tests.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void seedProfiles() {
        ProfileSetup.STANDARD_PROFILES.clear();
        ProfileSetup.STANDARD_PROFILES.put("zeta", new UrbexProfile("zeta", true));
        ProfileSetup.STANDARD_PROFILES.put("default", new UrbexProfile("default", true));
        ProfileSetup.STANDARD_PROFILES.put("alpha", new UrbexProfile("alpha", true));
        ProfileSetup.STANDARD_PROFILES.put("cavern", new UrbexProfile("cavern", true));
        ProfileSetup.STANDARD_PROFILES.put("hidden", new UrbexProfile("hidden", false));
        ProfileSetup.STANDARD_PROFILES.put("rare", new UrbexProfile("rare", true));
        Config.reset();
    }

    @AfterEach
    void clearProfiles() {
        ProfileSetup.STANDARD_PROFILES.clear();
        Config.reset();
    }

    private static String keyOf(Component component) {
        assertTrue(component.getContents() instanceof TranslatableContents,
                "expected a translatable component, got: " + component);
        return ((TranslatableContents) component.getContents()).getKey();
    }

    @Test
    void entriesAreOrderedDisabledFirstThenDefaultThenAlphabeticalPublics() {
        PresetSelection selection = new PresetSelection();

        List<String> ids = selection.entries().stream().map(PresetSelection.Entry::id).toList();

        assertEquals(List.of("disabled", "default", "alpha", "cavern", "rare", "zeta"), ids);
    }

    @Test
    void customEntrySortsLast() {
        PresetSelection selection = new PresetSelection();
        selection.applyCustomized(new UrbexProfile("customized", false), "default");

        List<String> ids = selection.entries().stream().map(PresetSelection.Entry::id).toList();

        assertEquals(List.of("disabled", "default", "alpha", "cavern", "rare", "zeta", "customized"), ids);
    }

    @Test
    void selectUnknownIdIsNoOp() {
        PresetSelection selection = new PresetSelection();
        selection.select("cavern");

        selection.select("nope");

        assertEquals("cavern", selection.selected().id());
    }

    @Test
    void restoreWithUnknownProfileLeavesSelectionUntouched() {
        PresetSelection selection = new PresetSelection();
        selection.select("cavern");

        selection.restore("totally-bogus-profile", "");

        assertEquals("cavern", selection.selected().id());
    }

    @Test
    void restoreWithUnknownProfileLeavesConfigUnpublished() {
        PresetSelection selection = new PresetSelection();

        selection.restore("totally-bogus-profile", "");

        assertNull(Config.profileFromClient);
        assertNull(Config.jsonFromClient);
    }

    @Test
    void restoreWithJsonProducesACustomEntryWithAGenericLabelNotTiedToTheBasedOnText() {
        PresetSelection selection = new PresetSelection();

        selection.restore("customized", "{\"citychance\":0.9}");

        PresetSelection.Entry entry = selection.selected();
        assertTrue(entry.custom());
        assertEquals("customized", entry.id());
        assertEquals("customized", entry.basedOn());
        // The label must not just dump the internal basedOn marker ("customized") back at the
        // player - it's a generic "Custom" label, independent of basedOn's value.
        assertEquals("urbex.preset.custom", keyOf(entry.name()));
    }

    @Test
    void restoreWithoutJsonSelectsTheNamedBuiltInProfile() {
        PresetSelection selection = new PresetSelection();

        selection.restore("cavern", "");

        assertEquals("cavern", selection.selected().id());
        assertTrue(selection.selected().profile().isPresent());
    }

    /**
     * Issue #85 regression: the Re-Create flow must reach the server even if the player never
     * opens the Cities tab, so restore() has to publish immediately - not just update the
     * in-memory selection for a screen that might never open.
     */
    @Test
    void restoreOfABuiltInProfilePublishesImmediately() {
        PresetSelection selection = new PresetSelection();

        selection.restore("rare", "");

        assertEquals("rare", Config.profileFromClient);
    }

    @Test
    void restoreWithJsonPublishesImmediately() {
        PresetSelection selection = new PresetSelection();

        selection.restore("customized", "{\"citychance\":0.9}");

        assertEquals("customized", Config.profileFromClient);
        assertTrue(Config.jsonFromClient != null && !Config.jsonFromClient.isEmpty());
    }

    @Test
    void disabledEntryPublishesNullProfileNameMatchingTheOldContract() {
        PresetSelection selection = new PresetSelection();
        selection.select("cavern");
        selection.select("disabled");

        selection.publish();

        assertNull(Config.profileFromClient);
    }
}

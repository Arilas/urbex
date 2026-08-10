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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        ProfileSetup.USER_PROFILES.clear();
        ProfileSetup.PROFILE_BASED_ON.clear();
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
        ProfileSetup.USER_PROFILES.clear();
        ProfileSetup.PROFILE_BASED_ON.clear();
        Config.reset();
    }

    private static String keyOf(Component component) {
        assertTrue(component.getContents() instanceof TranslatableContents,
                "expected a translatable component, got: " + component);
        return ((TranslatableContents) component.getContents()).getKey();
    }

    /**
     * Static-init-order regression: the {@code CLIENT} singleton is constructed during class init, so
     * its {@code selected} field initializer reads {@code DISABLED_ENTRY} at that moment. If CLIENT is
     * declared before DISABLED_ENTRY, that read sees {@code null} and the singleton's selection is null
     * forever - a real crash on the first Create-New-World open. This must touch the actual singleton,
     * not a fresh {@code new PresetSelection()} (which captures a fully-initialized DISABLED_ENTRY and
     * so can never reproduce the bug).
     */
    @Test
    void theClientSingletonHasANonNullDisabledSelectionAfterClassInit() {
        assertNotNull(PresetSelection.CLIENT.selected(),
                "the CLIENT singleton must not construct with a null selection");
        assertEquals(PresetSelection.DISABLED_ID, PresetSelection.CLIENT.selected().id());
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
    void userSavedProfilesAppearAsCustomEntriesAfterPublicsWithBasedOnProvenance() {
        // A hand-saved custom (via the Save-as editor) plus its provenance.
        ProfileSetup.STANDARD_PROFILES.put("my_wasteland", new UrbexProfile("my_wasteland", false));
        ProfileSetup.USER_PROFILES.add("my_wasteland");
        ProfileSetup.PROFILE_BASED_ON.put("my_wasteland", "wasteland");

        PresetSelection selection = new PresetSelection();
        List<PresetSelection.Entry> entries = selection.entries();
        List<String> ids = entries.stream().map(PresetSelection.Entry::id).toList();

        // Publics first (disabled, default, then alphabetical), the user custom last. The "hidden"
        // internal non-public built-in is NOT a user profile, so it stays hidden.
        assertEquals(List.of("disabled", "default", "alpha", "cavern", "rare", "zeta", "my_wasteland"), ids);
        assertFalse(ids.contains("hidden"));

        PresetSelection.Entry saved = entries.get(entries.size() - 1);
        assertTrue(saved.custom());
        assertEquals("wasteland", saved.basedOn());
        assertTrue(saved.profile().isPresent());
    }

    @Test
    void internalNonPublicBuiltInIsNotListedEvenWhenNotAUserProfile() {
        // "hidden" is registered non-public but never added to USER_PROFILES: it must not appear.
        PresetSelection selection = new PresetSelection();

        List<String> ids = selection.entries().stream().map(PresetSelection.Entry::id).toList();

        assertFalse(ids.contains("hidden"));
    }

    @Test
    void selectingASavedUserProfilePublishesUnderCustomizedCarryingItsJson() {
        UrbexProfile saved = new UrbexProfile("my_wasteland", false);
        saved.CITY_CHANCE = 0.5;
        ProfileSetup.STANDARD_PROFILES.put("my_wasteland", saved);
        ProfileSetup.USER_PROFILES.add("my_wasteland");
        ProfileSetup.PROFILE_BASED_ON.put("my_wasteland", "wasteland");

        PresetSelection selection = new PresetSelection();
        selection.select("my_wasteland");
        selection.publish();

        // A custom (user-saved) selection reaches the server as the reconstructable "customized" name
        // plus its full JSON, not by a name the server might not have on disk.
        assertEquals("customized", Config.profileFromClient);
        assertTrue(Config.jsonFromClient != null && !Config.jsonFromClient.isEmpty(),
                "the saved profile's JSON must travel so the server can rebuild it");
    }

    @Test
    void aUserProfileNamedLikeTheCustomMarkerIsNotDoubleListed() {
        // The transient CUSTOM_ID row is separate from saved files; a file literally named "customized"
        // must not produce a second row alongside the transient one.
        ProfileSetup.STANDARD_PROFILES.put("customized", new UrbexProfile("customized", false));
        ProfileSetup.USER_PROFILES.add("customized");

        PresetSelection selection = new PresetSelection();
        selection.applyCustomized(new UrbexProfile("customized", false), "default");

        long customizedRows = selection.entries().stream()
                .filter(e -> "customized".equals(e.id()))
                .count();
        assertEquals(1, customizedRows);
    }

    @Test
    void aUserProfileNamedLikeTheDisabledMarkerDoesNotDoubleListTheDisabledRow() {
        // A stray on-disk "disabled.json" that slipped past Save-as validation must not add a second
        // Disabled row alongside the built-in one.
        ProfileSetup.STANDARD_PROFILES.put("disabled", new UrbexProfile("disabled", false));
        ProfileSetup.USER_PROFILES.add("disabled");

        PresetSelection selection = new PresetSelection();

        long disabledRows = selection.entries().stream()
                .filter(e -> "disabled".equals(e.id()))
                .count();
        assertEquals(1, disabledRows);
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

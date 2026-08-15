package dev.krona.urbex.gui;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.config.PresetDraft;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.WorldSelectionHandoff;
import dev.krona.urbex.setup.WorldStyleMix;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PresetSelection is pure state (no widgets), so it's exercised directly against a fresh instance
 * per test. Entries come only from injection ({@link PresetSelection#setAvailablePresets}) since
 * Task 4 - there is no more static profile table to seed, so each test builds its own small entry
 * list, mirroring what {@code CitiesTab} would build from {@code Presets.listBrowsable}.
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
    void resetConfig() {
        Config.reset();
    }

    @AfterEach
    void clearConfig() {
        Config.reset();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("urbex", path);
    }

    private static Preset preset(String path) {
        return new Preset(id(path));
    }

    /** What the Customize editor hands back: a draft of the preset it was opened on. */
    private static PresetDraft draft(String path) {
        return new PresetDraft(id(path));
    }

    private static PresetSelection.Entry entry(String path) {
        return new PresetSelection.Entry(id(path), Component.literal(path), preset(path));
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
    void entriesStartWithOnlyTheDisabledRowUntilPresetsAreInjected() {
        PresetSelection selection = new PresetSelection();

        List<Identifier> ids = selection.entries().stream().map(PresetSelection.Entry::id).toList();

        assertEquals(List.of(PresetSelection.DISABLED_ID), ids);
    }

    @Test
    void injectedEntriesFollowTheDisabledRowInWhateverOrderTheyAreGiven() {
        // CitiesTab is responsible for the default-first-then-alphabetical ordering (via
        // Presets.listBrowsable); PresetSelection itself just prepends Disabled and appends them.
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default"), entry("alpha"), entry("cavern")));

        List<Identifier> ids = selection.entries().stream().map(PresetSelection.Entry::id).toList();

        assertEquals(List.of(PresetSelection.DISABLED_ID, id("default"), id("alpha"), id("cavern")), ids);
    }

    /**
     * Issue #201. Appended last it was row 14 of 14 with the shipped presets, so on a list too short
     * to show them all it sat off-screen - and pressing Done in the editor looked like it had done
     * nothing. Beside its base it lands where the player was already looking.
     */
    @Test
    void customizedEntrySitsDirectlyAfterThePresetItWasCustomizedFrom() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default"), entry("rarecities"), entry("safe")));
        selection.applyCustomized(draft("rarecities"));

        List<Identifier> ids = selection.entries().stream().map(PresetSelection.Entry::id).toList();

        assertEquals(List.of(PresetSelection.DISABLED_ID, id("default"), id("rarecities"),
                PresetSelection.CUSTOMIZED_ID, id("safe")), ids);
    }

    /** With nothing to sit beside - the pack providing the base was turned off - it still has to be
     *  reachable, so it goes last. */
    @Test
    void aCustomizedEntryWhoseBaseIsGoneStillAppears() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default")));
        selection.applyCustomized(draft("frompack"));

        List<Identifier> ids = selection.entries().stream().map(PresetSelection.Entry::id).toList();

        assertEquals(List.of(PresetSelection.DISABLED_ID, id("default"), PresetSelection.CUSTOMIZED_ID), ids);
    }

    @Test
    void customizedEntryIsNamedForItsBaseAndMarkedAsModified() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("rarecities")));
        selection.applyCustomized(draft("rarecities"));

        // "Customized: %s *" - the base's name plus the same marker the editor puts in its title.
        assertEquals("urbex.preset.custom.of", keyOf(selection.selected().name()));
        assertEquals("rarecities", selection.customizedBaseName().getString());
    }

    /** With no base to name it after, it falls back to the generic label rather than to a blank. */
    @Test
    void customizedEntryWithNoInjectedBaseKeepsTheGenericName() {
        PresetSelection selection = new PresetSelection();
        selection.applyCustomized(draft("default"));

        assertEquals("urbex.preset.custom", keyOf(selection.selected().name()));
        assertNull(selection.customizedBaseName());
    }

    // ---- revert (issue #201) --------------------------------------------------------------------

    @Test
    void revertDropsTheCustomizationAndGoesBackToItsBase() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default"), entry("rarecities")));
        PresetDraft copy = draft("rarecities");
        copy.CITY_CHANCE = 0.5;
        selection.applyCustomized(copy);

        selection.revertCustomization();

        assertEquals(id("rarecities"), selection.selected().id());
        assertFalse(selection.hasCustomization());
        assertEquals(List.of(PresetSelection.DISABLED_ID, id("default"), id("rarecities")),
                selection.entries().stream().map(PresetSelection.Entry::id).toList());
    }

    @Test
    void revertWithNothingCustomizedIsANoOp() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("cavern")));
        selection.select(id("cavern"));

        selection.revertCustomization();

        assertEquals(id("cavern"), selection.selected().id());
    }

    /** Nothing to go back to - the base is no longer injected - so it lands on Disabled rather than
     *  on a row that does not exist. */
    @Test
    void revertFallsBackToDisabledWhenTheBaseIsNoLongerAvailable() {
        PresetSelection selection = new PresetSelection();
        selection.applyCustomized(draft("frompack"));

        selection.revertCustomization();

        assertEquals(PresetSelection.DISABLED_ID, selection.selected().id());
    }

    @Test
    void selectUnknownIdIsNoOp() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("cavern")));
        selection.select(id("cavern"));

        selection.select(id("nope"));

        assertEquals(id("cavern"), selection.selected().id());
    }

    @Test
    void theDisabledEntryPublishesNothingAtAll() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("cavern")));
        selection.select(id("cavern"));
        selection.select(PresetSelection.DISABLED_ID);

        selection.publish();

        // One absence, not three nulls that a reader has to agree about. Publishing Disabled after
        // publishing something else has to clear it, which is why publish() discards rather than
        // returning early.
        assertNull(WorldSelectionHandoff.pending());
        assertFalse(WorldSelectionHandoff.isPending());
    }

    @Test
    void plainPresetPublishesItsOwnIdWithNoOverridesAndTheDefaultWorldStyle() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("cavern")));
        selection.select(id("cavern"));

        selection.publish();

        assertEquals(id("cavern"), WorldSelectionHandoff.pending().preset());
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, WorldSelectionHandoff.pending().worldStyles());
        assertTrue(WorldSelectionHandoff.pending().patch().isEmpty());
    }

    @Test
    void plainPresetPublishesTheChosenWorldStyleOverride() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("cavern")));
        selection.select(id("cavern"));
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbex:lcmt"));
        selection.setWorldStyles(WorldStyleMix.parse("urbex:lcmt"));

        selection.publish();

        assertEquals(id("cavern"), WorldSelectionHandoff.pending().preset());
        assertEquals(WorldStyleMix.of(id("lcmt")), WorldSelectionHandoff.pending().worldStyles());
        assertTrue(WorldSelectionHandoff.pending().patch().isEmpty());
    }

    @Test
    void customizedEntryPublishesTheBasePresetIdPlusParseableOverridesJson() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default")));
        selection.select(id("default"));

        PresetDraft copy = draft("default");
        copy.CITY_CHANCE = 0.5;
        selection.applyCustomized(copy);
        selection.publish();

        assertEquals(id("default"), WorldSelectionHandoff.pending().preset(), "the base preset id, not the sentinel");
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, WorldSelectionHandoff.pending().worldStyles());
        assertNotNull(WorldSelectionHandoff.pending().patch().orElse(null));

        // The published JSON is a real, parseable PresetDefinition overlay (not a stringified profile).
        com.mojang.serialization.DataResult<dev.krona.urbex.worldgen.lost.regassets.PresetDefinition> parsed =
                dev.krona.urbex.worldgen.lost.regassets.PresetDefinition.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                        com.google.gson.JsonParser.parseString(WorldSelectionHandoff.pending().patch().orElse(null)));
        assertTrue(parsed.isSuccess(), "overridesFromClient must decode as a PresetDefinition: " + parsed);
        assertEquals(0.5, parsed.getOrThrow().cities().orElseThrow().cityChance().orElseThrow(), 1e-9);
    }

    @Test
    void customizedEntryPublishesTheChosenWorldStyleOverride() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default")));
        selection.select(id("default"));
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbex:lcmt"));
        selection.setWorldStyles(WorldStyleMix.parse("urbex:lcmt"));
        selection.applyCustomized(draft("default"));

        selection.publish();

        assertEquals(WorldStyleMix.of(id("lcmt")), WorldSelectionHandoff.pending().worldStyles());
    }

    // ---- restore() (Re-Create flow, issue #85) --------------------------------------------------

    @Test
    void restoreWithEmptyPresetIsANoOp() {
        PresetSelection selection = new PresetSelection();

        selection.restore("", "", "");

        assertNull(WorldSelectionHandoff.pending());
    }

    @Test
    void restoreWithAMalformedPresetIdIsANoOp() {
        PresetSelection selection = new PresetSelection();

        selection.restore("not a valid identifier!!", "", "");

        assertNull(WorldSelectionHandoff.pending());
    }

    @Test
    void restoreOfAPlainPresetPublishesImmediatelyEvenWithNoEntriesInjectedYet() {
        // Issue #85: the restore has to reach the server even if the Cities tab (and therefore
        // setAvailablePresets) never runs before the world is created.
        PresetSelection selection = new PresetSelection();

        selection.restore("urbex:rare", "", "");

        assertEquals(id("rare"), WorldSelectionHandoff.pending().preset());
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, WorldSelectionHandoff.pending().worldStyles());
        assertTrue(WorldSelectionHandoff.pending().patch().isEmpty());
    }

    @Test
    void restoreWithASavedWorldStylePublishesItVerbatim() {
        PresetSelection selection = new PresetSelection();

        selection.restore("urbex:rare", "urbex:lcmt", "");

        assertEquals(WorldStyleMix.of(id("lcmt")), WorldSelectionHandoff.pending().worldStyles());
    }

    @Test
    void restoreWithJsonPublishesTheOverridesVerbatim() {
        PresetSelection selection = new PresetSelection();

        selection.restore("urbex:default", "", "{\"cities\":{\"cityChance\":0.9}}");

        assertEquals(id("default"), WorldSelectionHandoff.pending().preset());
        assertEquals("{\"cities\":{\"cityChance\":0.9}}", WorldSelectionHandoff.pending().patch().orElse(null));
    }

    /**
     * Regression: an unparseable overridesJson must never reach {@code WorldSelectionHandoff.pending().patch().orElse(null)} -
     * that field is read when a level loads and its runtime is built
     * ({@code DimensionRuntime.create}'s {@code PresetDefinition.parseOverrides(...)}), so a
     * corrupted/hand-edited save's garbage JSON must be rejected before publish, not after.
     */
    @Test
    void restoreWithMalformedOverridesJsonPublishesThePlainPresetInstead() {
        PresetSelection selection = new PresetSelection();

        selection.restore("urbex:default", "", "{not valid json at all");

        assertEquals(id("default"), WorldSelectionHandoff.pending().preset(), "the preset id itself is still restored");
        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, WorldSelectionHandoff.pending().worldStyles());
        assertTrue(WorldSelectionHandoff.pending().patch().isEmpty(), "malformed overrides must never reach Config");
    }

    @Test
    void restoreReconcilesTheVisualSelectionOnceEntriesAreInjected() {
        PresetSelection selection = new PresetSelection();
        selection.restore("urbex:cavern", "", "");
        // No entries were available yet, so the visual selection could not be resolved:
        assertEquals(PresetSelection.DISABLED_ID, selection.selected().id());

        // The tab now injects the real, registry-backed entries (as it would once CreateWorldScreen
        // actually builds the tab) - the pending restore must be picked up at that point.
        selection.setAvailablePresets(List.of(entry("cavern"), entry("default")));

        assertEquals(id("cavern"), selection.selected().id());
        assertNotNull(selection.selected().preset());
    }

    @Test
    void restoreOfACustomizedPresetRebuildsItOnceTheBaseEntryExists() {
        PresetSelection selection = new PresetSelection();
        selection.restore("urbex:default", "", "{\"cities\":{\"cityChance\":0.9}}");

        selection.setAvailablePresets(List.of(entry("default")));

        assertEquals(PresetSelection.CUSTOMIZED_ID, selection.selected().id());
        assertEquals(0.9, selection.selected().preset().cityChance(), 1e-9);
    }

    // ---- the unlisted row (issue #202) ----------------------------------------------------------

    /**
     * A saved preset the enabled datapacks do not offer gets a row of its own. Before this the tab
     * showed Disabled while {@code restore} had already published the saved id, so the screen said
     * "no cities" about a world that was about to have them.
     */
    @Test
    void aRestoredPresetTheDatapacksDoNotOfferGetsItsOwnSelectedRow() {
        PresetSelection selection = new PresetSelection();
        selection.restore("urbexpack:ruins", "", "");

        selection.setAvailablePresets(List.of(entry("default")));

        assertEquals(Identifier.parse("urbexpack:ruins"), selection.selected().id());
        assertNull(selection.selected().preset(), "nothing here can resolve it");
        assertEquals("urbex.preset.unlisted", keyOf(selection.selected().name()));
        assertEquals(List.of(PresetSelection.DISABLED_ID, Identifier.parse("urbexpack:ruins"), id("default")),
                selection.entries().stream().map(PresetSelection.Entry::id).toList());
    }

    /**
     * The unlisted row carries a real selection even though it carries no {@code Preset}, so
     * re-selecting it must republish what {@code restore} published rather than discarding it the way
     * Disabled does.
     */
    @Test
    void theUnlistedRowRepublishesTheSavedSelectionRatherThanTurningItOff() {
        PresetSelection selection = new PresetSelection();
        selection.restore("urbexpack:ruins", "", "{\"cities\":{\"cityChance\":0.9}}");
        selection.setAvailablePresets(List.of(entry("default")));

        selection.select(id("default"));
        selection.publish();
        assertEquals(id("default"), WorldSelectionHandoff.pending().preset());

        selection.select(Identifier.parse("urbexpack:ruins"));
        selection.publish();

        assertEquals(Identifier.parse("urbexpack:ruins"), WorldSelectionHandoff.pending().preset());
        assertEquals("{\"cities\":{\"cityChance\":0.9}}", WorldSelectionHandoff.pending().patch().orElse(null));
    }

    /** Turn the pack back on and the real entry takes over; the exceptional row must not outlive the
     *  condition it reports. */
    @Test
    void theUnlistedRowGivesWayOnceTheRealPresetIsInjected() {
        PresetSelection selection = new PresetSelection();
        selection.restore("urbexpack:ruins", "", "");
        selection.setAvailablePresets(List.of(entry("default")));

        PresetSelection.Entry real = new PresetSelection.Entry(Identifier.parse("urbexpack:ruins"),
                Component.literal("Ruins"), new Preset(Identifier.parse("urbexpack:ruins")));
        selection.setAvailablePresets(List.of(entry("default"), real));

        assertEquals(List.of(PresetSelection.DISABLED_ID, id("default"), Identifier.parse("urbexpack:ruins")),
                selection.entries().stream().map(PresetSelection.Entry::id).toList());
        assertNotNull(selection.selected().preset(), "and it is now a real, editable entry");
    }

    /** Before anything is injected there is nothing to be absent from, so the restore keeps waiting
     *  rather than declaring the preset unlisted on the strength of an empty list. */
    @Test
    void anEmptyInjectionIsTooEarlyToCallAPresetUnlisted() {
        PresetSelection selection = new PresetSelection();
        selection.restore("urbex:cavern", "", "");

        selection.setAvailablePresets(List.of());

        assertEquals(PresetSelection.DISABLED_ID, selection.selected().id());

        selection.setAvailablePresets(List.of(entry("cavern")));
        assertEquals(id("cavern"), selection.selected().id());
        assertNotNull(selection.selected().preset());
    }

    // ---- the modpack default (issue #204) -------------------------------------------------------

    @Test
    void theConfiguredDefaultBecomesTheTabsStartingSelection() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default"), entry("largecities")));
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbex:lcmt"));

        assertTrue(selection.applyConfiguredDefault(id("largecities"), WorldStyleMix.parse("urbex:lcmt")));

        assertEquals(id("largecities"), selection.selected().id());
        assertEquals(WorldStyleMix.parse("urbex:lcmt"), selection.selectedWorldStyles());
    }

    /**
     * The Cities tab is rebuilt on every {@code CreateWorldScreen.init()} - every window resize
     * included - so a default that re-applied would put the pack's preset back over a player who
     * deliberately chose Disabled.
     */
    @Test
    void theConfiguredDefaultIsAppliedOnceAndNeverReAppliedOverAPlayersChoice() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default"), entry("largecities")));
        selection.applyConfiguredDefault(id("largecities"), null);

        selection.select(PresetSelection.DISABLED_ID);
        assertFalse(selection.applyConfiguredDefault(id("largecities"), null));

        assertEquals(PresetSelection.DISABLED_ID, selection.selected().id());
    }

    /** This world's own history outranks a pack default. */
    @Test
    void aReCreateRestoreWinsOverTheConfiguredDefault() {
        PresetSelection selection = new PresetSelection();
        selection.restore("urbex:cavern", "", "");
        selection.setAvailablePresets(List.of(entry("cavern"), entry("largecities")));

        assertFalse(selection.applyConfiguredDefault(id("largecities"), null));

        assertEquals(id("cavern"), selection.selected().id());
    }

    /** A configured preset the enabled datapacks do not offer leaves the tab alone; the server still
     *  resolves and reports the id. */
    @Test
    void anUnknownConfiguredDefaultChangesNothing() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default")));

        assertFalse(selection.applyConfiguredDefault(id("nosuchpreset"), null));

        assertEquals(PresetSelection.DISABLED_ID, selection.selected().id());
    }

    @Test
    void aSecondSetAvailablePresetsReSelectsTheCurrentChoiceAgainstTheFreshList() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("cavern")));
        selection.select(id("cavern"));

        // A rebuild of the Cities tab (e.g. a window resize) re-injects a fresh list; the current
        // choice must still be selected against it.
        PresetSelection.Entry freshCavern = entry("cavern");
        selection.setAvailablePresets(List.of(freshCavern));

        assertEquals(freshCavern, selection.selected());
    }
}

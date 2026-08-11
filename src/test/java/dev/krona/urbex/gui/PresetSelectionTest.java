package dev.krona.urbex.gui;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.setup.Config;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void customizedEntrySortsLastAndCarriesTheSentinelId() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default")));
        selection.applyCustomized(preset("default"));

        List<Identifier> ids = selection.entries().stream().map(PresetSelection.Entry::id).toList();

        assertEquals(List.of(PresetSelection.DISABLED_ID, id("default"), PresetSelection.CUSTOMIZED_ID), ids);
    }

    @Test
    void customizedEntryNameIsGenericNotTiedToAnyBaseId() {
        PresetSelection selection = new PresetSelection();
        selection.applyCustomized(preset("default"));

        assertEquals("urbex.preset.custom", keyOf(selection.selected().name()));
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
    void disabledEntryPublishesAllThreeConfigFieldsNull() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("cavern")));
        selection.select(id("cavern"));
        selection.select(PresetSelection.DISABLED_ID);

        selection.publish();

        assertNull(Config.presetFromClient);
        assertNull(Config.worldStyleFromClient);
        assertNull(Config.overridesFromClient);
    }

    @Test
    void plainPresetPublishesItsOwnIdWithNoOverridesAndTheDefaultWorldStyle() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("cavern")));
        selection.select(id("cavern"));

        selection.publish();

        assertEquals(id("cavern"), Config.presetFromClient);
        assertEquals(Config.DEFAULT_WORLD_STYLE, Config.worldStyleFromClient);
        assertNull(Config.overridesFromClient);
    }

    @Test
    void plainPresetPublishesTheChosenWorldStyleOverride() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("cavern")));
        selection.select(id("cavern"));
        selection.setAvailableWorldStyles(List.of("standard", "lcmt"));
        selection.setWorldStyle("lcmt");

        selection.publish();

        assertEquals(id("cavern"), Config.presetFromClient);
        assertEquals(id("lcmt"), Config.worldStyleFromClient);
        assertNull(Config.overridesFromClient);
    }

    @Test
    void customizedEntryPublishesTheBasePresetIdPlusParseableOverridesJson() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default")));
        selection.select(id("default"));

        Preset copy = preset("default");
        copy.CITY_CHANCE = 0.5;
        selection.applyCustomized(copy);
        selection.publish();

        assertEquals(id("default"), Config.presetFromClient, "the base preset id, not the sentinel");
        assertEquals(Config.DEFAULT_WORLD_STYLE, Config.worldStyleFromClient);
        assertNotNull(Config.overridesFromClient);

        // The published JSON is a real, parseable PresetRE overlay (not a stringified profile).
        com.mojang.serialization.DataResult<dev.krona.urbex.worldgen.lost.regassets.PresetRE> parsed =
                dev.krona.urbex.worldgen.lost.regassets.PresetRE.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                        com.google.gson.JsonParser.parseString(Config.overridesFromClient));
        assertTrue(parsed.isSuccess(), "overridesFromClient must decode as a PresetRE: " + parsed);
        assertEquals(0.5, parsed.getOrThrow().cities().orElseThrow().cityChance().orElseThrow(), 1e-9);
    }

    @Test
    void customizedEntryPublishesTheChosenWorldStyleOverride() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default")));
        selection.select(id("default"));
        selection.setAvailableWorldStyles(List.of("standard", "lcmt"));
        selection.setWorldStyle("lcmt");
        selection.applyCustomized(preset("default"));

        selection.publish();

        assertEquals(id("lcmt"), Config.worldStyleFromClient);
    }

    // ---- restore() (Re-Create flow, issue #85) --------------------------------------------------

    @Test
    void restoreWithEmptyPresetIsANoOp() {
        PresetSelection selection = new PresetSelection();

        selection.restore("", "", "");

        assertNull(Config.presetFromClient);
    }

    @Test
    void restoreWithAMalformedPresetIdIsANoOp() {
        PresetSelection selection = new PresetSelection();

        selection.restore("not a valid identifier!!", "", "");

        assertNull(Config.presetFromClient);
    }

    @Test
    void restoreOfAPlainPresetPublishesImmediatelyEvenWithNoEntriesInjectedYet() {
        // Issue #85: the restore has to reach the server even if the Cities tab (and therefore
        // setAvailablePresets) never runs before the world is created.
        PresetSelection selection = new PresetSelection();

        selection.restore("urbex:rare", "", "");

        assertEquals(id("rare"), Config.presetFromClient);
        assertEquals(Config.DEFAULT_WORLD_STYLE, Config.worldStyleFromClient);
        assertNull(Config.overridesFromClient);
    }

    @Test
    void restoreWithASavedWorldStylePublishesItVerbatim() {
        PresetSelection selection = new PresetSelection();

        selection.restore("urbex:rare", "urbex:lcmt", "");

        assertEquals(id("lcmt"), Config.worldStyleFromClient);
    }

    @Test
    void restoreWithJsonPublishesTheOverridesVerbatim() {
        PresetSelection selection = new PresetSelection();

        selection.restore("urbex:default", "", "{\"cities\":{\"cityChance\":0.9}}");

        assertEquals(id("default"), Config.presetFromClient);
        assertEquals("{\"cities\":{\"cityChance\":0.9}}", Config.overridesFromClient);
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
        assertEquals(0.9, selection.selected().preset().CITY_CHANCE, 1e-9);
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

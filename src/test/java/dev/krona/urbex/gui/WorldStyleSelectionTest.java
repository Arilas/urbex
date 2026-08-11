package dev.krona.urbex.gui;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.setup.Config;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * worldStyle is orthogonal to the chosen preset (spec 1a): since Task 4 a {@link Preset} carries no
 * worldStyle field of its own at all, so switching styles never edits or clones a preset - it is
 * simply its own value published alongside {@code Config.presetFromClient}. The enumeration of
 * registered styles needs a live datapack registry that can't be built headless, so the state under
 * test takes the available style ids injected via {@link PresetSelection#setAvailableWorldStyles(List)} -
 * exactly what the Cities tab feeds it from the real registry. Everything else here is pure state.
 */
class WorldStyleSelectionTest {

    @BeforeAll
    static void bootstrap() {
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

    private static PresetSelection.Entry entry(String path) {
        return new PresetSelection.Entry(id(path), Component.literal(path), new Preset(id(path)));
    }

    @Test
    void aSingleRegisteredStyleYieldsASingleChoiceSoTheDropdownIsHidden() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("urbex:standard"));

        assertEquals(1, selection.styleChoices().size());
    }

    @Test
    void withNoOverrideTheEffectiveStyleIsTheDefault() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbex:lcmt"));

        assertEquals("urbex:standard", selection.effectiveWorldStyle());
        assertNull(selection.selectedWorldStyle());
    }

    @Test
    void choosingAStyleMakesItTheEffectiveOneAndPublishesItVerbatim() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default")));
        selection.select(id("default"));
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbex:lcmt"));

        selection.setWorldStyle("urbex:lcmt");

        assertEquals("urbex:lcmt", selection.effectiveWorldStyle());

        selection.publish();
        // A worldStyle choice publishes as its own Config field - no preset customization involved.
        assertEquals(id("default"), Config.presetFromClient);
        assertEquals(Identifier.parse("urbex:lcmt"), Config.worldStyleFromClient);
        assertNull(Config.overridesFromClient);
    }

    @Test
    void switchingPresetKeepsAStillValidChosenStyle() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default"), entry("alpha")));
        selection.select(id("default"));
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbex:lcmt"));
        selection.setWorldStyle("urbex:lcmt");

        selection.select(id("alpha"));

        assertEquals("urbex:lcmt", selection.selectedWorldStyle(), "the preset and the style are independent choices");
    }

    @Test
    void aRegistryThatDropsTheChosenStyleClearsTheOverride() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbex:lcmt"));
        selection.setWorldStyle("urbex:lcmt");
        assertEquals("urbex:lcmt", selection.selectedWorldStyle());

        // The registry no longer offers "urbex:lcmt": the stale choice must reset to "the default".
        selection.setAvailableWorldStyles(List.of("urbex:standard"));

        assertNull(selection.selectedWorldStyle());
    }
}

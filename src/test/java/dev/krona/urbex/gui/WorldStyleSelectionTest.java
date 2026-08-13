package dev.krona.urbex.gui;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.setup.Config;
import dev.krona.urbex.setup.WorldSelectionHandoff;
import dev.krona.urbex.setup.WorldStyleMix;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * worldStyle is orthogonal to the chosen preset (spec 1a): since Task 4 a {@link Preset} carries no
 * worldStyle field of its own at all, so switching styles never edits or clones a preset - it is
 * simply its own value published alongside {@code WorldSelectionHandoff.pending().preset()}. The enumeration of
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
        assertNull(selection.selectedWorldStyles());
    }

    @Test
    void choosingAStyleMakesItTheEffectiveOneAndPublishesItVerbatim() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default")));
        selection.select(id("default"));
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbex:lcmt"));

        selection.setWorldStyles(WorldStyleMix.parse("urbex:lcmt"));

        assertEquals("urbex:lcmt", selection.effectiveWorldStyle());

        selection.publish();
        // A worldStyle choice publishes alongside the preset - no customization involved.
        assertEquals(id("default"), WorldSelectionHandoff.pending().preset());
        assertEquals(WorldStyleMix.parse("urbex:lcmt"), WorldSelectionHandoff.pending().worldStyles());
        assertTrue(WorldSelectionHandoff.pending().patch().isEmpty());
    }

    @Test
    void switchingPresetKeepsAStillValidChosenStyle() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailablePresets(List.of(entry("default"), entry("alpha")));
        selection.select(id("default"));
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbex:lcmt"));
        selection.setWorldStyles(WorldStyleMix.parse("urbex:lcmt"));

        selection.select(id("alpha"));

        assertEquals(WorldStyleMix.parse("urbex:lcmt"), selection.selectedWorldStyles(), "the preset and the style are independent choices");
    }

    @Test
    void aRegistryThatDropsTheChosenStyleClearsTheOverride() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("urbex:standard", "urbex:lcmt"));
        selection.setWorldStyles(WorldStyleMix.parse("urbex:lcmt"));
        assertEquals(WorldStyleMix.parse("urbex:lcmt"), selection.selectedWorldStyles());

        // The registry no longer offers "urbex:lcmt": the stale choice must reset to "the default".
        selection.setAvailableWorldStyles(List.of("urbex:standard"));

        assertNull(selection.selectedWorldStyles());
    }
}

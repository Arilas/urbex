package dev.krona.urbex.gui;

import dev.krona.urbex.config.Preset;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detail panel is widget code, but the blurb it shows is a pure function of the selected entry:
 * the old editor's three-part colouring (plain description, aqua extra, red warning) has to survive
 * the move to {@link Preset}, and a preset that leaves a part empty must not get a blank line for it.
 */
class CitiesTabTest {

    @AfterEach
    void clearReopenRequest() {
        // Static one-shot flag; tests must not leak it into each other or into a later run.
        CitiesTab.forgetReopenOnCitiesTab();
    }

    private static PresetSelection.Entry entryFor(Preset preset) {
        return new PresetSelection.Entry(Identifier.fromNamespaceAndPath("urbex", "test"),
                Component.literal("test"), preset);
    }

    private static Preset preset(String description, String extra, String warning) {
        Preset preset = new Preset(Identifier.fromNamespaceAndPath("urbex", "test"));
        preset.setDescription(description);
        preset.setExtraDescription(extra);
        preset.setWarning(warning);
        return preset;
    }

    @Test
    void theDisabledEntryExplainsItselfInsteadOfShowingAProfileBlurb() {
        PresetSelection.Entry disabled = new PresetSelection.Entry(PresetSelection.DISABLED_ID,
                Component.translatable("urbex.preset.disabled"), null);
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class,
                CitiesTab.describe(disabled).getContents());
        assertEquals("urbex.preset.disabled.info", contents.getKey());
    }

    @Test
    void aDescriptionOnlyProfileGetsNoExtraLines() {
        Component described = CitiesTab.describe(entryFor(preset("Common cities", "", "")));
        assertEquals("Common cities", described.getString());
        assertTrue(described.getSiblings().isEmpty(), "empty extra/warning must not add blank lines");
    }

    @Test
    void extraAndWarningKeepTheOldEditorsColours() {
        Component described = CitiesTab.describe(entryFor(preset("Base", "Harder than it looks", "Needs a caves world")));
        List<Component> siblings = described.getSiblings();
        // description, then (newline, extra), then (newline, warning)
        assertEquals(4, siblings.size());
        assertEquals("Harder than it looks", siblings.get(1).getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA), siblings.get(1).getStyle().getColor());
        assertEquals("Needs a caves world", siblings.get(3).getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), siblings.get(3).getStyle().getColor());
    }

    @Test
    void aWarningWithoutAnExtraDescriptionStillRenders() {
        Component described = CitiesTab.describe(entryFor(preset("Base", "", "Careful")));
        List<Component> siblings = described.getSiblings();
        assertEquals(2, siblings.size(), "expected exactly one newline plus the warning");
        assertEquals("Careful", siblings.get(1).getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), siblings.get(1).getStyle().getColor());
    }

    @Test
    void noReopenIsRequestedUntilTheEditorIsOpened() {
        assertFalse(CitiesTab.consumeReopenOnCitiesTab(),
                "a plain Create World screen must not be redirected to the Cities tab");
    }

    @Test
    void theReopenRequestIsConsumedExactlyOnce() {
        // CreateWorldScreen.init() re-runs on every window resize, not only on the way back from
        // the editor, so a request that stayed set would hijack the tab on every resize.
        CitiesTab.requestReopenOnCitiesTab();
        assertTrue(CitiesTab.consumeReopenOnCitiesTab());
        assertFalse(CitiesTab.consumeReopenOnCitiesTab());
    }

    @Test
    void aRequestBelongingToAnAbandonedEditorTripCanBeDropped() {
        CitiesTab.requestReopenOnCitiesTab();
        CitiesTab.forgetReopenOnCitiesTab();
        assertFalse(CitiesTab.consumeReopenOnCitiesTab());
    }
}

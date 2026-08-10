package dev.krona.urbex.gui;

import dev.krona.urbex.config.UrbexProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detail panel is widget code, but the blurb it shows is a pure function of the selected entry:
 * the old editor's three-part colouring (plain description, aqua extra, red warning) has to survive
 * the move, and profiles that leave a part empty must not get a blank line for it.
 */
class CitiesTabTest {

    private static PresetSelection.Entry entryFor(UrbexProfile profile) {
        return new PresetSelection.Entry("test", Component.literal("test"), false, "", Optional.of(profile));
    }

    private static UrbexProfile profile(String description, String extra, String warning) {
        UrbexProfile profile = new UrbexProfile("test", true);
        profile.setDescription(description);
        profile.setExtraDescription(extra);
        profile.setWarning(warning);
        return profile;
    }

    @Test
    void theDisabledEntryExplainsItselfInsteadOfShowingAProfileBlurb() {
        PresetSelection.Entry disabled = new PresetSelection.Entry(PresetSelection.DISABLED_ID,
                Component.translatable("urbex.preset.disabled"), false, "", Optional.empty());
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class,
                CitiesTab.describe(disabled).getContents());
        assertEquals("urbex.preset.disabled.info", contents.getKey());
    }

    @Test
    void aDescriptionOnlyProfileGetsNoExtraLines() {
        Component described = CitiesTab.describe(entryFor(profile("Common cities", "", "")));
        assertEquals("Common cities", described.getString());
        assertTrue(described.getSiblings().isEmpty(), "empty extra/warning must not add blank lines");
    }

    @Test
    void extraAndWarningKeepTheOldEditorsColours() {
        Component described = CitiesTab.describe(entryFor(profile("Base", "Harder than it looks", "Needs a caves world")));
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
        Component described = CitiesTab.describe(entryFor(profile("Base", "", "Careful")));
        List<Component> siblings = described.getSiblings();
        assertEquals(2, siblings.size(), "expected exactly one newline plus the warning");
        assertEquals("Careful", siblings.get(1).getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), siblings.get(1).getStyle().getColor());
    }
}

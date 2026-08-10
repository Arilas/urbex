package dev.krona.urbex.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.krona.urbex.config.ProfileSetup;
import dev.krona.urbex.config.UrbexProfile;
import dev.krona.urbex.setup.Config;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * worldStyle is orthogonal to the chosen preset (spec 1a): a player picks a preset and then freely
 * switches worldStyle without editing or cloning a profile. The enumeration of registered styles
 * needs a live datapack registry that can't be built headless, so the state under test takes the
 * available style ids injected via {@link PresetSelection#setAvailableWorldStyles(List)} - exactly
 * what the Cities tab feeds it from the real registry. Everything else here is pure state.
 */
class WorldStyleSelectionTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void seedProfiles() {
        ProfileSetup.STANDARD_PROFILES.clear();
        ProfileSetup.USER_PROFILES.clear();
        ProfileSetup.PROFILE_BASED_ON.clear();
        // Fresh profiles default their worldStyle to "standard".
        ProfileSetup.STANDARD_PROFILES.put("default", new UrbexProfile("default", true));
        ProfileSetup.STANDARD_PROFILES.put("alpha", new UrbexProfile("alpha", true));
        Config.reset();
    }

    @AfterEach
    void clearProfiles() {
        ProfileSetup.STANDARD_PROFILES.clear();
        ProfileSetup.USER_PROFILES.clear();
        ProfileSetup.PROFILE_BASED_ON.clear();
        Config.reset();
    }

    private static String worldStyleOf(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return root.getAsJsonObject(UrbexProfile.CATEGORY_CITY_ID).get("worldStyle").getAsString();
    }

    @Test
    void choosingANonDefaultStylePublishesACustomizedProfileCarryingIt() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("standard", "lcmt"));
        selection.select("default");

        selection.setWorldStyle("lcmt");
        selection.publish();

        // A worldStyle override reaches the server exactly like an editor customization: the
        // reconstructable "customized" name plus a full JSON that carries the chosen style.
        assertEquals(PresetSelection.CUSTOM_ID, Config.profileFromClient);
        assertEquals("lcmt", worldStyleOf(Config.jsonFromClient));
    }

    @Test
    void settingTheStyleBackToThePresetOwnClearsTheOverride() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("standard", "lcmt"));
        selection.select("default");
        selection.setWorldStyle("lcmt");
        selection.publish();

        // The preset "default" owns the "standard" style, so choosing it back is no override.
        selection.setWorldStyle("standard");
        selection.publish();

        assertEquals("default", Config.profileFromClient);
        assertNull(Config.jsonFromClient);
    }

    @Test
    void aSingleRegisteredStyleYieldsASingleChoiceSoTheDropdownIsHidden() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("standard"));

        assertEquals(1, selection.styleChoices().size());
    }

    @Test
    void selectingADifferentPresetKeepsAValidStyleButDropsAnInvalidOne() {
        PresetSelection selection = new PresetSelection();
        selection.setAvailableWorldStyles(List.of("standard", "lcmt"));
        selection.select("default");
        selection.setWorldStyle("lcmt");

        // "lcmt" is still a registered style, so switching preset keeps the chosen style.
        selection.select("alpha");
        assertEquals("lcmt", selection.selectedWorldStyle());

        // Now the registry no longer offers "lcmt": the stale choice must reset to "the preset's own".
        selection.setAvailableWorldStyles(List.of("standard"));
        assertNull(selection.selectedWorldStyle());
    }
}

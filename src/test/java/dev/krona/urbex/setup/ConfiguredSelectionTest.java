package dev.krona.urbex.setup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.krona.urbex.config.CitiesTabAccess;
import dev.krona.urbex.config.UrbexConfig;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a modpack writes in {@code config/urbex/urbex.json} and what the rest of the mod reads back
 * out of it (issue #204): the selection the pack chose, and how much of it the player may change.
 */
class ConfiguredSelectionTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static GlobalConfig from(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return GlobalConfig.of(UrbexConfig.fromJson(object).orElseThrow());
    }

    // ---- citiesTabAccess ------------------------------------------------------------------------

    @Test
    void theTabIsEditableUnlessAPackSaysOtherwise() {
        assertEquals(CitiesTabAccess.EDITABLE, UrbexConfig.DEFAULT.citiesTabAccess());
        assertTrue(CitiesTabAccess.EDITABLE.visible());
        assertTrue(CitiesTabAccess.EDITABLE.editable());
    }

    @Test
    void lockedShowsTheTabWithoutLettingThePlayerChangeIt() {
        UrbexConfig config = UrbexConfig.fromJson(
                JsonParser.parseString("{\"citiesTabAccess\": \"locked\"}").getAsJsonObject()).orElseThrow();

        assertEquals(CitiesTabAccess.LOCKED, config.citiesTabAccess());
        assertTrue(config.citiesTabAccess().visible(), "reading the pack's choice is the point of locked");
        assertFalse(config.citiesTabAccess().editable());
    }

    @Test
    void hiddenTakesTheTabAwayEntirely() {
        UrbexConfig config = UrbexConfig.fromJson(
                JsonParser.parseString("{\"citiesTabAccess\": \"hidden\"}").getAsJsonObject()).orElseThrow();

        assertFalse(config.citiesTabAccess().visible());
        assertFalse(config.citiesTabAccess().editable());
    }

    /**
     * A misspelled value fails the whole parse, which {@code ConfigRepository} reports and then falls
     * back to the defaults for. Better than silently reading as {@code editable}: a pack author who
     * typed {@code "lock"} would otherwise ship an unlocked tab and never hear about it.
     */
    @Test
    void anUnknownAccessValueDoesNotQuietlyReadAsEditable() {
        assertTrue(UrbexConfig.fromJson(
                JsonParser.parseString("{\"citiesTabAccess\": \"lock\"}").getAsJsonObject()).isEmpty());
    }

    // ---- selectedPreset / selectedWorldStyle ----------------------------------------------------

    @Test
    void aPackNamesItsPresetAndStyle() {
        GlobalConfig config = from("{\"selectedPreset\": \"urbex:largecities\", "
                + "\"selectedWorldStyle\": \"urbex:lcmt\"}");

        assertEquals(Identifier.parse("urbex:largecities"), config.selectedPreset());
        assertEquals(WorldStyleMix.of(Identifier.parse("urbex:lcmt")), config.selectedWorldStyles());
    }

    /**
     * Issue #204: {@code selectedWorldStyle} used to take a single id only, while every other place a
     * style is written down already took the weighted grammar. A bare qualified id is a one-entry mix
     * in that grammar, so every config written before this parses unchanged.
     */
    @Test
    void selectedWorldStyleAcceptsAMixWhenTheExperimentalGateIsOpen() {
        GlobalConfig config = from("{\"selectedWorldStyle\": \"urbex:standard*0.25+urbex:lcmt*0.75\", "
                + "\"experimentalMultiWorldStyles\": true}");

        assertEquals(WorldStyleMix.parse("urbex:standard*0.25+urbex:lcmt*0.75"), config.selectedWorldStyles());
    }

    /** Gated on the value, not only on the UI - same as every other mix that arrives from a file. */
    @Test
    void aMixIsReducedToItsPrimaryWithoutTheOptIn() {
        GlobalConfig config = from("{\"selectedWorldStyle\": \"urbex:standard*0.25+urbex:lcmt*0.75\"}");

        assertEquals(WorldStyleMix.of(Identifier.parse("urbex:lcmt")), config.selectedWorldStyles(),
                "the heaviest entry, kept alone");
    }

    @Test
    void aMalformedStyleFallsBackToTheDefaultRatherThanFailingTheWholeConfig() {
        GlobalConfig config = from("{\"selectedWorldStyle\": \"not a style!!\"}");

        assertEquals(Config.DEFAULT_WORLD_STYLE_MIX, config.selectedWorldStyles());
    }

    @Test
    void noSelectedPresetMeansThePackNamesNoDefault() {
        assertNull(from("{}").selectedPreset());
    }
}

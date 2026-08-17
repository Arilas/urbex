package dev.krona.urbex.config;

import com.google.gson.JsonParser;
import dev.krona.urbex.worldgen.lost.regassets.PresetDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * That a partial {@code PresetDefinition} overlay reaches the resolved preset.
 *
 * <p>This is the one thing a site caller cannot check for itself. DFU codecs ignore map keys they do
 * not recognise, so an overlay naming a section or a field that does not exist is dropped in
 * silence: the world generates, nothing is logged, and the only symptom is that the setting had no
 * effect - which looks exactly like the setting not working.</p>
 *
 * <p>The overlay below is {@code Urbex-Bunkers}' own, field for field. If a preset key is ever
 * renamed, this fails here rather than in somebody's cave.</p>
 */
class SiteOverrideApplicationTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String BUNKER_OVERLAY = """
            {
              "buildings": {
                "buildingChance": 0.6,
                "buildingMinFloors": 0,
                "buildingMaxFloors": 3,
                "buildingMinCellars": 1,
                "buildingMaxCellars": 2
              },
              "decoration": {
                "lightingDensity": 0.95
              },
              "destruction": {
                "ruinChance": 0.0
              }
            }
            """;

    @Test
    void aPartialOverlayChangesOnlyWhatItNames() {
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbex", "test-base"));
        draft.BUILDING_MAXFLOORS = 9;
        draft.BUILDING_MAXCELLARS = 9;
        draft.RUIN_CHANCE = 0.5f;
        draft.CORRIDOR_CHANCE = 0.25f;      // named by no section below
        Preset base = draft.resolve();

        Preset overridden = Presets.applyOverrides(base,
                PresetDefinition.parseOverrides(JsonParser.parseString(BUNKER_OVERLAY)));

        assertEquals(3, overridden.buildingMaxFloors(), "buildingMaxFloors did not take");
        assertEquals(2, overridden.buildingMaxCellars(), "buildingMaxCellars did not take");
        assertEquals(0.6f, overridden.buildingChance(), 1e-6, "buildingChance did not take");
        assertEquals(0.95f, overridden.lightingDensity(), 1e-6, "lightingDensity did not take");
        assertEquals(0.0f, overridden.ruinChance(), 1e-6, "ruinChance did not take");
        assertEquals(0.25f, overridden.corridorChance(), 1e-6,
                "an overlay must leave everything it does not name alone");
    }

    /**
     * The failure this whole class is about. A section that does not exist is not an error to a
     * codec - it is a key nobody asked about - so the overlay silently does nothing.
     */
    @Test
    void anOverlayNamingASectionThatDoesNotExistChangesNothing() {
        PresetDraft draft = new PresetDraft(Identifier.fromNamespaceAndPath("urbex", "test-base"));
        draft.BUILDING_MAXFLOORS = 9;
        Preset base = draft.resolve();

        Preset overridden = Presets.applyOverrides(base, PresetDefinition.parseOverrides(
                JsonParser.parseString("{ \"building\": { \"buildingMaxFloors\": 3 } }")));

        assertEquals(9, overridden.buildingMaxFloors(),
                "a misspelled section is dropped in silence - that is the trap this documents");
    }
}

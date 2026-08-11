package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.config.Preset;
import dev.krona.urbex.worldgen.lost.cityassets.CityStyle;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleRE;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Minimal generation settings for tests that exercise a decision in isolation.
 *
 * <p>Both factories are deliberately extreme rather than realistic: a profile that always wants a
 * building and a style that overrides nothing, so any non-building outcome in a test can only have
 * come from the rule under test and not from a chance roll.
 */
final class TestProfiles {

    private TestProfiles() {
    }

    /** A profile that always wants a building and never nominates a park. */
    static Preset dense() {
        Preset profile = new Preset(Identifier.fromNamespaceAndPath("urbex", "test-dense"));
        profile.BUILDING_CHANCE = 1.0f;
        profile.OPEN_LOT_PARK_CHANCE = 0.0f;
        return profile;
    }

    /** A style that overrides nothing, so every chance falls back to the profile. */
    static CityStyle cityStyle() {
        return new CityStyle(List.of(new CityStyleRE(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty())));
    }
}

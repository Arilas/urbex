package dev.krona.urbex.setup;

import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * A resolved dimension selection: which preset generates it, which world styles it draws from, and
 * an optional {@code PresetRE} JSON overlay applied on top of the resolved preset (a
 * client-published customization, or a saved-world one). {@code overridesJson}, when present, is
 * parsed with {@code PresetRE.CODEC} and applied via {@code Presets.applyOverrides}.
 * <p>
 * {@code worldStyles} is a {@link WorldStyleMix} rather than a single id: with
 * {@code experimentalMultiWorldStyles} on it can carry several weighted styles, and every other
 * path builds a single-entry mix. A single-entry mix resolves without drawing any randomness, so
 * this being a mix costs a world that only uses one style nothing at all.
 */
public record PresetChoice(Identifier preset, WorldStyleMix worldStyles, Optional<String> overridesJson) {
}

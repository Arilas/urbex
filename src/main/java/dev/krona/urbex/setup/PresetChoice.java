package dev.krona.urbex.setup;

import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * A resolved dimension selection: which preset generates it, which worldStyle it uses, and an
 * optional {@code PresetRE} JSON overlay applied on top of the resolved preset (a client-published
 * customization, or a saved-world one). {@code overridesJson}, when present, is parsed with
 * {@code PresetRE.CODEC} and applied via {@code Presets.applyOverrides}.
 */
public record PresetChoice(Identifier preset, Identifier worldStyle, Optional<String> overridesJson) {
}

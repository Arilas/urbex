package dev.krona.urbex.setup;

import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * What one world generates with: a preset, the world styles it draws from, and an optional
 * customization patch on top.
 *
 * <p>Three things that always travel together and never did. They arrive from the create-world
 * screen as three static fields on {@code Config}, are stored in {@code UrbexData} as three strings,
 * and were carried through the resolution below as three locals - so "is there a selection" was
 * spelled differently at each step, and the patch could be dropped without the preset noticing
 * (issue #130).</p>
 *
 * <p>The patch stays a {@code String} of {@code PresetDefinition} JSON rather than a decoded overlay.
 * Decoding it is fail-soft and happens where the preset is resolved, because the two sources it can
 * come from differ in how much they can be trusted: a client publication was encoded by
 * {@code PresetSelection.publish} moments ago, while saved data may have been hand-edited between
 * sessions, and a corrupt one must not refuse the level.</p>
 */
public record WorldSelection(Identifier preset, WorldStyleMix worldStyles, Optional<String> patch) {

    public WorldSelection(Identifier preset, WorldStyleMix worldStyles) {
        this(preset, worldStyles, Optional.empty());
    }

    /** The choice this selection makes for one dimension. */
    public PresetChoice asChoice() {
        return new PresetChoice(preset, worldStyles, patch);
    }
}

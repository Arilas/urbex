package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Versioned;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * A registered palette, in whichever format version its file declares.
 * <p>
 * This is the value type of the {@code palettes} dynamic registry, and the codec registered for that
 * registry is {@link #CODEC} - the dispatcher, not either branch. {@code VER.003} requires version
 * selection to happen "before decoding, by inspecting the raw document, so a version 2 file is never
 * first decoded by the version 1 codec", and a registry can only be given one codec, so the dispatcher
 * has to be the one it is given.
 * <p>
 * <b>Two permitted implementations that share nothing.</b> {@code VER.005} refuses an {@code extends}
 * chain that crosses versions, in either direction, and its {@code > Why} block says what that buys:
 * the two formats are developed independently and "neither is a dialect of the other". So this
 * interface is deliberately thin - the version, and the {@code extends} link every registry entry has.
 * There is no common node model, no shared merge, and nothing here invites one. An abstraction over
 * both would be an abstraction over version 1, which is deprecated: it must keep loading, because every
 * shipped pack is version 1, and it must not be built upon.
 * <p>
 * <b>Not {@code sealed}, though it wants to be.</b> The intent is
 * {@code sealed … permits PaletteDefinition, PaletteV2Definition}, and that does not compile here:
 * outside a named module Java requires every permitted subtype to be in the <em>same package</em> as
 * the sealed type, and these two are deliberately not. Version 1 stays in the registry package it has
 * always been in - moving it would be the refactor of version 1 that {@code VER.004}'s reasoning says
 * not to do - and version 2 lives with the rest of the format code under
 * {@code dev.krona.urbex.format}. Sealing would therefore cost one of those two placements, which is a
 * worse trade than losing exhaustiveness checking on the two casts that exist - in
 * {@code V2Palettes.compile} and {@code Palette.inline} - each of which is guarded by the version test
 * immediately above it and by {@code VER.005}, which refuses a chain that mixes versions before either
 * is reached.
 */
public interface PaletteAssetDefinition extends Extendable, Versioned.Asset {

    /**
     * The palettes registry's codec: {@code version} decides which branch reads the document.
     * <p>
     * Version 1 keeps its own {@code RetiredKeys} wrapper, so it keeps refusing {@code inherit} and
     * {@code parent} and keeps ignoring every other unknown key ({@code VER.004} - version 1 does not
     * become stricter). Version 2 refuses both, by {@code MODEL.004} and the retired-key table.
     * {@code RetiredKeysRejectedTest} walks both branches, because a registry that rejects a retired key
     * on one of its two branches rejects it on the branch nobody's pack uses.
     */
    Codec<PaletteAssetDefinition> CODEC = Versioned.dispatch("palette", Map.of(
            1, PaletteDefinition.CODEC,
            PaletteV2Definition.FORMAT_VERSION, PaletteV2Definition.CODEC));

    /**
     * The codec for a palette written inline in a part or building: the same dispatcher, plus the one
     * thing an inline palette may not do.
     * <p>
     * {@code MERGE.011} is what this is: "A palette written inline in a part or building declares
     * {@code version}, and is read by the rules of the version it declares." So it is {@link #CODEC},
     * and an inline version 2 palette gets the version 2 codec - which is what makes {@code MERGE.012}
     * true as well, since {@code $imports} and {@code $defs} are that codec's keys and there is nothing
     * here to withhold them. Until this task the field was the version 1 codec with a refusal in front
     * of it ({@code VER.014}, now retired with a tombstone): version 2 could be named and not read, and
     * naming it by name was better than handing it to a codec that ignores keys it does not know.
     * <p>
     * {@code MERGE.009} is the addition: {@code extends} inside an inline palette is refused, because
     * "an inline palette is not a registry entry, so nothing can resolve the link". Refused here for a
     * version 2 palette and at compile time for a version 1 one - see
     * {@link dev.krona.urbex.worldgen.lost.cityassets.Palette#inline}, which has refused it since long
     * before this catalogue existed. Both raise {@code DIAG.031}; they differ only in when, and the
     * reason they differ is {@code VER.004}: version 1 does not become stricter, and moving its refusal
     * from compile to decode would refuse one file that loads today - a part whose inline palette
     * declares {@code extends} and whose descendant replaces it with {@code refpalette}, where the
     * inherited inline block is dropped before anything compiles it.
     */
    Codec<PaletteAssetDefinition> INLINE_CODEC = CODEC.validate(palette ->
            palette.formatVersion() == 1 || palette.getExtends().isEmpty()
                    ? DataResult.success(palette)
                    : DataResult.error(() -> Diag.DIAG_031.message(
                            Diagnostics.INLINE_OWNER_LOCATION,
                            "'" + palette.getExtends().orElseThrow() + "'",
                            Diagnostics.INLINE_OWNER_LOCATION)));

}

package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import dev.krona.urbex.format.Versioned;
import dev.krona.urbex.format.palette.PaletteV2Definition;

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
 * worse trade than losing exhaustiveness checking on the one {@code instanceof} that exists
 * ({@link #version1Only}), where the fallback branch is a named load error rather than a gap.
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
     * One {@code extends} chain, as version 1 entries, refusing the chain if any link is version 2.
     * <p>
     * A version 2 palette decodes as of this task and compiles as of a later one. Between the two, a
     * pack that declares {@code "version": 2} has to fail by name: dropping the entry would give it a
     * palette with no markers, and casting would give it a {@link ClassCastException} out of a worker
     * thread naming no file. This is the same reasoning as {@code VER.012}'s - a thing the format
     * accepted and then did not act on is how a pack ends up meaning something other than what it says.
     * <p>
     * Thrown rather than returned, because {@code AssetStage} records a thrown exception against the
     * asset it was compiling and carries on with the rest of the registry, which is the reporting this
     * needs: one named palette failed, and the load error says which.
     */
    static java.util.List<PaletteDefinition> version1Only(net.minecraft.resources.Identifier id,
                                                          java.util.List<PaletteAssetDefinition> chain) {
        java.util.List<PaletteDefinition> version1 = new java.util.ArrayList<>(chain.size());
        for (PaletteAssetDefinition link : chain) {
            if (link instanceof PaletteDefinition definition) {
                version1.add(definition);
                continue;
            }
            throw new IllegalStateException("The palette '" + id + "' resolves through an entry"
                    + " written in palette format version " + link.formatVersion() + ", which this"
                    + " Urbex decodes but does not yet compile. Write it in the version 1 format, or"
                    + " omit 'version', until version 2 compilation lands.");
        }
        return version1;
    }
}

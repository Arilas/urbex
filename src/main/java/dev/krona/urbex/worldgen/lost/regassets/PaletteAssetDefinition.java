package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Versioned;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * The codec for a palette written inline in a part or building: version 1, and loudly nothing else.
     * <p>
     * {@code VER.014}. {@code MERGE.011} says an inline palette is read by the rules of the version it
     * declares, and nothing reads an inline version 2 palette yet - the registry is where the dispatcher
     * lives. The two answers available in the meantime were to refuse a declared {@code version} by name
     * or to leave the field on {@code PaletteDefinition.CODEC}, which ignores keys it does not know: an
     * inline version 2 palette would then have decoded to a palette holding none of the markers its
     * author wrote, with no message. That is the silent misreading {@code VER.003} removes at the
     * registry level, and it would have survived here until inline version 2 support landed.
     * <p>
     * {@code "version": 1} is accepted, because it is true and is honoured - version 1 is exactly what
     * this codec reads. Only a version it cannot read is refused. This whole field goes away with
     * {@code VER.014}, which is marked {@code [DEPRECATED → MERGE.011]} for that reason.
     */
    Codec<PaletteDefinition> INLINE_CODEC = new Codec<>() {
        @Override
        public <T> DataResult<Pair<PaletteDefinition, T>> decode(DynamicOps<T> ops, T input) {
            Optional<Dynamic<T>> version = new Dynamic<>(ops, input).get("version").result();
            if (version.isPresent() && !isVersionOne(version.get())) {
                Object written = version.get().getValue();
                return DataResult.error(() -> Diag.DIAG_062.message(
                        Diagnostics.INLINE_OWNER_LOCATION, written));
            }
            return PaletteDefinition.CODEC.decode(ops, input);
        }

        @Override
        public <T> DataResult<T> encode(PaletteDefinition input, DynamicOps<T> ops, T prefix) {
            return PaletteDefinition.CODEC.encode(input, ops, prefix);
        }

        @Override
        public String toString() {
            return PaletteDefinition.CODEC + "[inline, version 1 only]";
        }
    };

    /** A declared {@code version} this codec can honour, which is 1 and nothing else. */
    private static boolean isVersionOne(Dynamic<?> version) {
        return version.asNumber().result().filter(number -> number.intValue() == 1
                && number.doubleValue() == number.intValue()).isPresent();
    }

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
    static List<PaletteDefinition> version1Only(Identifier id, List<PaletteAssetDefinition> chain) {
        List<PaletteDefinition> version1 = new ArrayList<>(chain.size());
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

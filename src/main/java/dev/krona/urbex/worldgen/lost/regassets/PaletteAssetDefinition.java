package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Versioned;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * The authored palette accepted by the palettes registry and by inline palette fields.
 * <p>
 * {@link #CODEC} accepts version 2 alone. It dispatches before decoding fields so an unversioned or
 * version 1 document is refused with the migration diagnostic ({@code VER.018}), and an unknown
 * version is refused as unsupported. Neither can silently lose fields through another format's codec.
 * <p>
 * {@link PaletteDefinition} still implements this interface for tests that compare converter output
 * with the legacy compiler. It is not registered with the dispatcher and is not a loadable datapack
 * format. Moving that legacy implementation to test scope is separate from the loader contract.
 */
public interface PaletteAssetDefinition extends Extendable, Versioned.Asset {

    /**
     * Version selection precedes field decoding ({@code VER.003}), including when only one version
     * is supported. The v2 codec enforces the format's strict keys and retired-key diagnostics.
     */
    Codec<PaletteAssetDefinition> CODEC = Versioned.dispatch("palette", Map.of(
            PaletteV2Definition.FORMAT_VERSION, PaletteV2Definition.CODEC));

    /**
     * Parts and buildings use the same version dispatcher ({@code MERGE.011}), including support for
     * inline {@code $imports} and {@code $defs} ({@code MERGE.012}).
     * <p>
     * An inline palette is not a registry entry, so its own {@code extends} cannot resolve and is
     * refused here ({@code MERGE.009}). Its owner supplies inheritance instead. Rejecting at decode
     * also checks an invalid ancestor that a descendant later replaces with {@code refpalette}.
     */
    Codec<PaletteAssetDefinition> INLINE_CODEC = CODEC.validate(palette ->
            palette.getExtends().isEmpty()
                    ? DataResult.success(palette)
                    : DataResult.error(() -> Diag.DIAG_031.message(
                            Diagnostics.INLINE_OWNER_LOCATION,
                            "'" + palette.getExtends().orElseThrow() + "'",
                            Diagnostics.INLINE_OWNER_LOCATION)));

}

package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeys;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A palette of materials as used by building parts - the version 1 format.
 * <p>
 * {@code palette} is optional here rather than required, because requiredness is checked after the
 * {@code extends} chain is resolved, in {@link dev.krona.urbex.worldgen.lost.cityassets.Palette}.
 * <p>
 * <b>Deprecated in fact, not in annotation.</b> Version 1 is what every shipped pack is written in, so
 * it must keep loading unchanged ({@code VER.001}), and it must not become stricter ({@code VER.004}) -
 * refusing unknown keys here would break packs retroactively, which this project has never done.
 * Nothing new should be built on it, and nothing here is shared with
 * {@link dev.krona.urbex.format.palette.PaletteV2Definition}: the two formats meet only at
 * {@link PaletteAssetDefinition}, which is the version and the {@code extends} link and nothing else.
 * <p>
 * The one thing that changed here is that it names {@link PaletteAssetDefinition} instead of
 * {@link Extendable} - the interface that supersedes it also carries the format version - and gained
 * {@link #formatVersion()}. Nothing else about this class moved.
 */
public class PaletteDefinition implements PaletteAssetDefinition {

    private static final Codec<PaletteDefinition> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.list(PaletteEntry.CODEC).optionalFieldOf("palette").forGetter(l -> Optional.ofNullable(l.paletteEntries))
            ).apply(instance, PaletteDefinition::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<PaletteDefinition> CODEC = RetiredKeys.reject(RAW, "palette");

    private final Optional<Identifier> extendsId;
    // Null when this entry declares no palette of its own and takes its ancestor's.
    private final List<PaletteEntry> paletteEntries;

    public PaletteDefinition(Optional<Identifier> extendsId, Optional<List<PaletteEntry>> entries) {
        this.extendsId = extendsId;
        this.paletteEntries = entries.map(List::copyOf).orElse(null);
    }

    @Nullable
    public List<PaletteEntry> getPaletteEntries() {
        return paletteEntries;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    /** {@code VER.001}: a palette file with no {@code version} is this format. */
    @Override
    public int formatVersion() {
        return 1;
    }


}

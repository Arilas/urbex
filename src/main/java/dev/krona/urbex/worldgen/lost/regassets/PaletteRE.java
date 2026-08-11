package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A palette of materials as used by building parts.
 * <p>
 * {@code palette} is optional here rather than required, because requiredness is checked after the
 * {@code extends} chain is resolved, in {@link dev.krona.urbex.worldgen.lost.cityassets.Palette}.
 */
public class PaletteRE implements IAsset<PaletteRE>, Extendable {

    public static final Codec<PaletteRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.list(PaletteEntry.CODEC).optionalFieldOf("palette").forGetter(l -> Optional.ofNullable(l.paletteEntries))
            ).apply(instance, PaletteRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    // Null when this entry declares no palette of its own and takes its ancestor's.
    private final List<PaletteEntry> paletteEntries;

    public PaletteRE(Optional<Identifier> extendsId, Optional<List<PaletteEntry>> entries) {
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

    @Override
    public PaletteRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}

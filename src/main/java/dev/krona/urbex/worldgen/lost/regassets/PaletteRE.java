package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A palette of materials as used by building parts
 */
public class PaletteRE implements IAsset<PaletteRE>, Extendable {

    public static final Codec<PaletteRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Codec.list(PaletteEntry.CODEC).fieldOf("palette").forGetter(l -> l.paletteEntries)
            ).apply(instance, PaletteRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    private final List<PaletteEntry> paletteEntries = new ArrayList<>();

    public PaletteRE(Optional<Identifier> extendsId, List<PaletteEntry> entries) {
        this.extendsId = extendsId;
        paletteEntries.addAll(entries);
    }

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

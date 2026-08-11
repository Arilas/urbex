package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteSelector;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public class StyleRE implements IAsset<StyleRE>, Extendable {

    public static final Codec<StyleRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(Codec.list(PaletteSelector.CODEC)).fieldOf("randompalettes").forGetter(l -> l.randomPaletteChoices)
            ).apply(instance, StyleRE::new));

    private Identifier name;

    private final Optional<Identifier> extendsId;
    private final Mergeable<List<PaletteSelector>> randomPaletteChoices;

    public StyleRE(Optional<Identifier> extendsId, Mergeable<List<PaletteSelector>> randomPaletteChoices) {
        this.extendsId = extendsId;
        this.randomPaletteChoices = randomPaletteChoices;
    }

    public Mergeable<List<PaletteSelector>> getRandomPaletteChoices() {
        return randomPaletteChoices;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    @Override
    public StyleRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}

package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class VariantRE implements IAsset<VariantRE>, Extendable {

    public static final Codec<VariantRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(BlockEntry.CODEC).fieldOf("blocks").forGetter(l -> l.blocks)
            ).apply(instance, VariantRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    private final Mergeable<BlockEntry> blocks;

    public VariantRE(Optional<Identifier> extendsId, Mergeable<BlockEntry> entries) {
        this.extendsId = extendsId;
        this.blocks = entries;
    }

    public Mergeable<BlockEntry> getBlocks() {
        return blocks;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    @Override
    public VariantRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}

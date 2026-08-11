package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionPart;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class ConditionRE implements IAsset<ConditionRE>, Extendable {

    public static final Codec<ConditionRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(ConditionPart.CODEC).fieldOf("values").forGetter(l -> l.values)
            ).apply(instance, ConditionRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    private final Mergeable<ConditionPart> values;

    public ConditionRE(Optional<Identifier> extendsId, Mergeable<ConditionPart> values) {
        this.extendsId = extendsId;
        this.values = values;
    }

    public Mergeable<ConditionPart> getValues() {
        return values;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    @Override
    public ConditionRE setRegistryName(Identifier name) {
        this.name = name;
        return this;
    }

    @Nullable
    public Identifier getRegistryName() {
        return name;
    }
}

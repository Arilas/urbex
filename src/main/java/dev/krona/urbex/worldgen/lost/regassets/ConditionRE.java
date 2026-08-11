package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionPart;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A named set of weighted, conditional values.
 * <p>
 * {@code values} is optional here rather than required, because requiredness is checked after the
 * {@code extends} chain is resolved, in {@link dev.krona.urbex.worldgen.lost.cityassets.Condition}.
 * Absent means "inherit unchanged", which is what {@code {"replace": false, "values": []}} was
 * being used for.
 */
public class ConditionRE implements IAsset<ConditionRE>, Extendable {

    public static final Codec<ConditionRE> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Identifier.CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(ConditionPart.CODEC).optionalFieldOf("values").forGetter(l -> Optional.ofNullable(l.values))
            ).apply(instance, ConditionRE::new));

    private Identifier name;
    private final Optional<Identifier> extendsId;
    private final Mergeable<ConditionPart> values;   // null when this entry declares none

    public ConditionRE(Optional<Identifier> extendsId, Optional<Mergeable<ConditionPart>> values) {
        this.extendsId = extendsId;
        this.values = values.orElse(null);
    }

    @Nullable
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

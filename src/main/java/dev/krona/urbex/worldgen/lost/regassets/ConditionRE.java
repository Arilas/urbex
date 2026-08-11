package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.worldgen.lost.regassets.data.ConditionPart;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.RetiredKeys;
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

    private static final Codec<ConditionRE> RAW = RecordCodecBuilder.create(instance ->
            instance.group(
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends").forGetter(l -> l.extendsId),
                    Mergeable.codec(ConditionPart.CODEC).optionalFieldOf("values").forGetter(l -> Optional.ofNullable(l.values))
            ).apply(instance, ConditionRE::new));

    /** Retired-key rejection wraps every registry's codec; see {@link RetiredKeys}. */
    public static final Codec<ConditionRE> CODEC = RetiredKeys.reject(RAW, "condition");

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

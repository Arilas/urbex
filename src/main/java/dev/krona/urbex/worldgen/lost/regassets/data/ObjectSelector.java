package dev.krona.urbex.worldgen.lost.regassets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Represents an object with a factor indicating how likely this object is relative to others in the same list
 */
public record ObjectSelector(float factor, String value, int minSpawnDistance, int maxSpawnDistance, int feather) {

    public static final Codec<ObjectSelector> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.fieldOf("factor").forGetter(ObjectSelector::factor),
                    Codec.STRING.fieldOf("value").forGetter(ObjectSelector::value),
                    // Real getters, not the constants this used to encode. The decode side has
                    // always been correct, so the tuning survived a round trip only by accident of
                    // never being encoded: `urbex savepreset` and the create-world screen's
                    // overrides overlay both go through PresetDefinition.CODEC, and a selector re-encoded
                    // with v -> 0 would have silently reset every distance and feather it carried.
                    Codec.INT.optionalFieldOf("minSpawnDistance", 0).forGetter(ObjectSelector::minSpawnDistance),
                    Codec.INT.optionalFieldOf("maxSpawnDistance", Integer.MAX_VALUE).forGetter(ObjectSelector::maxSpawnDistance),
                    Codec.INT.optionalFieldOf("feather", 0).forGetter(ObjectSelector::feather)
            ).apply(instance, ObjectSelector::new));
}

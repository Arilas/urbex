package dev.krona.urbex.format.palette;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.format.StrictKeys;

import java.util.Optional;
import java.util.Set;

/**
 * A load-time condition on one alternative ({@code WEIGHT.020}).
 * <p>
 * A choice whose condition does not hold is removed from its list before any share is computed, once,
 * at load ({@code WEIGHT.022}) - which is the whole difference between this and
 * {@code urbex:optional}, and why {@code WEIGHT.025} refuses to let either stand in for the other.
 * {@code when} decides whether a choice exists at all; {@code urbex:optional} decides, per position,
 * whether an existing choice is written or its replacement is.
 * <p>
 * {@code WEIGHT.023} fixes the two fields at {@code mod} and {@code pack} deliberately: both already
 * have implementations, and widening the condition to configuration or dimension would make "load
 * time" depend on state that can change without a reload.
 *
 * @param mod  a mod id that must be loaded
 * @param pack a namespace that must register assets
 */
public record When(Optional<String> mod, Optional<String> pack) {

    /** The keys {@code WEIGHT.023} defines, and no others ({@code MODEL.004}). */
    public static final Set<String> KEYS = Set.of("mod", "pack");

    public static final Codec<When> CODEC = StrictKeys.only(
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.optionalFieldOf("mod").forGetter(When::mod),
                    Codec.STRING.optionalFieldOf("pack").forGetter(When::pack)
            ).apply(instance, When::new)),
            KEYS, "'when'");
}

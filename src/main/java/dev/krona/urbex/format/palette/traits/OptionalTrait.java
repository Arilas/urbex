package dev.krona.urbex.format.palette.traits;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.palette.PointerResolver;
import dev.krona.urbex.format.palette.RawNode;
import dev.krona.urbex.format.palette.ResolvedNode;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.format.palette.TraitType;
import dev.krona.urbex.format.palette.TraitValue;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code urbex:optional} - this node is placed only when a named density roll accepts it
 * ({@code TRAIT.060}).
 * <p>
 * Named {@code OptionalTrait} rather than {@code Optional}: a class called {@code Optional} in a package
 * whose every other file uses {@link java.util.Optional} would have to be fully qualified at almost every
 * mention of either, and the format's name for the trait is its id, which is unaffected.
 * <p>
 * {@code TRAIT.065} - "The roll is addressed by position" - is a property of the generation-time roll
 * rather than of this declaration, and is the same guarantee {@code LOAD.043} states for the whole
 * resolution: the outcome does not depend on how many other markers the chunk resolved first.
 * <p>
 * <b>{@code TRAIT.061}'s density is not checked here.</b> The rule says {@code density} "names a density
 * in the preset's decoration settings", and a palette is compiled against a block registry and a set of
 * assets, not against a preset - the same palette is drawn by presets that declare different densities.
 * It is a {@code MUST} with no diagnostic, so there is no refusal to omit; the name is carried through
 * to the roll, which is where a preset exists to answer it.
 */
public final class OptionalTrait implements TraitType<OptionalTrait.Value> {

    /** The single registered instance. */
    public static final OptionalTrait TYPE = new OptionalTrait();

    private static final Identifier ID = Identifier.fromNamespaceAndPath("urbex", "optional");

    /** {@code TRAIT.061}: the required field, naming a density in the preset's decoration settings. */
    public static final String DENSITY = "density";

    /** {@code TRAIT.060}: the block written where the roll rejects this node. */
    public static final String REPLACEMENT = "replacement";

    private static final Codec<Value> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf(DENSITY).forGetter(Value::density),
            // TRAIT.062: an absent 'replacement' is air, as a value rather than as an Optional - see
            // Light.AIR for why the default is applied at decode.
            RawNode.CODEC.optionalFieldOf(REPLACEMENT, Light.AIR).forGetter(Value::replacement)
    ).apply(instance, Value::new));

    /**
     * @param density     the density this node's placement is rolled against
     * @param replacement the satellite written when the roll rejects it ({@code TRAIT.062}: air)
     */
    public record Value(String density, RawNode replacement) implements TraitValue {
    }

    private OptionalTrait() {
    }

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public Codec<Value> codec() {
        return CODEC;
    }

    @Override
    public Set<String> keys() {
        return Set.of(DENSITY, REPLACEMENT);
    }

    @Override
    public Set<String> blockValuedFields() {
        return Set.of(REPLACEMENT);
    }

    /** {@code TRAIT.095}: {@code urbex:optional} selects; see {@link Light#phase()}. */
    @Override
    public Phase phase() {
        return Phase.SELECTION;
    }

    @Override
    public Optional<String> replacementField() {
        return Optional.of(REPLACEMENT);
    }

    @Override
    public List<ReferenceTarget> references() {
        return List.of();
    }

    @Override
    public Map<String, RawNode> satellites(Value value) {
        return Map.of(REPLACEMENT, value.replacement());
    }

    @Override
    public Value withSatellites(Value value, Map<String, RawNode> satellites) {
        return new Value(value.density(),
                satellites.getOrDefault(REPLACEMENT, value.replacement()));
    }

    @Override
    public Map<ReferenceTarget, List<Identifier>> referenced(Value value) {
        return Map.of();
    }

    @Override
    public void validate(Value value, ResolvedNode owner, TraitContext context,
                         PointerResolver.Site site, Diagnostics diagnostics) {
        // TRAIT.064 - this trait beside urbex:light - is a rule about the node's whole trait set and
        // not about either trait's payload, so it is checked where the set is, not here.
    }
}

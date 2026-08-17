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
import java.util.Set;

/**
 * {@code urbex:damaged} - what this node's block becomes where the damage pass applies
 * ({@code TRAIT.010}).
 * <p>
 * {@code TRAIT.011} is the reason this is a trait on a node rather than a table on a palette: "The
 * mapping is keyed by the marker carrying the trait, not by the block state it resolves to." Version 1
 * kept one {@code Map<BlockState, BlockState>} per palette, so two markers resolving to the same block
 * shared one mapping and the last compiled won.
 * <p>
 * {@code TRAIT.012} is why {@link #validate} refuses nothing: "An {@code into} naming a block this game
 * does not have leaves the marker undamaged, and the load succeeds." That is {@code MODEL.042} arriving
 * through a satellite, and the compiled entry simply carries no damaged form.
 */
public final class Damaged implements TraitType<Damaged.Value> {

    /** The single registered instance; {@code Traits} holds it and nothing else constructs one. */
    public static final Damaged TYPE = new Damaged();

    private static final Identifier ID = Identifier.fromNamespaceAndPath("urbex", "damaged");

    /** {@code TRAIT.010}: the required field is {@code into}, and it is block-valued. */
    public static final String INTO = "into";

    private static final Codec<Value> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RawNode.CODEC.fieldOf(INTO).forGetter(Value::into)
    ).apply(instance, Value::new));

    /**
     * @param into the node this block becomes - a satellite, and so a full node by {@code TRAIT.009}:
     *             a string, a weighted list, a {@code $ref}, or one carrying traits of its own
     */
    public record Value(RawNode into) implements TraitValue {
    }

    private Damaged() {
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
        return Set.of(INTO);
    }

    @Override
    public Set<String> blockValuedFields() {
        return Set.of(INTO);
    }

    @Override
    public List<ReferenceTarget> references() {
        return List.of();
    }

    @Override
    public Map<String, RawNode> satellites(Value value) {
        return Map.of(INTO, value.into());
    }

    @Override
    public Value withSatellites(Value value, Map<String, RawNode> satellites) {
        return new Value(satellites.getOrDefault(INTO, value.into()));
    }

    @Override
    public Map<ReferenceTarget, List<Identifier>> referenced(Value value) {
        return Map.of();
    }

    @Override
    public void validate(Value value, ResolvedNode owner, TraitContext context,
                         PointerResolver.Site site, Diagnostics diagnostics) {
        // TRAIT.012: nothing to refuse. An absent 'into' block leaves the marker undamaged.
    }
}

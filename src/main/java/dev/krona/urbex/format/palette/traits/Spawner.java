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
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code urbex:spawner} - this node's block is initialised as a mob spawner ({@code TRAIT.030}).
 * <p>
 * {@code TRAIT.032} - "A node carrying {@code urbex:spawner} and rejected by spawner policy is written
 * as air" - is a generation-time behaviour rather than a load-time refusal, and so is not enforced
 * here: by {@code LOAD.011} no compiled palette can raise a diagnostic during generation, and writing
 * air is not one.
 */
public final class Spawner implements TraitType<Spawner.Value> {

    /** The single registered instance. */
    public static final Spawner TYPE = new Spawner();

    private static final Identifier ID = Identifier.fromNamespaceAndPath("urbex", "spawner");

    /** {@code TRAIT.030}: the required field, naming a {@code conditions} asset of entity ids. */
    public static final String POOL = "pool";

    private static final Codec<Value> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DataTools.STRICT_IDENTIFIER_CODEC.fieldOf(POOL).forGetter(Value::pool)
    ).apply(instance, Value::new));

    /** @param pool a {@code conditions} asset whose values are entity ids */
    public record Value(Identifier pool) implements TraitValue {
    }

    private Spawner() {
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
        return Set.of(POOL);
    }

    @Override
    public Set<String> blockValuedFields() {
        return Set.of();
    }

    @Override
    public List<ReferenceTarget> references() {
        return List.of(new ReferenceTarget(POOL, TraitContext.conditionsRegistry()));
    }

    @Override
    public Map<String, RawNode> satellites(Value value) {
        return Map.of();
    }

    @Override
    public Value withSatellites(Value value, Map<String, RawNode> satellites) {
        return value;
    }

    @Override
    public Map<ReferenceTarget, List<Identifier>> referenced(Value value) {
        return Map.of(references().get(0), List.of(value.pool()));
    }

    @Override
    public void validate(Value value, ResolvedNode owner, TraitContext context,
                         PointerResolver.Site site, Diagnostics diagnostics) {
        // TRAIT.031 is the generic reference check reading references(); see Loot's class note.
    }
}

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
 * {@code urbex:loot} - this node's block is a loot container ({@code TRAIT.020}).
 * <p>
 * <b>This trait is the one {@code TRAIT.022} is written about,</b> and it is why {@link #references()}
 * exists at all: "The trait declares {@code pool} as a reference into the {@code conditions} registry,
 * and that declaration is what reference validation reads." Nothing here checks the pool. The check is
 * generic, reads this list, and so cannot be forgotten by a trait that declares its references and can
 * not be performed at all by one that does not - which is the difference from the 48-name table that
 * drifted.
 */
public final class Loot implements TraitType<Loot.Value> {

    /** The single registered instance. */
    public static final Loot TYPE = new Loot();

    private static final Identifier ID = Identifier.fromNamespaceAndPath("urbex", "loot");

    /** {@code TRAIT.020}: the required field, naming a {@code conditions} asset. */
    public static final String POOL = "pool";

    private static final Codec<Value> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DataTools.STRICT_IDENTIFIER_CODEC.fieldOf(POOL).forGetter(Value::pool)
    ).apply(instance, Value::new));

    /** @param pool a {@code conditions} asset whose values are loot tables */
    public record Value(Identifier pool) implements TraitValue {
    }

    private Loot() {
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
        // TRAIT.021 is the generic reference check reading references(); see the class note.
    }
}

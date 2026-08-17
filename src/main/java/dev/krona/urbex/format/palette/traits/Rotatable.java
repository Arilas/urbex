package dev.krona.urbex.format.palette.traits;

import com.mojang.serialization.Codec;
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
 * {@code urbex:rotatable} - whether this node's block follows the part's rotation ({@code TRAIT.070}).
 * <p>
 * <b>{@code TRAIT.071}: absent, a node is rotatable.</b> The default is <em>on</em>, and the version 1
 * mechanism it replaces is not consulted on this path at all. Version 1 answered the question from a
 * hand-maintained block tag on the world style, holding 16 tag-includes and 27 block ids and excluding
 * nothing, with a test existing solely to catch the list falling behind the shipped palettes. The
 * predicate that tag approximated is one the platform computes exactly - rotating a state with no
 * directional property is already a no-op - so the tag is not a source of truth this format has any
 * reason to read. A pack that forgets an opt-<em>in</em> gets silently mis-facing blocks on every
 * rotated part, which is the defect the tag was introduced to fix; a pack that forgets an opt-out gets
 * a block rotated that did not need to be, which is a no-op.
 * <p>
 * {@code TRAIT.001}'s scalar shorthand, and the only trait that has one: "its whole content is one
 * boolean, and {@code "urbex:rotatable": false} is what an author writes. […] A trait that defines one
 * is declaring, through its schema, that it never will." {@link #keys()} is therefore empty, and a
 * payload written as an object is refused by the codec rather than by a key check, because there is no
 * key it could have named.
 * <p>
 * {@code TRAIT.072}: {@code false} here is meaningful rather than a restatement of a default, which is
 * why the value is kept rather than dropped as redundant.
 * <p>
 * {@code TRAIT.073}: this is rotation <em>with the part</em>. Deriving a facing from surroundings is a
 * different behaviour, is {@code [PROPOSED]} as {@code urbex:oriented}, and is not this trait.
 */
public final class Rotatable implements TraitType<Rotatable.Value> {

    /** The single registered instance. */
    public static final Rotatable TYPE = new Rotatable();

    private static final Identifier ID = Identifier.fromNamespaceAndPath("urbex", "rotatable");

    /** {@code TRAIT.071}: the value a node with no {@code urbex:rotatable} behaves as. */
    public static final Value DEFAULT = new Value(true);

    private static final Codec<Value> CODEC = Codec.BOOL.xmap(Value::new, Value::on);

    /** @param on whether this node's block follows the rotation and mirroring of the part */
    public record Value(boolean on) implements TraitValue {
    }

    private Rotatable() {
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
        return Set.of();
    }

    @Override
    public Set<String> blockValuedFields() {
        return Set.of();
    }

    @Override
    public List<ReferenceTarget> references() {
        return List.of();
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
        return Map.of();
    }

    @Override
    public void validate(Value value, ResolvedNode owner, TraitContext context,
                         PointerResolver.Site site, Diagnostics diagnostics) {
        // Nothing: both values are legal on every node, and TRAIT.073 puts the only neighbouring
        // behaviour in a trait that does not exist yet.
    }
}

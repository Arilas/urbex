package dev.krona.urbex.format.palette.traits;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.palette.PointerResolver;
import dev.krona.urbex.format.palette.RawNode;
import dev.krona.urbex.format.palette.ResolvedNode;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.format.palette.TraitType;
import dev.krona.urbex.format.palette.TraitValue;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code urbex:light} - this node's block is an optional light ({@code TRAIT.050}).
 * <p>
 * {@code TRAIT.063} says what it is in one sentence: "{@code urbex:light} behaves exactly as
 * {@code urbex:optional} with {@code density} fixed to the preset's lighting density, plus the emission
 * rules {@code TRAIT.052} and {@code TRAIT.053}." Those two rules are the whole of what this class adds,
 * and both are about the same mistake seen from opposite ends - a marker that rolls a density and then
 * looks the same either way.
 * <p>
 * {@code TRAIT.054} is why a light is never dropped rather than replaced: "A light source is never
 * filtered out of the output; the roll chooses between the lit block and the replacement, and both
 * occupy the marker." {@code TRAIT.051}'s default is what makes that true of a file that writes no
 * {@code unlit}: the satellite is air, which is a block, not an absence.
 * <p>
 * {@code TRAIT.055} - a socket candidate's own {@code unlit} beating the socket's - needs no code of its
 * own. It is {@code TRAIT.006} arriving through {@code TRAIT.005}: a candidate declaring
 * {@code urbex:light} replaces the inherited one whole, by id, so its {@code unlit} is the one that
 * survives.
 */
public final class Light implements TraitType<Light.Value> {

    /** The single registered instance. */
    public static final Light TYPE = new Light();

    private static final Identifier ID = Identifier.fromNamespaceAndPath("urbex", "light");

    /** {@code TRAIT.050}: the block written where the roll rejects the light. */
    public static final String UNLIT = "unlit";

    /**
     * {@code TRAIT.051}: "An absent {@code unlit} is air."
     * <p>
     * A default <em>value</em> rather than an {@code Optional} the compiler fills in later, so that the
     * two spellings the rule's {@code equiv} fixtures pin - {@code {}} and
     * {@code {"unlit": "minecraft:air"}} - are the same value from the moment they are decoded. An
     * {@code Optional} would make them equal only wherever somebody remembered to apply the default.
     */
    public static final RawNode AIR = RawNode.ofBlock("minecraft:air");

    private static final Codec<Value> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RawNode.CODEC.optionalFieldOf(UNLIT, AIR).forGetter(Value::unlit)
    ).apply(instance, Value::new));

    /** @param unlit the satellite written in this block's place when the density roll rejects it */
    public record Value(RawNode unlit) implements TraitValue {
    }

    private Light() {
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
        return Set.of(UNLIT);
    }

    @Override
    public Set<String> blockValuedFields() {
        return Set.of(UNLIT);
    }

    @Override
    public List<ReferenceTarget> references() {
        return List.of();
    }

    @Override
    public Map<String, RawNode> satellites(Value value) {
        return Map.of(UNLIT, value.unlit());
    }

    @Override
    public Value withSatellites(Value value, Map<String, RawNode> satellites) {
        return new Value(satellites.getOrDefault(UNLIT, value.unlit()));
    }

    @Override
    public Map<ReferenceTarget, List<Identifier>> referenced(Value value) {
        return Map.of();
    }

    /**
     * {@code TRAIT.052} and {@code TRAIT.053}, in that order.
     * <p>
     * <b>Both are conditioned on there being a state to ask about,</b> and that is {@code MODEL.042}
     * rather than caution: a marker naming a cross-mod lamp this installation does not have resolves to
     * no state at all, and refusing it would refuse a pack that is working exactly as written on the
     * installs that have the mod. So "none of its states emits light" is asked only of a node that has
     * states, and an unanswerable question is not an answer of "no".
     */
    @Override
    public void validate(Value value, ResolvedNode owner, TraitContext context,
                         PointerResolver.Site site, Diagnostics diagnostics) {
        List<BlockState> lit = context.statesOf(owner);
        if (!lit.isEmpty() && lit.stream().noneMatch(Light::emits)) {
            diagnostics.error(Diag.DIAG_023, site.location());
        }
    }

    /**
     * {@code TRAIT.053}: an {@code unlit} satellite that emits light is refused.
     * <p>
     * Separate from {@link #validate} because it is asked of the <em>satellite</em>, which
     * {@code MODEL.031} keeps out of the node's own states: "realising a node never realises its
     * satellites". The compile stage resolves the satellite and then asks this.
     */
    public static void checkUnlit(ResolvedNode unlit, TraitContext context,
                                  PointerResolver.Site site, Diagnostics diagnostics) {
        if (context.statesOf(unlit).stream().anyMatch(Light::emits)) {
            diagnostics.error(Diag.DIAG_024, site.location());
        }
    }

    /** Whether a state is a light source at all. */
    public static boolean emits(BlockState state) {
        return state.getLightEmission() > 0;
    }
}

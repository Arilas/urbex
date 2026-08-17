package dev.krona.urbex.format.palette;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A node with every reference resolved and a block source it is certain to have.
 * <p>
 * The difference from {@link RawNode} is {@code REF.034}: "After compilation, no compiled palette holds
 * a reference, a definition name, or an unresolved marker alias." There is no {@code $ref} component to
 * hold one, no {@code $only}, no {@code $without} and no {@code $spread} - not because they were
 * cleared, but because this type has nowhere to put them. That is the invariant made structural rather
 * than asserted.
 * <p>
 * The second difference is {@code MODEL.080}: a node "<em>resolves to a block source</em> when, after
 * reference resolution, it has a {@code kind} and that kind's required keys". A {@link Source} is that
 * key, already read: {@link RawNode} carries five optional block-source fields of which any number may
 * be absent, and this carries exactly one. {@code MODEL.081} is therefore the only way to build one,
 * and {@code MODEL.082} is why {@code $defs} entries never become one - a definition need not resolve
 * to a block source, so a resolved definition is still a {@link RawNode}, with its operands gone.
 * <p>
 * <b>Not the compiled form.</b> {@code LOAD.020} through {@code LOAD.024} describe a compiled palette -
 * interned trait sets, 128 materialised slots, markers remapped to a dense integer range - and this is
 * none of that. It is the output of stage 3 of {@code LOAD.001}, "link", with stages 4 to 8 still to
 * come: block strings are still strings, {@code when} is still unevaluated, and shares are still
 * fractions.
 *
 * @param kind   which block source this node has ({@code MODEL.011}'s default already applied)
 * @param source that source, with the keys the kind requires
 * @param traits the traits that apply, merged by id ({@code TRAIT.006}) and still opaque payloads
 */
public record ResolvedNode(Kind kind, Source source, Map<Identifier, Trait> traits) {

    public ResolvedNode(Kind kind, Source source, Map<Identifier, Trait> traits) {
        this.kind = kind;
        this.source = source;
        this.traits = Map.copyOf(traits);
    }

    /**
     * Where a resolved node's block comes from - one case per {@link Kind}, and exactly one per node.
     * <p>
     * {@code MODEL.013}: "{@code kind} selects exactly one block source." Sealed rather than five
     * nullable fields for that reason: version 1 held {@code block}, {@code blocks}, {@code variant},
     * {@code frompalette} and a socket {@code lightSource} as five independent keys read by an
     * {@code if}/{@code else if} ladder, and the exclusivity nothing enforced was the bug. Here the
     * exclusivity is the type.
     */
    public sealed interface Source
            permits Source.Block, Source.Weighted, Source.Tag, Source.Alias, Source.Socket {

        /** {@code MODEL.040}: one block state, still as the text the file wrote ({@code MODEL.041}). */
        record Block(String block) implements Source {
        }

        /** {@code MODEL.044}: one of these, drawn by {@code 05-weights.md}. */
        record Weighted(List<Choice> choices) implements Source {

            public Weighted(List<Choice> choices) {
                this.choices = List.copyOf(choices);
            }
        }

        /** {@code MODEL.050}: one block drawn uniformly from this block tag, expanded in stage 5. */
        record Tag(String tag) implements Source {
        }

        /**
         * {@code MODEL.060}: whatever this marker resolves to in the same merged palette.
         * <p>
         * Still a marker, and deliberately: {@code MODEL.064} says an alias and a pointer "resolve
         * against different things and are not interchangeable" - a pointer names a node in a document
         * and is answered here, an alias names a marker and is answered by the merged palette the part
         * is generated with, "including markers contributed by palettes this file never mentions". So
         * resolving it here would pin one answer where the format promises the style's.
         */
        record Alias(Marker of) implements Source {
        }

        /** {@code MODEL.070}: no block of its own - these candidates are the source. */
        record Socket(Map<Kind.Placement, List<Choice>> placements) implements Source {

            public Socket(Map<Kind.Placement, List<Choice>> placements) {
                Map<Kind.Placement, List<Choice>> copy = new LinkedHashMap<>();
                placements.forEach((placement, candidates) ->
                        copy.put(placement, List.copyOf(candidates)));
                // Ordered by Kind.Placement, not by Map.copyOf, whose iteration order is per-JVM salt.
                this.placements = Kind.Placement.ordered(copy);
            }
        }
    }

    /**
     * One alternative of a resolved list: a node, the size it takes, and the condition it exists under.
     * <p>
     * {@code size} stays optional, as it is on {@link RawChoice}, because {@code WEIGHT.005} evaluates
     * every size rule "on the list as it stands after {@code $spread} expansion and after exclusion,
     * never on the choices as written". Expansion has happened by the time one of these exists;
     * exclusion has not, and neither has the check that every remaining element states a size. Both are
     * the next stage's ({@code LOAD.001} stage 4 and {@code 05-weights.md}), and a non-optional field
     * here would have to be filled with a guess in the meantime.
     */
    public record Choice(ResolvedNode node, Optional<Size> size, Optional<When> when) {
    }
}

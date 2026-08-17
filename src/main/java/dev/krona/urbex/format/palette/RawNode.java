package dev.krona.urbex.format.palette;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.StrictKeys;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A node exactly as a version 2 palette file writes it, with nothing resolved.
 * <p>
 * {@code MODEL.010} names the five positions a node appears in, and this one record is all of them:
 * the value of a marker, the value of a name in {@code $defs}, an entry in a {@code weighted} node's
 * {@code choices}, a candidate in a {@code light_socket} placement list, and the value of a
 * block-valued trait field. One value type is the whole point - version 1 spelled "a weighted choice
 * among block sources with metadata" four different ways, in {@code blocks}, in the {@code variants}
 * registry, in {@code unlitBlocks} and in a socket's candidate lists, and each spelling had its own
 * rules because each was specified separately.
 * <p>
 * <b>Raw, and deliberately so.</b> {@code $ref}, {@code $only}, {@code $without} and {@code $spread}
 * are captured as they were written and resolved nowhere in this class. {@code REF.031} makes
 * resolution a topological sort over the whole reference graph, performed once, which is a pass over
 * every decoded document and not something a codec can do while reading one of them. The kind default
 * of {@code MODEL.011} is left unapplied for the same reason: a node carrying {@code $ref} takes its
 * kind from the node it references, so applying the default here would decide the kind of a node whose
 * kind is not yet known.
 *
 * @param kind       {@code kind}, absent when the file does not declare one ({@code MODEL.011})
 * @param block      {@code block}, a block id with an optional bracketed property expression
 * @param choices    {@code choices}, present only on a {@code weighted} node
 * @param tag        {@code tag}, a block tag reference with a leading {@code #}
 * @param aliasOf    {@code of}, the marker an {@code alias} node resolves through
 * @param placements the four {@code light_socket} candidate lists, by their position
 * @param traits     {@code traits}, by id, opaque until a trait registry exists (see {@link Trait})
 * @param ref        {@code $ref}, a pointer, unresolved
 * @param only       {@code $only}, the keys of the target to take
 * @param without    {@code $without}, the keys of the target to drop
 * @param spread     {@code $spread}, present only on a list element that stands for a list
 */
public record RawNode(Optional<Kind> kind, Optional<String> block, Optional<List<RawChoice>> choices,
                      Optional<String> tag, Optional<Marker> aliasOf,
                      Map<Kind.Placement, List<RawChoice>> placements,
                      Map<Identifier, Trait> traits, Optional<String> ref,
                      Optional<List<String>> only, Optional<List<String>> without,
                      Optional<String> spread) {

    /**
     * The keys any node accepts, whatever its kind.
     * <p>
     * {@code REF.050} closes the {@code $} set: "Inside a node the operands are {@code $ref},
     * {@code $only}, {@code $without} and {@code $spread}" and no other {@code $}-prefixed key is
     * accepted anywhere. The prefix is reserved for structure precisely so a future kind, trait or
     * block property can never collide with one, and a closed set is what keeps that promise checkable.
     */
    public static final Set<String> COMMON_KEYS =
            Set.of("kind", "traits", "$ref", "$only", "$without", "$spread");

    /** Every key any node may carry - the set to check against when the kind is not yet knowable. */
    public static final Set<String> ANY_KIND_KEYS = union(COMMON_KEYS, Kind.allKindSpecificKeys());

    /**
     * The node codec, recursive because a node contains nodes.
     * <p>
     * {@link Codec#recursive} rather than a hand-rolled lazy holder: a choice holds a node and a
     * placement candidate holds a node, so the codec has to exist before it is finished being built.
     */
    public static final Codec<RawNode> CODEC = Codec.recursive("urbex:palette/node", RawNode::build);

    private static Codec<RawNode> build(Codec<RawNode> self) {
        Codec<List<RawChoice>> choices = RawChoice.listCodec(self);
        return stringOrObject(validatedWhenComplete(keyChecked(fields(choices).codec())));
    }

    /**
     * Runs {@link #validate} only on a node that decoded completely.
     * <p>
     * Not {@link Codec#validate}, which runs on a <em>partial</em> decode too. {@code RecordCodecBuilder}
     * accumulates field failures: a field whose value fails to decode contributes an empty
     * {@link Optional} and its error, and the record is still assembled from what is left. Validating
     * that assembly means describing a node the file does not contain. The case that found it was a
     * socket whose one floor candidate declared no size - the author got the correct {@code DIAG.040}
     * <em>and</em> {@code DIAG.010}, "a light_socket declares no candidate in floor, wall, ceiling or
     * free", which was false: the candidate was right there, and it was the reason the field was empty.
     * <p>
     * The general rule this encodes: a diagnostic derived from a value is only true if the value is the
     * one the file wrote. When a field failed, the field's own diagnostic is the whole story.
     */
    private static Codec<RawNode> validatedWhenComplete(Codec<RawNode> base) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<RawNode, T>> decode(DynamicOps<T> ops, T input) {
                DataResult<Pair<RawNode, T>> decoded = base.decode(ops, input);
                if (decoded.error().isPresent()) {
                    return decoded;
                }
                Pair<RawNode, T> pair = decoded.result().orElseThrow();
                return RawNode.validate(pair.getFirst())
                        .map(node -> Pair.of(node, pair.getSecond()));
            }

            @Override
            public <T> DataResult<T> encode(RawNode input, DynamicOps<T> ops, T prefix) {
                return RawNode.validate(input).flatMap(node -> base.encode(node, ops, prefix));
            }

            @Override
            public String toString() {
                return base.toString();
            }
        };
    }

    /**
     * {@code MODEL.020}: "Wherever a node is expected, a JSON string is that node with kind
     * {@code block} and that string as its {@code block}."
     * <p>
     * Written as an inspection of the input rather than as {@code Codec.either(Codec.STRING, object)}
     * because {@code either} reports both branches' failures when both fail: an object with a misspelt
     * key would be refused with "Failed to parse either… not a string; …" in front of the
     * {@code DIAG.003} message the author needs. Here a string input is a string node and anything else
     * is an object node, and an object node's diagnostic travels alone.
     * <p>
     * {@code MODEL.021} falls out of this and is not a separate check: the string branch stores its
     * value in {@code block} and never looks at {@code $ref}, so a string is never a reference. It
     * matters that it cannot be: a definition name and a block id are both strings, both may carry a
     * namespace, and if either could appear bare then resolving {@code "urbex:rubble"} would depend on
     * which registry answered first.
     */
    private static Codec<RawNode> stringOrObject(Codec<RawNode> object) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<RawNode, T>> decode(DynamicOps<T> ops, T input) {
                DataResult<String> asString = ops.getStringValue(input);
                if (asString.result().isPresent()) {
                    return DataResult.success(
                            Pair.of(ofBlock(asString.result().orElseThrow()), ops.empty()));
                }
                return object.decode(ops, input);
            }

            @Override
            public <T> DataResult<T> encode(RawNode input, DynamicOps<T> ops, T prefix) {
                if (input.isPlainBlock()) {
                    return ops.mergeToPrimitive(prefix,
                            ops.createString(input.block().orElseThrow()));
                }
                return object.encode(input, ops, prefix);
            }

            @Override
            public String toString() {
                return "a node";
            }
        };
    }

    /** The node {@code MODEL.020} says a bare string is. */
    public static RawNode ofBlock(String block) {
        return new RawNode(Optional.of(Kind.BLOCK), Optional.of(block), Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of(), Map.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Whether this node is exactly what {@link #ofBlock} builds, and so may be written as a string. */
    public boolean isPlainBlock() {
        return kind.equals(Optional.of(Kind.BLOCK)) && block.isPresent() && choices.isEmpty()
                && tag.isEmpty() && aliasOf.isEmpty() && placements.isEmpty() && traits.isEmpty()
                && ref.isEmpty() && only.isEmpty() && without.isEmpty() && spread.isEmpty();
    }

    /**
     * {@code MODEL.004} and {@code MODEL.013} together, as one pre-pass over the raw document.
     * <p>
     * They are one check because {@code MODEL.013} says so: the kind-specific keys of one kind "are not
     * accepted on another, and are caught by MODEL.004". So the key set a node is checked against
     * depends on the node's kind, which is read out of the raw map here - before any field is decoded.
     * <p>
     * <b>Why before, and not after.</b> Version 1's {@code tag} held block-entity NBT; version 2's
     * {@code tag} is a block tag reference, on a node of kind {@code tag} only. A pack that carries the
     * version 1 spelling into version 2 therefore writes an object where this format wants a string. If
     * the key check ran after decoding, that file would fail with DFU's {@code "Not a string"} - a
     * message about a type, on a key that should never have been there. Running first, it fails with
     * {@code DIAG.061}, naming {@code tag} and the trait that replaced it.
     */
    private static Codec<RawNode> keyChecked(Codec<RawNode> base) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<RawNode, T>> decode(DynamicOps<T> ops, T input) {
                Dynamic<T> dynamic = new Dynamic<>(ops, input);
                DataResult<Set<String>> allowed = allowedKeys(dynamic);
                if (allowed.error().isPresent()) {
                    String message = allowed.error().get().message();
                    return DataResult.error(() -> message);
                }
                Optional<String> problem = StrictKeys.problem(dynamic, allowed.result().orElseThrow(),
                        context(dynamic), RetiredV2Keys.TABLE);
                if (problem.isPresent()) {
                    String message = problem.get();
                    return DataResult.error(() -> message);
                }
                return base.decode(ops, input);
            }

            @Override
            public <T> DataResult<T> encode(RawNode input, DynamicOps<T> ops, T prefix) {
                return base.encode(input, ops, prefix);
            }

            @Override
            public String toString() {
                return base.toString();
            }
        };
    }

    /**
     * The keys this particular node may carry.
     * <p>
     * A node carrying {@code $ref} takes the union of every kind's keys, because its kind arrives from
     * the node it references and is not knowable here. That is the honest answer rather than a
     * permissive one: {@code MODEL.013} is still checked, one pass later, where the reference has been
     * resolved - refusing {@code choices} beside a {@code $ref} now would refuse
     * {@code WEIGHT.017}'s own fixture, which extends an inherited weighted node.
     */
    private static <T> DataResult<Set<String>> allowedKeys(Dynamic<T> node) {
        Optional<Dynamic<T>> declared = node.get("kind").result();
        if (declared.isEmpty()) {
            Set<String> keys = node.get("$ref").result().isPresent()
                    ? ANY_KIND_KEYS
                    : union(COMMON_KEYS, Kind.BLOCK.ownKeys());
            return DataResult.success(keys);
        }
        DataResult<Kind> kind = Kind.CODEC.parse(declared.get());
        return kind.map(value -> union(COMMON_KEYS, value.ownKeys()));
    }

    /** What a {@code DIAG.003} message calls the thing whose keys were checked. */
    private static <T> String context(Dynamic<T> node) {
        if (node.get("$spread").result().isPresent()) {
            return "a '$spread' element";
        }
        Optional<String> kind = node.get("kind").asString().result();
        return kind.map(name -> "a " + name + " node").orElseGet(() ->
                node.get("$ref").result().isPresent() ? "a node" : "a block node");
    }

    private static MapCodec<RawNode> fields(Codec<List<RawChoice>> choices) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                Kind.CODEC.optionalFieldOf("kind").forGetter(RawNode::kind),
                Codec.STRING.optionalFieldOf("block").forGetter(RawNode::block),
                choices.optionalFieldOf("choices").forGetter(RawNode::choices),
                Codec.STRING.optionalFieldOf("tag").forGetter(RawNode::tag),
                Marker.CODEC.optionalFieldOf("of").forGetter(RawNode::aliasOf),
                placements(choices).forGetter(RawNode::placements),
                Trait.MAP_CODEC.optionalFieldOf("traits", Map.of()).forGetter(RawNode::traits),
                Codec.STRING.optionalFieldOf("$ref").forGetter(RawNode::ref),
                Codec.STRING.listOf().optionalFieldOf("$only").forGetter(RawNode::only),
                Codec.STRING.listOf().optionalFieldOf("$without").forGetter(RawNode::without),
                Codec.STRING.optionalFieldOf("$spread").forGetter(RawNode::spread)
        ).apply(instance, RawNode::new));
    }

    /**
     * The four {@code light_socket} lists as one map field ({@code MODEL.071}).
     * <p>
     * One record component rather than four, because {@code MODEL.073} makes the four a single ordered
     * search - floor, west wall, east wall, north wall, south wall, ceiling, {@code free} - and every
     * rule about them ({@code MODEL.072}, {@code MODEL.076}, {@code WEIGHT.043}) is a statement about
     * the set. Absent and empty are kept distinct nowhere here: a list nobody wrote and a list written
     * empty both contribute no candidate, which is exactly what {@code MODEL.072} counts.
     */
    private static MapCodec<Map<Kind.Placement, List<RawChoice>>> placements(
            Codec<List<RawChoice>> choices) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                choices.optionalFieldOf(Kind.Placement.FLOOR.key()).forGetter(at(Kind.Placement.FLOOR)),
                choices.optionalFieldOf(Kind.Placement.WALL.key()).forGetter(at(Kind.Placement.WALL)),
                choices.optionalFieldOf(Kind.Placement.CEILING.key()).forGetter(at(Kind.Placement.CEILING)),
                choices.optionalFieldOf(Kind.Placement.FREE.key()).forGetter(at(Kind.Placement.FREE))
        ).apply(instance, RawNode::placementMap));
    }

    private static java.util.function.Function<Map<Kind.Placement, List<RawChoice>>,
            Optional<List<RawChoice>>> at(Kind.Placement placement) {
        return byPlacement -> Optional.ofNullable(byPlacement.get(placement));
    }

    private static Map<Kind.Placement, List<RawChoice>> placementMap(Optional<List<RawChoice>> floor,
                                                                    Optional<List<RawChoice>> wall,
                                                                    Optional<List<RawChoice>> ceiling,
                                                                    Optional<List<RawChoice>> free) {
        Map<Kind.Placement, List<RawChoice>> byPlacement = new LinkedHashMap<>();
        floor.ifPresent(list -> byPlacement.put(Kind.Placement.FLOOR, list));
        wall.ifPresent(list -> byPlacement.put(Kind.Placement.WALL, list));
        ceiling.ifPresent(list -> byPlacement.put(Kind.Placement.CEILING, list));
        free.ifPresent(list -> byPlacement.put(Kind.Placement.FREE, list));
        return Map.copyOf(byPlacement);
    }

    /**
     * The rules about one decoded node that need no reference resolved.
     * <p>
     * Everything checked here is a property of this node alone. What is <em>not</em> here is anything
     * that needs another document: {@code MODEL.081} (resolves to a block source), {@code MODEL.062}
     * (an alias names a marker some palette defines), {@code MODEL.043} (a block's property expression
     * applies to it) and {@code MODEL.053} (a tag has members) each need a resolution pass or a game
     * registry, and reporting a guess at them from a codec would be worse than reporting them later.
     */
    private static DataResult<RawNode> validate(RawNode node) {
        Diagnostics diagnostics = new Diagnostics();

        // REF.053: the two filters are alternatives, and a node carrying both has not said which keys
        // of its target it wants.
        if (node.only().isPresent() && node.without().isPresent()) {
            diagnostics.error(Diag.DIAG_035, Diagnostics.DECODING_LOCATION);
        }

        // REF.055: a filter key that names no key of a node contributes nothing, and nothing about the
        // result says why - the marker then fails as MODEL.081, naming a completeness problem the author
        // did not have. This is the one place a key name appears as a value, and so escapes MODEL.004.
        node.only().ifPresent(keys -> filterKeys(keys, "'$only'", diagnostics));
        node.without().ifPresent(keys -> filterKeys(keys, "'$without'", diagnostics));

        // VER.016: a $ref inside a trait payload is refused until references inside traits resolve.
        // Ordered by trait id so a node carrying two of them reports the same way every run.
        node.traits().values().stream()
                .filter(Trait::holdsReference)
                .sorted(java.util.Comparator.comparing(trait -> trait.id().toString()))
                .forEach(trait -> diagnostics.error(Diag.DIAG_064, Diagnostics.DECODING_LOCATION,
                        "'" + trait.id() + "'"));

        // REF.072: a $spread element carries no other key. To change what it spreads, point somewhere
        // else - so a sibling key is a key of a thing this element is not.
        if (node.spread().isPresent()) {
            for (String key : node.presentKeysOtherThanSpread()) {
                diagnostics.error(Diag.DIAG_003, Diagnostics.DECODING_LOCATION, key,
                        "a '$spread' element");
            }
        }

        // MODEL.045. Checked on presence rather than on kind: declaring choices beside a $ref replaces
        // the referenced node's list, so an empty one is empty however the kind arrived.
        if (node.choices().filter(List::isEmpty).isPresent()) {
            diagnostics.error(Diag.DIAG_007, Diagnostics.DECODING_LOCATION);
        }

        // MODEL.051's leading '#'. The namespace half of that rule is not checked here: an unqualified
        // tag is a reference that resolves against the tag registry, and that is where every other
        // unqualified reference in the format is reported. The '#' is checkable from the text alone,
        // and it is the half that would otherwise decode cleanly and match nothing.
        if (node.tag().filter(tag -> !tag.startsWith("#")).isPresent()) {
            diagnostics.error(Diag.DIAG_012, Diagnostics.DECODING_LOCATION,
                    "'" + node.tag().orElseThrow() + "'");
        }

        // MODEL.072. Only when the kind is declared here and nothing is referenced: a socket that
        // takes its lists from a $ref has candidates this node cannot see.
        if (node.kind().equals(Optional.of(Kind.LIGHT_SOCKET)) && node.ref().isEmpty()
                && node.placements().values().stream().allMatch(List::isEmpty)) {
            diagnostics.error(Diag.DIAG_010, Diagnostics.DECODING_LOCATION);
        }

        Optional<String> error = diagnostics.asError();
        if (error.isPresent()) {
            String message = error.get();
            return DataResult.error(() -> message);
        }
        return DataResult.success(node);
    }

    /**
     * The keys {@code $only} and {@code $without} may name ({@code REF.055}).
     * <p>
     * A node's own keys and not {@link #COMMON_KEYS}: an operand is not a key a reference contributes.
     * {@code $ref} is resolved before the filter is applied, so naming it would filter something that no
     * longer exists, and `$only`/`$without` on the target are the target's business, not the referrer's.
     */
    public static final Set<String> FILTERABLE_KEYS =
            union(Set.of("kind", "traits"), Kind.allKindSpecificKeys());

    /**
     * {@code REF.055}: reports every key of a filter that is not a key of a node.
     * <p>
     * The "closest" hint is a prefix test in either direction rather than an edit distance, because the
     * typo this rule exists for is a plural: {@code trait} for {@code traits}, {@code blocks} for
     * {@code block}. A hint that fires only on the case it is sure about is worth more than one that
     * guesses at every misspelling, and the message names the whole legal set either way.
     */
    private static void filterKeys(List<String> keys, String operand, Diagnostics diagnostics) {
        for (String key : keys) {
            if (FILTERABLE_KEYS.contains(key)) {
                continue;
            }
            String closest = FILTERABLE_KEYS.stream()
                    .filter(candidate -> candidate.startsWith(key) || key.startsWith(candidate))
                    .sorted()
                    .findFirst()
                    .map(candidate -> ", and the closest is '" + candidate + "'")
                    .orElse("");
            diagnostics.error(Diag.DIAG_072, Diagnostics.DECODING_LOCATION, operand,
                    "'" + key + "'", closest);
        }
    }

    /**
     * This node and every node beneath it - a choice, a placement candidate, and their own children.
     * <p>
     * Stops at {@code traits}, because a trait payload is a {@link Trait} and stays opaque until a trait
     * registry exists. That is not only an implementation limit: {@code MODEL.031} says realising a node
     * never realises its satellites, so a node inside a trait is not an alternative of this one and does
     * not belong in a walk over this node's alternatives.
     * <p>
     * Here rather than in each caller because three of them wanted it - {@code REF.015}'s check that no
     * pointer in a definitions asset names an unqualified definition, the fixture harness's test for
     * whether a document reaches outside itself, and {@code REF.034}'s assertion that no operand
     * survived - and three hand-written recursions over the same shape is three places to forget the
     * placement lists.
     */
    public List<RawNode> selfAndDescendants() {
        List<RawNode> nodes = new ArrayList<>();
        collectInto(nodes);
        return List.copyOf(nodes);
    }

    private void collectInto(List<RawNode> nodes) {
        nodes.add(this);
        choices.ifPresent(list -> list.forEach(choice -> choice.node().collectInto(nodes)));
        placements.values().forEach(candidates ->
                candidates.forEach(candidate -> candidate.node().collectInto(nodes)));
    }

    /** Every {@code $ref} and {@code $spread} in this node's tree, as written. */
    public List<String> pointersWritten() {
        List<String> written = new ArrayList<>();
        for (RawNode node : selfAndDescendants()) {
            node.ref().ifPresent(written::add);
            node.spread().ifPresent(written::add);
        }
        return List.copyOf(written);
    }

    /**
     * The keys this node declares that are not operands, in a stable order.
     * <p>
     * Public because {@code MODEL.013} is checked twice, and the second time is not here.
     * {@link #allowedKeys} lets a node carrying {@code $ref} write any kind's keys, because its kind
     * arrives from the node it references and is not knowable at decode; once the reference is resolved
     * the kind <em>is</em> known, and the same check runs again over the merged node - which is the
     * reading {@code REF.054}'s {@code > Why} states outright: "the target's {@code kind: weighted}
     * arrives, the sibling {@code block} is declared, and MODEL.013 refuses the result".
     */
    public List<String> presentNodeKeys() {
        List<String> keys = new ArrayList<>();
        kind.ifPresent(value -> keys.add("kind"));
        block.ifPresent(value -> keys.add("block"));
        choices.ifPresent(value -> keys.add("choices"));
        tag.ifPresent(value -> keys.add("tag"));
        aliasOf.ifPresent(value -> keys.add("of"));
        placements.keySet().stream().map(Kind.Placement::key).forEach(keys::add);
        if (!traits.isEmpty()) {
            keys.add("traits");
        }
        return keys;
    }

    /** The keys this node declares besides {@code $spread}, in a stable order, for {@code REF.072}. */
    private List<String> presentKeysOtherThanSpread() {
        List<String> keys = new ArrayList<>(presentNodeKeys());
        ref.ifPresent(value -> keys.add("$ref"));
        only.ifPresent(value -> keys.add("$only"));
        without.ifPresent(value -> keys.add("$without"));
        return keys;
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return Set.copyOf(union);
    }
}

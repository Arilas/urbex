package dev.krona.urbex.format.palette;

import com.mojang.serialization.DataResult;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Answers a {@link Pointer}: which entry of which document it names, and what stands at the path
 * inside it.
 * <p>
 * Two halves, because the reference graph needs them apart. {@link #address} finds the <b>entry</b> -
 * a {@code $defs} name, a marker, or a definitions asset - which is the granularity {@code REF.031}'s
 * topological sort works at and {@code REF.032}'s cycle is named in. {@link #walk} then reads the rest
 * of the path <em>inside</em> that entry, which is what {@code $spread} needs when it names
 * {@code $super#/choices} rather than a whole node.
 * <p>
 * <b>Addressing is over the document; the value is the entry's resolved one.</b> {@code REF.042} says a
 * fragment is "an RFC 6901 JSON Pointer into that asset's decoded document", and {@code REF.044} adds
 * that it resolves "<b>after</b> its own {@code extends} chain is applied and <b>before</b> any of its
 * {@code $ref}s are". So the path locates the entry in the document as written - which is why
 * {@code #/$defs/rubble} finds what that file calls {@code rubble} even if {@code rubble} is itself a
 * reference - and the entry so located is then resolved in <em>its own</em> document's scope, because
 * {@code REF.030} leaves no reference unresolved and only that document can resolve its own names.
 */
public final class PointerResolver {

    private PointerResolver() {
    }

    /**
     * Where a diagnostic about a resolution is: the asset, the entry inside it, and the references it
     * was reached through.
     * <p>
     * This is {@code 08-errors.md} §2's message shape - {@code <asset> [marker '<m>'] [via <chain>]} -
     * as a value, so that a nested resolution can extend the chain without every method taking three
     * strings. {@code DIAG.901} is the rule: "Every diagnostic names the reference chain the failing
     * node was reached through, when it was reached through one", and {@code LOAD.051} says the same
     * about the loader. It is one placeholder by the time it reaches {@link Diag}, for the reason
     * {@link Diag}'s own javadoc records.
     *
     * @param asset what the document is called ({@code DIAG.902}, as far as this stage can meet it)
     * @param entry the entry, already phrased - {@code marker 'X'} or {@code definition 'rubble'}
     * @param via   the pointers followed to get here, outermost first
     */
    public record Site(String asset, String entry, List<String> via) {

        public Site(String asset, String entry, List<String> via) {
            this.asset = asset;
            this.entry = entry;
            this.via = List.copyOf(via);
        }

        /** A marker's entry ({@code MODEL.010}'s first position). */
        public static Site marker(ResolutionScope scope, Marker marker) {
            return new Site(scope.document().describe(), "marker " + marker, List.of());
        }

        /** A {@code $defs} entry ({@code MODEL.010}'s second position). */
        public static Site definition(ResolutionScope scope, String name) {
            return new Site(scope.document().describe(), "definition '" + name + "'", List.of());
        }

        /** A definitions asset, which is one node and has no entry name inside it. */
        public static Site wholeAsset(ResolutionScope scope) {
            return new Site(scope.document().describe(), "", List.of());
        }

        /** The same site, reached through one more pointer. */
        public Site through(String pointer) {
            List<String> chain = new ArrayList<>(via);
            chain.add(pointer);
            return new Site(asset, entry, chain);
        }

        /** The same chain, at a named position inside this entry - a choice, or a placement list. */
        public Site inside(String position) {
            return new Site(asset, entry.isEmpty() ? position : entry + " " + position, via);
        }

        /** What goes in a diagnostic's leading placeholder. */
        public String location() {
            StringBuilder text = new StringBuilder(asset);
            if (!entry.isEmpty()) {
                text.append(' ').append(entry);
            }
            if (!via.isEmpty()) {
                text.append(" via ").append(String.join(" → ", via));
            }
            return text.toString();
        }

        /** The entry's own name, as {@code DIAG.036} prints it. */
        public String entryName() {
            return entry.isEmpty() ? "this asset" : entry;
        }
    }

    /**
     * The entry a pointer names, the scope its own pointers resolve in, and the path left inside it.
     *
     * @param key   the entry's name in the reference graph - unique across documents, and what
     *              {@code DIAG.032} prints
     * @param node  the entry as written, with nothing resolved
     * @param scope the document {@code node} came from, so that a pointer inside it is expanded against
     *              that document's {@code $imports} ({@code REF.086}) and resolved against its
     *              {@code $defs} ({@code REF.011})
     * @param path  what is left of the RFC 6901 path once the entry has been located
     */
    public record Addressed(String key, RawNode node, ResolutionScope scope, List<String> path) {

        public Addressed(String key, RawNode node, ResolutionScope scope, List<String> path) {
            this.key = key;
            this.node = node;
            this.scope = scope;
            this.path = List.copyOf(path);
        }
    }

    /** What stands at a path: a node, a list, or something that is neither. */
    public sealed interface Target permits Target.Node, Target.Choices, Target.Other {

        /** A node - what {@code $ref} needs. */
        record Node(RawNode node) implements Target {
        }

        /** A list of alternatives - what {@code $spread} needs ({@code REF.070}). */
        record Choices(List<RawChoice> choices) implements Target {

            public Choices(List<RawChoice> choices) {
                this.choices = List.copyOf(choices);
            }
        }

        /**
         * Neither, with a phrase naming what it was - the slot {@code DIAG.037} calls {@code <kind>}.
         */
        record Other(String description) implements Target {
        }
    }

    /**
     * Finds the entry a pointer names ({@code REF.010} through {@code REF.013}, {@code REF.041}
     * through {@code REF.045}, {@code REF.060} through {@code REF.062}).
     *
     * @param written the pointer as the file wrote it, which the diagnostic quotes alongside the
     *                expansion ({@code REF.085})
     * @param operand the key whose value this pointer is - {@code '$ref'} or {@code '$spread'} - which
     *                {@code DIAG.030} names, because both fail in a tier the same way and only the
     *                caller knows which one asked
     */
    public static DataResult<Addressed> address(Pointer pointer, String written, String operand,
                                               ResolutionScope scope, Site site) {
        String location = site.location();
        return switch (pointer) {
            case Pointer.Local local -> local(local, written, operand, scope, location);
            case Pointer.Registry registry -> registry(registry, written, operand, scope, location);
            case Pointer.Fragment fragment -> fragment(fragment, written, scope, location);
            case Pointer.Super inherited -> inherited(inherited, written, scope, site);
        };
    }

    /**
     * The node a pointer names, or empty when it names none.
     * <p>
     * The signature the brief for this task specified, kept for callers that only need the value -
     * {@link #address} is what the resolver itself uses, because a resolution that failed has to say
     * <em>which</em> half failed ({@code REF.045}) and an {@link Optional} cannot. Reads the entry as
     * written: {@code REF.044} makes a fragment address the document rather than the resolved form, and
     * resolving what it lands on is {@link NodeResolver}'s job.
     */
    public static Optional<RawNode> resolve(Pointer pointer, ResolutionScope scope) {
        Site site = new Site(scope.document().describe(), "", List.of());
        return address(pointer, pointer.expanded(), "'$ref'", scope, site).result()
                .flatMap(addressed -> walk(addressed.node(), addressed.path()).result())
                .flatMap(target -> target instanceof Target.Node node
                        ? Optional.of(node.node())
                        : Optional.empty());
    }

    /**
     * {@code REF.011}: a name with no colon is a definition of this file's {@code $defs}.
     * <p>
     * {@code REF.012} is the reason there is no second lookup here and no fall back to the registry: "A
     * name resolves in exactly one tier, decided by the presence of a colon, and a failure in that tier
     * is not retried in the other." Its {@code > Why} says what a search order would cost - "would make
     * {@code \"rubble\"} resolve differently depending on what else happened to be loaded".
     */
    private static DataResult<Addressed> local(Pointer.Local local, String written, String operand,
                                               ResolutionScope scope, String location) {
        RawNode node = scope.document().defs().get(local.name());
        if (node == null) {
            // REF.013, and the diagnostic names the operand that failed and the tier that was searched.
            return DataResult.error(() -> Diag.DIAG_030.message(location, operand,
                    Pointer.describe(written, local), "'$defs'"));
        }
        return DataResult.success(new Addressed(scope.document().defKey(local.name()), node, scope,
                local.path()));
    }

    /** {@code REF.010} and {@code REF.041}: a name with a colon is a {@code definitions} asset. */
    private static DataResult<Addressed> registry(Pointer.Registry pointer, String written,
                                                 String operand, ResolutionScope scope,
                                                 String location) {
        Optional<DefinitionAssetDefinition> asset = scope.registry().get(pointer.asset());
        if (asset.isEmpty()) {
            return DataResult.error(() -> Diag.DIAG_030.message(location, operand,
                    Pointer.describe(written, pointer), "registry"));
        }
        return DataResult.success(definitionsEntry(pointer.asset(), asset.get(), scope, List.of()));
    }

    /** {@code REF.042}: a path into another asset's decoded document. */
    private static DataResult<Addressed> fragment(Pointer.Fragment fragment, String written,
                                                  ResolutionScope scope, String location) {
        Identifier registry = fragment.registry();
        if (registry.equals(Pointer.DEFINITIONS_REGISTRY)) {
            Optional<DefinitionAssetDefinition> asset = scope.registry().get(fragment.asset());
            return asset
                    .map(value -> DataResult.success(
                            definitionsEntry(fragment.asset(), value, scope, fragment.path())))
                    .orElseGet(() -> DataResult.error(() -> noAsset(location, written, fragment)));
        }
        if (!registry.equals(Pointer.DEFAULT_FRAGMENT_REGISTRY)) {
            return DataResult.error(() -> Diag.DIAG_034.message(location,
                    Pointer.describe(written, fragment),
                    "no asset '" + fragment.asset() + "' to reach into",
                    "A fragment pointer reaches into the 'palettes' or the 'definitions' registry,"
                            + " and '" + registry.getPath() + "' is neither."));
        }
        PaletteV2Definition palette = scope.palettes().get(fragment.asset());
        if (palette == null) {
            return DataResult.error(() -> noAsset(location, written, fragment));
        }
        return paletteEntry(fragment, written, palette, scope, location);
    }

    /**
     * The entry of a palette a fragment reaches into: a {@code $defs} name, or a marker.
     * <p>
     * Those two and nothing else, because those are the only two file-level keys of a palette whose
     * values are nodes ({@code MODEL.010}). A path into {@code version}, {@code extends} or
     * {@code $imports} names something real and not a node, and {@code REF.045}'s diagnostic is
     * required to say "which half failed" - so it says the path did, in the asset that does exist.
     */
    private static DataResult<Addressed> paletteEntry(Pointer.Fragment fragment, String written,
                                                     PaletteV2Definition palette,
                                                     ResolutionScope scope, String location) {
        List<String> path = fragment.path();
        ResolutionScope target = scope.in(ResolutionScope.Document.of(fragment.asset(), palette));
        if (path.size() >= 2 && path.get(0).equals("$defs")) {
            RawNode node = palette.defs().get(path.get(1));
            if (node != null) {
                return DataResult.success(new Addressed(target.document().defKey(path.get(1)), node,
                        target, path.subList(2, path.size())));
            }
        }
        if (path.size() >= 2 && path.get(0).equals("palette")) {
            Optional<Marker> marker = Marker.parse(path.get(1)).result();
            RawNode node = marker.flatMap(at -> palette.palette()
                    .map(entries -> entries.get(at))).orElse(null);
            if (node != null) {
                return DataResult.success(new Addressed(
                        target.document().markerKey(marker.orElseThrow()), node, target,
                        path.subList(2, path.size())));
            }
        }
        return DataResult.error(() -> noNode(location, written, fragment));
    }

    private static Addressed definitionsEntry(Identifier id, DefinitionAssetDefinition asset,
                                              ResolutionScope scope, List<String> path) {
        ResolutionScope target = scope.in(ResolutionScope.Document.of(id, asset));
        return new Addressed(id.toString(), asset.node(), target, path);
    }

    /**
     * {@code REF.060}: what this entry inherited from its {@code extends} chain.
     * <p>
     * {@code REF.062} refuses it when there is nothing to name, in either of the two cases
     * {@code DIAG.036} spells out: the file declares no {@code extends}, or it does and no ancestor
     * declares this entry. Both are the same absence to this code and different sentences to the author,
     * which is why {@link ResolutionScope.Document} carries {@code extends} at all.
     * <p>
     * The inherited node needs no graph key: it is not a named entry of any document, it is a value the
     * {@code extends} merge already produced, so it arrives resolved. {@code REF.033}'s cycle "through
     * both" is a cycle in that merge, found where the chain is built.
     */
    private static DataResult<Addressed> inherited(Pointer.Super pointer, String written,
                                                   ResolutionScope scope, Site site) {
        Optional<RawNode> value = scope.inherited();
        if (value.isEmpty()) {
            String reason = scope.document().extendsId().isPresent()
                    ? "nothing in its extends chain declares " + site.entryName()
                    : "this file declares no extends";
            return DataResult.error(() -> Diag.DIAG_036.message(site.location(), reason));
        }
        return DataResult.success(new Addressed("$" + Pointer.SUPER + " of " + site.entryName(),
                value.get(), scope, pointer.path()));
    }

    /**
     * Reads a path inside one entry ({@code REF.042}).
     * <p>
     * Stops at {@code traits}: a trait payload is opaque until a trait registry exists, and
     * {@code REF.054} means nothing needs to walk into one - "{@code $only} and {@code $without} name
     * top-level keys of the target node only", and a satellite is reached by pointing at the trait
     * field, which is a node this walk already returns.
     */
    public static DataResult<Target> walk(RawNode node, List<String> path) {
        if (path.isEmpty()) {
            return DataResult.success(new Target.Node(node));
        }
        String segment = path.get(0);
        List<String> rest = path.subList(1, path.size());
        Optional<List<RawChoice>> list = listAt(node, segment);
        if (list.isPresent()) {
            return element(list.get(), rest, segment);
        }
        Optional<String> scalar = scalarAt(node, segment);
        if (scalar.isPresent()) {
            return rest.isEmpty()
                    ? DataResult.success(new Target.Other(scalar.get()))
                    : DataResult.error(() -> "no node at '/" + segment + "/"
                            + String.join("/", rest) + "' inside it, because '" + segment
                            + "' holds a string");
        }
        if (segment.equals("traits") && !node.traits().isEmpty()) {
            return DataResult.success(new Target.Other("traits object"));
        }
        return DataResult.error(() -> "no node at '/" + segment + "' inside it");
    }

    private static DataResult<Target> element(List<RawChoice> choices, List<String> rest,
                                             String listKey) {
        if (rest.isEmpty()) {
            return DataResult.success(new Target.Choices(choices));
        }
        int index;
        try {
            index = Integer.parseInt(rest.get(0));
        } catch (NumberFormatException notAnIndex) {
            return DataResult.error(() -> "no node at '/" + listKey + "/" + rest.get(0)
                    + "', because '" + listKey + "' is a list and that is not an index into it");
        }
        if (index < 0 || index >= choices.size()) {
            return DataResult.error(() -> "no element " + index + " in '" + listKey
                    + "', which holds " + choices.size());
        }
        return walk(choices.get(index).node(), rest.subList(1, rest.size()));
    }

    private static Optional<List<RawChoice>> listAt(RawNode node, String segment) {
        if (segment.equals("choices")) {
            return node.choices();
        }
        for (Kind.Placement placement : Kind.Placement.values()) {
            if (segment.equals(placement.key())) {
                return Optional.ofNullable(node.placements().get(placement));
            }
        }
        return Optional.empty();
    }

    /**
     * A key of this node whose value is a string, named as a bare noun.
     * <p>
     * Bare, because the phrase goes in {@code DIAG.037}'s {@code <kind>} slot, whose sentence is
     * "{@code $spread} {@code <p>} names a {@code <kind>}, not a list".
     */
    private static Optional<String> scalarAt(RawNode node, String segment) {
        return switch (segment) {
            case "kind" -> node.kind().map(kind -> "kind");
            case "block" -> node.block().map(block -> "block");
            case "tag" -> node.tag().map(tag -> "tag");
            case "of" -> node.aliasOf().map(marker -> "marker");
            default -> Optional.empty();
        };
    }

    private static String noAsset(String location, String written, Pointer pointer) {
        return Diag.DIAG_034.message(location, Pointer.describe(written, pointer),
                "no asset '" + assetOf(pointer) + "'",
                "Nothing loaded registers that id; check the namespace and the file's path.");
    }

    private static String noNode(String location, String written, Pointer.Fragment fragment) {
        return Diag.DIAG_034.message(location, Pointer.describe(written, fragment),
                "no node at '/" + String.join("/", fragment.path()) + "' in '"
                        + fragment.asset() + "'",
                "The asset exists; the path does not. A palette's nodes are under '/$defs/<name>'"
                        + " and '/palette/<marker>'.");
    }

    private static String assetOf(Pointer pointer) {
        return switch (pointer) {
            case Pointer.Registry registry -> registry.asset().toString();
            case Pointer.Fragment fragment -> fragment.asset().toString();
            case Pointer.Local local -> local.name();
            case Pointer.Super ignored -> "$" + Pointer.SUPER;
        };
    }
}

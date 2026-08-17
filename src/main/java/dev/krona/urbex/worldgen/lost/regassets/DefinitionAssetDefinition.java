package dev.krona.urbex.worldgen.lost.regassets;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Versioned;
import dev.krona.urbex.format.palette.Pointer;
import dev.krona.urbex.format.palette.RawChoice;
import dev.krona.urbex.format.palette.RawNode;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An entry of the {@code definitions} registry: one node, shared by every file that points at it.
 * <p>
 * {@code REF.014}: "A definitions asset is a single node, with the same file-level keys {@code version}
 * and {@code extends}, and no {@code palette}." So the document <em>is</em> a node - its {@code kind},
 * {@code block}, {@code choices} and {@code traits} sit at the top level, beside the three file-level
 * keys this record decodes off the front. {@code REF.018} adds {@code $imports} and refuses
 * {@code $defs}: it is one node, so it has nothing to put names on, but "a shared definition is exactly
 * the thing most likely to point somewhere else".
 * <p>
 * This registry is what version 1's {@code variants} becomes ({@code 09-migration.md} §2:
 * "{@code \"variant\": \"<id>\"} → {@code { \"$ref\": \"<id>\" }} - the {@code variants} registry becomes
 * {@code definitions}"). It does not replace it: {@code variants} stays registered and untouched,
 * because every shipped pack is version 1 and {@code VER.004} says version 1 does not change.
 * {@code MODEL.044}'s closing note is the whole difference - "A named weighted node in the definitions
 * registry is what a {@code variant} was", and a definitions asset can be any of the five kinds and can
 * carry traits, which a {@code variant} could not.
 * <p>
 * <b>There is no version 1 form of this registry,</b> so {@code version} is required and must be 2 -
 * which is {@code REF.019}, refusing an absent {@code version} rather than reading it as version 1.
 * {@link Versioned#dispatch} is deliberately not used: it reads an absent {@code version} as 1 by
 * {@code VER.001}, and {@code DIAG.001}'s remedy - "or omit it for the version 1 format" - would then
 * offer an author a format this registry has never had. {@code DIAG.071} says the true thing instead.
 *
 * @param extendsId {@code extends} - one definitions asset this one builds on ({@code MERGE.001})
 * @param imports   {@code $imports} - alias name to pointer prefix ({@code REF.080}, {@code REF.018})
 * @param node      the node this asset is, exactly as written and with nothing resolved
 */
public record DefinitionAssetDefinition(Optional<Identifier> extendsId, Map<String, String> imports,
                                        RawNode node)
        implements Extendable, Versioned.Asset {

    /** This is the version this record is; there is no other. */
    public static final int FORMAT_VERSION = 2;

    /** The keys read off the front of the document, before what remains is read as a node. */
    public static final List<String> FILE_LEVEL_KEYS = List.of("version", "extends", "$imports");

    public DefinitionAssetDefinition(Optional<Identifier> extendsId, Map<String, String> imports,
                                     RawNode node) {
        this.extendsId = extendsId;
        this.imports = Map.copyOf(imports);
        this.node = node;
    }

    /**
     * The registry's codec: the three file-level keys, then everything else as one node.
     * <p>
     * Hand-rolled rather than a {@link com.mojang.serialization.codecs.RecordCodecBuilder}, because the
     * node's keys are siblings of the file-level ones rather than nested under a field - the same shape
     * {@link RawChoice} already has for a choice's {@code weight} and {@code when}, and handled the same
     * way: the file-level keys are taken off before the node sees the document, so that
     * {@code MODEL.004} does not report {@code version} as a key a node does not have.
     * <p>
     * <b>The node is read before {@code version} is checked.</b> A document carrying a version 1 key -
     * {@code blocks}, {@code variant}, {@code inherit} - is refused by name, with its replacement, which
     * is what {@code VER.010} through {@code VER.012} require and is far more use to the author than
     * being told to declare a version. A wrong {@code version} on an otherwise valid document is still
     * refused, one check later.
     */
    public static final Codec<DefinitionAssetDefinition> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<Pair<DefinitionAssetDefinition, T>> decode(DynamicOps<T> ops, T input) {
            Dynamic<T> document = new Dynamic<>(ops, input);
            Dynamic<T> nodeOnly = document;
            for (String key : FILE_LEVEL_KEYS) {
                nodeOnly = nodeOnly.remove(key);
            }
            DataResult<RawNode> node = RawNode.CODEC.parse(nodeOnly);
            if (node.error().isPresent()) {
                String message = node.error().get().message();
                return DataResult.error(() -> message);
            }
            DataResult<DefinitionAssetDefinition> asset = fields(document, node.result().orElseThrow());
            return asset.map(value -> Pair.of(value, ops.empty()));
        }

        @Override
        public <T> DataResult<T> encode(DefinitionAssetDefinition input, DynamicOps<T> ops, T prefix) {
            return input.encode(ops, prefix);
        }

        @Override
        public String toString() {
            return "a definitions asset";
        }
    };

    /**
     * The three file-level keys, and the two rules that need the document and the node together.
     * <p>
     * Both are checked here rather than inside a {@code validate} on the record, and only once the node
     * has decoded, for the reason {@link RawNode}'s {@code validatedWhenComplete} records: a diagnostic
     * derived from a value is only true if the value is the one the file wrote. {@code REF.015} reads
     * every pointer in the node against {@code $imports}, so it spans two fields and would describe a
     * document nobody wrote if either had failed.
     */
    private static <T> DataResult<DefinitionAssetDefinition> fields(Dynamic<T> document, RawNode node) {
        Optional<Dynamic<T>> version = document.get("version").result();
        if (version.isEmpty()) {
            return DataResult.error(() -> Diag.DIAG_071.message(Diagnostics.DECODING_LOCATION,
                    "declares no 'version'"));
        }
        Optional<Number> declared = version.get().asNumber().result();
        if (declared.isEmpty() || declared.get().intValue() != FORMAT_VERSION
                || declared.get().doubleValue() != declared.get().intValue()) {
            Object written = version.get().getValue();
            return DataResult.error(() -> Diag.DIAG_071.message(Diagnostics.DECODING_LOCATION,
                    "declares version " + written));
        }

        Optional<Identifier> extendsId = Optional.empty();
        Optional<Dynamic<T>> written = document.get("extends").result();
        if (written.isPresent()) {
            DataResult<Identifier> parsed = DataTools.STRICT_IDENTIFIER_CODEC.parse(written.get());
            if (parsed.error().isPresent()) {
                String message = parsed.error().get().message();
                return DataResult.error(() -> message);
            }
            extendsId = parsed.result();
        }

        Map<String, String> imports = Map.of();
        Optional<Dynamic<T>> declaredImports = document.get("$imports").result();
        if (declaredImports.isPresent()) {
            DataResult<Map<String, String>> parsed = Codec
                    .unboundedMap(Codec.STRING, Codec.STRING).parse(declaredImports.get());
            if (parsed.error().isPresent()) {
                String message = parsed.error().get().message();
                return DataResult.error(() -> message);
            }
            imports = parsed.result().orElseThrow();
        }
        // REF.082: $super is built in to every file, this registry's entries included, and an
        // $imports entry named 'super' would either silently lose to it or silently win.
        if (imports.containsKey(Pointer.SUPER)) {
            return DataResult.error(() -> Diag.DIAG_070.message(Diagnostics.DECODING_LOCATION));
        }

        DefinitionAssetDefinition asset = new DefinitionAssetDefinition(extendsId, imports, node);
        Optional<String> unqualified = asset.unqualifiedReference();
        if (unqualified.isPresent()) {
            String name = unqualified.get();
            return DataResult.error(() -> Diag.DIAG_033.message(Diagnostics.DECODING_LOCATION,
                    "'" + name + "'"));
        }
        return DataResult.success(asset);
    }

    /**
     * {@code REF.015}: the first pointer in this asset that names an unqualified definition, if any.
     * <p>
     * {@code DIAG.033}, and the rule's {@code > Why} is the whole of it: a definitions asset "has no
     * file whose {@code $defs} to resolve against, and resolving against the referring file's would make
     * a shared definition mean different things to different callers". A pointer that survives
     * {@link Pointer#parse} as a {@link Pointer.Local} is exactly such a name - the parse has already
     * expanded the asset's own {@code $imports}, so an alias standing for a qualified prefix is not
     * caught here, which is right: it resolves to an asset.
     * <p>
     * Reports the first rather than every one, unlike {@code DIAG.903}'s usual collection: this walk is
     * over pointers whose targets it cannot see, and a document carrying one unqualified name is nearly
     * always a version 1 {@code variant} chain being converted wholesale, where the second name tells
     * the author nothing the first did not.
     */
    private Optional<String> unqualifiedReference() {
        for (String written : node.pointersWritten()) {
            Optional<Pointer> parsed =
                    Pointer.parse(written, imports, Diagnostics.DECODING_LOCATION).result();
            if (parsed.orElse(null) instanceof Pointer.Local local) {
                return Optional.of(local.name());
            }
        }
        return Optional.empty();
    }

    /**
     * Writes the three file-level keys back over the node's own.
     * <p>
     * A node that is a bare block encodes to a JSON <em>string</em> by {@code MODEL.020}, and a string
     * has nowhere to put {@code version}. So a string result is expanded back into
     * {@code {"block": …}} - the one place in the format where the shorthand does not survive a round
     * trip, because the shorthand is a node and this document is a node plus three keys.
     */
    private <T> DataResult<T> encode(DynamicOps<T> ops, T prefix) {
        DataResult<T> encoded = RawNode.CODEC.encodeStart(ops, node);
        if (encoded.error().isPresent()) {
            return encoded;
        }
        T value = encoded.result().orElseThrow();
        Dynamic<T> document = ops.getStringValue(value).result()
                .map(block -> new Dynamic<>(ops, ops.createMap(
                        Map.of(ops.createString("block"), ops.createString(block)))))
                .orElseGet(() -> new Dynamic<>(ops, value));
        document = document.set("version", new Dynamic<>(ops, ops.createInt(FORMAT_VERSION)));
        if (extendsId.isPresent()) {
            document = document.set("extends",
                    new Dynamic<>(ops, ops.createString(extendsId.get().toString())));
        }
        if (!imports.isEmpty()) {
            DataResult<T> written = Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .encodeStart(ops, imports);
            if (written.error().isPresent()) {
                return written;
            }
            document = document.set("$imports", new Dynamic<>(ops, written.result().orElseThrow()));
        }
        T written = document.getValue();
        return ops.getMap(written).flatMap(fields -> ops.mergeToMap(prefix, fields));
    }

    @Override
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }
}

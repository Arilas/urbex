package dev.krona.urbex.format.palette;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.StrictKeys;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.DataTools;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A version 2 palette file, decoded and nothing more.
 * <p>
 * {@code MODEL.001} fixes the five file-level keys - {@code version}, {@code extends},
 * {@code $imports}, {@code $defs} and {@code palette} - and "no others", which is what
 * {@link StrictKeys} enforces here. {@code MODEL.003} is why {@code palette} is optional on a file
 * rather than required: it is required somewhere in the {@code extends} chain, so a file that only
 * repaints a definition its ancestors' markers reference declares none of its own
 * ({@code MERGE.006}). A chain where nothing declares one is {@code DIAG.002}, reported where the
 * chain is resolved, not here.
 * <p>
 * Nothing in this record is resolved. {@code extends} is an id and not the palette it names,
 * {@code $imports} holds pointer prefixes as text, and every {@code $ref} inside {@code $defs} and
 * {@code palette} is whatever the file wrote. That is {@code REF.030} and {@code REF.031}: resolution
 * is one topological sort over the whole reference graph, which is a pass over every decoded document
 * and cannot happen while one of them is being read.
 *
 * @param extendsId {@code extends} - the one palette this file builds on ({@code MERGE.001})
 * @param imports   {@code $imports} - alias name to pointer prefix, expanded textually
 *                  ({@code REF.081}). Held as strings because a pointer is parsed where it is
 *                  resolved, in a later stage; the brief for this task named a {@code Pointer} type
 *                  that this task does not build.
 * @param defs      {@code $defs} - definition name to node ({@code REF.001})
 * @param palette   {@code palette} - marker to node ({@code MODEL.003})
 */
public record PaletteV2Definition(Optional<Identifier> extendsId, Map<String, String> imports,
                                  Map<String, RawNode> defs,
                                  Optional<Map<Marker, RawNode>> palette)
        implements PaletteAssetDefinition {

    /** This is the version this record is; see {@code VER.002}. */
    public static final int FORMAT_VERSION = 2;

    /** The five keys {@code MODEL.001} names, and no others. */
    public static final Set<String> FILE_LEVEL_KEYS =
            Set.of("version", "extends", "$imports", "$defs", "palette");

    /**
     * {@code REF.082}: {@code $super} is a built-in alias, available in every file, and may not be
     * declared in {@code $imports}.
     * <p>
     * Refused rather than shadowed or ignored, with {@code DIAG.070}. An {@code $imports} entry named
     * {@code super} would either silently lose to the built-in - a declaration the file makes and
     * nothing honours - or silently win, and change what {@code $super} means in one file out of a pack.
     */
    private static final String RESERVED_ALIAS = "super";

    /**
     * {@code version}, which must be 2 here.
     * <p>
     * Required rather than defaulted, and checked rather than assumed. Required so that encoding a
     * round trip writes it: a document with no {@code version} is a version 1 palette by
     * {@code VER.001}, so an encoder that omitted it would emit a file that reads back as a different
     * format. Checked because this codec is one branch of {@link dev.krona.urbex.format.Versioned}'s
     * dispatch and is reachable directly - by a test, or by a caller that skipped the dispatcher - and
     * a version 2 codec that quietly accepted {@code "version": 1} would be the mis-read
     * {@code VER.003} exists to prevent, arrived at from the other direction.
     */
    private static final Codec<Integer> VERSION = Codec.INT.validate(declared ->
            declared == FORMAT_VERSION
                    ? DataResult.success(declared)
                    : DataResult.error(() -> "this is the version " + FORMAT_VERSION
                            + " palette codec and the document declares version " + declared
                            + "; decode through the palette registry's codec, which selects by version"));

    public static final Codec<PaletteV2Definition> CODEC = StrictKeys.only(
            RecordCodecBuilder.<PaletteV2Definition>create(instance -> instance.group(
                    VERSION.fieldOf("version").forGetter(PaletteV2Definition::formatVersion),
                    DataTools.STRICT_IDENTIFIER_CODEC.optionalFieldOf("extends")
                            .forGetter(PaletteV2Definition::extendsId),
                    Codec.unboundedMap(Codec.STRING, Codec.STRING)
                            .optionalFieldOf("$imports", Map.of())
                            .forGetter(PaletteV2Definition::imports),
                    Codec.unboundedMap(Codec.STRING, RawNode.CODEC)
                            .optionalFieldOf("$defs", Map.of())
                            .forGetter(PaletteV2Definition::defs),
                    Codec.unboundedMap(Marker.CODEC, RawNode.CODEC).optionalFieldOf("palette")
                            .forGetter(PaletteV2Definition::palette)
            ).apply(instance, PaletteV2Definition::new)).validate(PaletteV2Definition::validate),
            FILE_LEVEL_KEYS, "a palette file", RetiredV2Keys.TABLE);

    /**
     * The constructor the codec uses, which takes the declared {@code version} and drops it.
     * <p>
     * {@code version} is decoded rather than skipped, and written back on encode, for one reason: a
     * round trip that dropped it would produce a document with no {@code version}, which
     * {@code VER.001} reads back as a version 1 palette. The value is not stored, because
     * {@link #formatVersion()} is the only answer this type can give.
     */
    private PaletteV2Definition(int declaredVersion, Optional<Identifier> extendsId,
                                Map<String, String> imports, Map<String, RawNode> defs,
                                Optional<Map<Marker, RawNode>> palette) {
        this(extendsId, imports, defs, palette);
    }

    @Override
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    @Override
    public Optional<Identifier> getExtends() {
        return extendsId;
    }

    private static DataResult<PaletteV2Definition> validate(PaletteV2Definition definition) {
        if (definition.imports().containsKey(RESERVED_ALIAS)) {
            return DataResult.error(() -> Diag.DIAG_070.message(Diagnostics.DECODING_LOCATION));
        }
        return DataResult.success(definition);
    }
}

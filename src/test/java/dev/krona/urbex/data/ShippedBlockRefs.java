package dev.krona.urbex.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.RawNode;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Every block string the bundled datapack writes, wherever it writes it and in whichever format.
 *
 * <h2>Why this is one walk and not one per test</h2>
 *
 * <p>Two tests ask this same question for different reasons — {@link ShippedBlockIdsResolveTest} asks
 * whether each id names a block this Minecraft version has, and {@link RotatableTagCoversShippedBlocksTest}
 * asks whether each one that turns under rotation is in {@code urbex:rotatable} — and until the pack
 * became version 2 they had a copy each of the same fifteen-line search for the key names {@code block}
 * and {@code damaged}. Converting the pack made both copies cover a fraction of it, and only one copy
 * was fixed: the rotatable guard went on passing while seventy distinct block states, every {@code rail}
 * and {@code ladder} and {@code lever} among them, had become invisible to it. That is the specific
 * failure a second copy causes, so there is one walk now and both tests read it.</p>
 *
 * <h2>Why a version 2 document is not searched by key</h2>
 *
 * <p>Version 1 spells a block source two ways and version 2 spells it six: the marker's whole value can
 * <em>be</em> the string ({@code MODEL.020}), and {@code into}, {@code unlit} and every other
 * block-valued trait field is a satellite that is itself a node ({@code TRAIT.009}). Guessing at that
 * key list is how a guard goes quietly out of date — which is not a hypothetical here, it is what
 * happened. So a version 2 document is decoded and enumerated with {@link RawNode#selfAndDescendants()},
 * the format's own walk, the one {@code REF.015} and {@code REF.034} are checked with; it knows about
 * choices, placement lists and trait satellites because those rules needed it to, and a seventh
 * block-valued spelling would arrive here without an edit.</p>
 *
 * <p>What is still searched by key is everything else: version 1 files, which the pack still ships as
 * the inline palettes of six parts and buildings and as its {@code variants} registry, and the
 * registries that are not palettes at all. Those spell a block {@code block} and {@code damaged} and
 * nothing else.</p>
 *
 * <p><b>The general form of that failure is open as
 * <a href="https://github.com/Arilas/urbex/issues/220">issue #220</a>:</b> a walk that dispatches on a
 * document's format silently stops covering an asset the moment that asset's format changes, and the
 * only thing that noticed here was a reviewer disabling the version 2 branch by hand. The two guards
 * over this walk are the local fix; nothing yet makes the general case fail on its own.</p>
 */
final class ShippedBlockRefs {

    /** The bundled datapack, as both callers walk it. */
    static final Path DATA_ROOT = Path.of("src/main/resources/data");

    private ShippedBlockRefs() {
    }

    /**
     * One written block string and where it came from.
     *
     * @param version2 which of the two walks found it, so a caller can assert that neither branch has
     *                 silently stopped covering anything without re-deriving the dispatch
     */
    record Ref(String value, String file, boolean version2) {}

    /** Every block string under {@code root}, in file order. */
    static List<Ref> under(Path root) throws IOException {
        List<Ref> refs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonElement document = JsonParser.parseString(Files.readString(file));
                boolean version2 = isVersion2(document);
                collect(document, file.toString(),
                        value -> refs.add(new Ref(value, file.toString(), version2)));
            }
        }
        return List.copyOf(refs);
    }

    /**
     * Every file under {@code root} whose own document is version 2.
     *
     * <p>Separate from {@link #under} because the two answer different questions: this is which files
     * the version 2 branch is responsible for, and a file it covers may legitimately write no block at
     * all - an alias-only palette, or a partial definition carrying only traits. Counting the files
     * that produced a ref would call those uncovered.</p>
     */
    static List<String> version2Documents(Path root) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                if (isVersion2(JsonParser.parseString(Files.readString(file)))) {
                    found.add(file.toString());
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Feeds every block string in {@code element} to {@code sink}.
     *
     * <p>The version 2 branch fires on a nested document too, which is what makes an inline palette
     * that converts later covered without an edit here.</p>
     */
    private static void collect(JsonElement element, String file, Consumer<String> sink) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collect(child, file, sink));
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        if (isVersion2(element)) {
            version2Blocks(element.getAsJsonObject(), file).forEach(sink);
            return;
        }
        element.getAsJsonObject().entrySet().forEach(entry -> {
            boolean isBlockRef = ("block".equals(entry.getKey()) || "damaged".equals(entry.getKey()))
                    && entry.getValue().isJsonPrimitive();
            if (isBlockRef) {
                sink.accept(entry.getValue().getAsString());
            } else {
                collect(entry.getValue(), file, sink);
            }
        });
    }

    /** {@code VER.003}: the version is read off the raw document, before anything decodes it. */
    static boolean isVersion2(JsonElement element) {
        if (!element.isJsonObject()) {
            return false;
        }
        JsonElement version = element.getAsJsonObject().get("version");
        return version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber()
                && version.getAsInt() == PaletteV2Definition.FORMAT_VERSION;
    }

    /**
     * Every block string a version 2 palette or definitions asset writes, however it spells it.
     *
     * <p>{@code $defs} as well as {@code palette}: {@code REF.017} makes a named definition API, so an
     * unresolvable id in one is shipped whether or not this pack's own markers reach it today.</p>
     */
    private static List<String> version2Blocks(JsonObject document, String file) {
        List<RawNode> roots = new ArrayList<>();
        if (document.has("palette")) {
            DataResult<PaletteV2Definition> decoded =
                    PaletteV2Definition.CODEC.parse(JsonOps.INSTANCE, document);
            PaletteV2Definition palette = decoded.result().orElse(null);
            assertNotNull(palette, () -> file + ": "
                    + decoded.error().map(Object::toString).orElse("did not decode"));
            roots.addAll(palette.defs().values());
            palette.palette().ifPresent(markers -> roots.addAll(markers.values()));
        } else {
            DataResult<DefinitionAssetDefinition> decoded =
                    DefinitionAssetDefinition.CODEC.parse(JsonOps.INSTANCE, document);
            DefinitionAssetDefinition definition = decoded.result().orElse(null);
            assertNotNull(definition, () -> file + ": "
                    + decoded.error().map(Object::toString).orElse("did not decode"));
            roots.add(definition.node());
        }
        List<String> blocks = new ArrayList<>();
        for (RawNode root : roots) {
            root.selfAndDescendants().forEach(node -> node.block().ifPresent(blocks::add));
        }
        return blocks;
    }
}

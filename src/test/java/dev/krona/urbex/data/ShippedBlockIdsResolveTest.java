package dev.krona.urbex.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.RawNode;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every block id in the bundled datapack must name a block this Minecraft version actually has.
 * <p>
 * Nothing checked this, and the failure mode is silent rather than loud: {@code Tools.stringToState}
 * ends by returning {@code minecraft:air} for an id it cannot resolve, and version 2 reaches the same
 * outcome deliberately - {@code MODEL.042} makes an absent id an {@code ACCEPT}, because a pack naming
 * optional cross-mod content must not refuse the world. Either way an id that a Minecraft version
 * renames turns into air everywhere it is used, with no exception and one warning in the log that
 * nobody reads while playing. (It used to reach that outcome by accident, through
 * {@code BuiltInRegistries.BLOCK.getValue} handing back a defaulted registry's default; it is
 * written out as a return now, and pinned by {@code BlockResolutionTest}, so #91 has one line to
 * change.)
 * <p>
 * Two shipped entries were in exactly that state when this test was written, both surfaced by
 * Task 5c making the load-time validation actually run: {@code minecraft:chain} (renamed
 * {@code minecraft:iron_chain} in 26.x), which made the whole {@code urbex:chains} decoration
 * invisible, and {@code minecraft:red_sandstone@2}, a 1.12 {@code name@meta} string predating
 * flattening that is not even a legal {@link Identifier} - {@code Identifier.parse} threw on it,
 * and because that happens inside {@code Palette}'s constructor it took the whole world load with
 * it as soon as anything resolved that palette.
 * <p>
 * The air fallback itself is left alone here; see the Task 5c report. This test is the guard that
 * does not depend on it.
 *
 * <h2>Why the walk is two walks since the pack became version 2</h2>
 *
 * <p>The version 1 walk was a search for the key names {@code block} and {@code damaged}, and version 2
 * spells a block source four more ways: the marker's whole value can <em>be</em> the string
 * ({@code MODEL.020}), and {@code into}, {@code unlit} and any other block-valued trait field are
 * satellites that are themselves nodes ({@code TRAIT.009}). Guessing at that key list is how a guard
 * goes quietly out of date, so a version 2 document is not searched by key at all: it is decoded, and
 * every node in it is enumerated by {@link RawNode#selfAndDescendants()} - the format's own walk, the
 * one {@code REF.015} and {@code REF.034} are checked with, which knows about choices, placement lists
 * and trait satellites because those rules needed it to.</p>
 *
 * <p>What is still searched by key is everything else: version 1 files, which the pack still ships as
 * the inline palettes of six parts and buildings and as its {@code variants} registry, and the
 * registries that are not palettes at all. Those spell a block {@code block} and {@code damaged} and
 * nothing else.</p>
 */
class ShippedBlockIdsResolveTest {

    private static final Path ROOT = Path.of("src/main/resources/data");

    private record Ref(String value, String file) {}

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyShippedBlockIdNamesARealBlock() throws IOException {
        List<Ref> refs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                collect(JsonParser.parseString(Files.readString(file)), file.toString(),
                        value -> refs.add(new Ref(value, file.toString())));
            }
        }
        assertFalse(refs.isEmpty(), "found no block ids at all - the walk or the key names are wrong");

        List<String> problems = new ArrayList<>();
        for (Ref ref : refs) {
            // Block-state properties are parsed separately by Tools.stringToState and by
            // BlockStrings.resolve; only the id is under test here.
            String id = ref.value().contains("[")
                    ? ref.value().substring(0, ref.value().indexOf('['))
                    : ref.value();
            try {
                if (!BuiltInRegistries.BLOCK.containsKey(Identifier.parse(id))) {
                    problems.add(ref.file() + ": '" + ref.value() + "' is not a block in this "
                            + "version - it would silently generate as air");
                }
            } catch (Exception e) {
                problems.add(ref.file() + ": '" + ref.value() + "' is not a valid block id: " + e.getMessage());
            }
        }
        assertTrue(problems.isEmpty(),
                () -> problems.size() + " unresolvable block id(s):\n" + String.join("\n", problems));
    }

    /**
     * The bundled pack reaches this test through both of its walks, which is worth asserting because
     * one of them silently covering nothing is exactly how this guard would stop guarding.
     *
     * <p>The counts are the shape of the pack rather than a second copy of it: thirty version 2
     * palettes and thirteen definitions on one side, and on the other the six parts and buildings whose
     * inline palettes are still version 1 ({@code VER.006} allows the mix) together with the twelve
     * {@code variants} the version 1 side of the pack still reads.</p>
     */
    @Test
    void bothWalksReachTheFilesTheyAreFor() throws IOException {
        List<String> version2 = new ArrayList<>();
        List<String> version1WithBlocks = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonElement document = JsonParser.parseString(Files.readString(file));
                if (isVersion2(document)) {
                    version2.add(file.getFileName().toString());
                } else {
                    List<String> found = new ArrayList<>();
                    collect(document, file.toString(), found::add);
                    if (!found.isEmpty()) {
                        version1WithBlocks.add(file.getFileName().toString());
                    }
                }
            }
        }
        assertEquals(43, version2.size(),
                () -> "thirty palettes and thirteen definitions assets are written in version 2, and "
                        + "the version 2 walk reached " + version2.size() + ": " + version2);
        assertEquals(18, version1WithBlocks.size(),
                () -> "twelve variants and the six parts and buildings with an inline version 1 "
                        + "palette still spell a block by key, and the key walk reached "
                        + version1WithBlocks.size() + ": " + version1WithBlocks);
    }

    /**
     * Feeds every block string in {@code element} to {@code sink}.
     *
     * <p>A version 2 document is handed to the format's own node walk; anything else is searched for
     * version 1's two block-valued keys, at any depth. The version 2 branch fires on a nested document
     * too, which is what makes an inline palette that converts later covered without an edit here.</p>
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
    private static boolean isVersion2(JsonElement element) {
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
            PaletteV2Definition palette = decoded.result().orElseThrow(() -> new AssertionError(
                    file + ": " + decoded.error().map(Object::toString).orElse("did not decode")));
            roots.addAll(palette.defs().values());
            palette.palette().ifPresent(markers -> roots.addAll(markers.values()));
        } else {
            DataResult<DefinitionAssetDefinition> decoded =
                    DefinitionAssetDefinition.CODEC.parse(JsonOps.INSTANCE, document);
            roots.add(decoded.result().orElseThrow(() -> new AssertionError(
                    file + ": " + decoded.error().map(Object::toString).orElse("did not decode")))
                    .node());
        }
        List<String> blocks = new ArrayList<>();
        for (RawNode root : roots) {
            root.selfAndDescendants().forEach(node -> node.block().ifPresent(blocks::add));
        }
        return blocks;
    }
}

package dev.krona.urbex.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code block} and {@code damaged} id in the bundled datapack must name a block this
 * Minecraft version actually has.
 * <p>
 * Nothing checked this, and the failure mode is silent rather than loud: {@code Tools.stringToState}
 * ends at {@code BuiltInRegistries.BLOCK.getValue(id)}, and that returns the block registry's
 * <em>default</em> value - {@code minecraft:air} - for an id it does not know, so the
 * {@code value == null} guard below it never fires. An id that a Minecraft version renames
 * therefore turns into air everywhere it is used, with no exception and no log line.
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
                collect(JsonParser.parseString(Files.readString(file)),
                        value -> refs.add(new Ref(value, file.toString())));
            }
        }
        assertFalse(refs.isEmpty(), "found no block ids at all - the walk or the key names are wrong");

        List<String> problems = new ArrayList<>();
        for (Ref ref : refs) {
            // Block-state properties are parsed separately by Tools.stringToState; only the id is
            // under test here.
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

    /** Feeds every {@code block} / {@code damaged} string value, at any depth, to {@code sink}. */
    private static void collect(JsonElement element, Consumer<String> sink) {
        if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(entry -> {
                boolean isBlockRef = ("block".equals(entry.getKey()) || "damaged".equals(entry.getKey()))
                        && entry.getValue().isJsonPrimitive();
                if (isBlockRef) {
                    sink.accept(entry.getValue().getAsString());
                } else {
                    collect(entry.getValue(), sink);
                }
            });
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collect(child, sink));
        }
    }
}

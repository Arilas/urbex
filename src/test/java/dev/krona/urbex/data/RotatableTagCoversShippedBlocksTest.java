package dev.krona.urbex.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.krona.urbex.varia.Tools;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every block the bundled datapack places that <em>changes under rotation</em> must be in
 * {@code urbex:rotatable}, because that tag is the whole of what gets rotated.
 * <p>
 * {@code CityGenerator.transformBlockState} applies the part's mirror/rotation to a block only if
 * it is in the world style's rotatable tag ({@code urbex:rotatable} unless a world style names its
 * own). The tag used to contain {@code #minecraft:stairs} and nothing else, while the shipped
 * palettes place ladders, an iron trapdoor, doors, chests, barrels, furnaces, levers and wall
 * torches - so every rotated copy of the parts holding them came out facing the direction the
 * author wrote for the <em>unrotated</em> part. A ladder or wall torch that survives that is
 * attached to nothing (issue #117).
 * <p>
 * "Changes under rotation" is asked of the block itself rather than listed by hand: a state whose
 * {@code rotate}/{@code mirror} returns something different is exactly a state that would have come
 * out wrong. So a new palette entry for a directional block fails this test rather than shipping a
 * quiet defect, and a block that vanilla does not rotate is not required to be in the tag.
 * <p>
 * Tag references inside the tag are expanded from the vanilla data on the classpath, so the test
 * checks what the game will actually resolve, not the literal file contents.
 */
class RotatableTagCoversShippedBlocksTest {

    private static final Path DATA_ROOT = Path.of("src/main/resources/data");
    private static final Path ROTATABLE = Path.of("src/main/resources/data/urbex/tags/block/rotatable.json");

    private record Ref(String value, String file) {}

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyRotationSensitiveShippedBlockIsRotatable() throws IOException {
        Set<Identifier> rotatable = expand(ROTATABLE, Files.readString(ROTATABLE), new LinkedHashSet<>());
        assertFalse(rotatable.isEmpty(), "the rotatable tag expanded to nothing");

        List<Ref> refs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DATA_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                collectBlockRefs(JsonParser.parseString(Files.readString(file)),
                        value -> refs.add(new Ref(value, file.toString())));
            }
        }
        assertFalse(refs.isEmpty(), "found no block ids at all - the walk or the key names are wrong");

        Set<String> problems = new TreeSet<>();
        for (Ref ref : refs) {
            BlockState state = Tools.stringToState(ref.value(), ref.file());
            boolean turns = state.rotate(Rotation.CLOCKWISE_90) != state
                    || state.mirror(Mirror.FRONT_BACK) != state;
            Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (turns && !rotatable.contains(id)) {
                problems.add(id + " (e.g. '" + ref.value() + "' in " + ref.file() + ")");
            }
        }
        assertTrue(problems.isEmpty(), () -> problems.size() + " shipped block(s) change under "
                + "rotation but are not in urbex:rotatable, so rotated parts place them facing the "
                + "way the unrotated part was authored:\n" + String.join("\n", problems));
    }

    @Test
    void everyEntryInTheTagNamesSomethingThatExists() throws IOException {
        // A tag file that names a missing block or a missing tag is not a no-op: vanilla fails the
        // whole tag, so one stale id would silently take rotation away from every block in it.
        Set<Identifier> expanded = expand(ROTATABLE, Files.readString(ROTATABLE), new LinkedHashSet<>());
        List<String> unknown = expanded.stream()
                .filter(id -> !BuiltInRegistries.BLOCK.containsKey(id))
                .map(Identifier::toString)
                .toList();
        assertTrue(unknown.isEmpty(), () -> "rotatable tag names blocks that do not exist: " + unknown);
    }

    /**
     * Resolves a block tag file to the block ids it covers, following {@code #tag} references into
     * the vanilla tag data on the classpath. {@code seen} guards against a reference cycle.
     */
    private static Set<Identifier> expand(Object source, String json, Set<Object> seen) throws IOException {
        Set<Identifier> ids = new LinkedHashSet<>();
        if (!seen.add(source)) {
            return ids;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (JsonElement value : root.getAsJsonArray("values")) {
            // The {"id": .., "required": false} form is not used by anything here; a plain string is
            // all the shipped tag writes, and an object would be a silent miss, so fail on it.
            String entry = value.getAsString();
            if (entry.startsWith("#")) {
                Identifier tag = Identifier.parse(entry.substring(1));
                String path = "/data/" + tag.getNamespace() + "/tags/block/" + tag.getPath() + ".json";
                try (InputStream in = RotatableTagCoversShippedBlocksTest.class.getResourceAsStream(path)) {
                    assertTrue(in != null, "the tag " + entry + " named by rotatable.json does not "
                            + "exist in this Minecraft version (looked for " + path + ")");
                    ids.addAll(expand(path, new String(in.readAllBytes(), StandardCharsets.UTF_8), seen));
                }
            } else {
                ids.add(Identifier.parse(entry));
            }
        }
        return ids;
    }

    /** Feeds every {@code block} / {@code damaged} string value, at any depth, to {@code sink}. */
    private static void collectBlockRefs(JsonElement element, Consumer<String> sink) {
        if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(entry -> {
                boolean isBlockRef = ("block".equals(entry.getKey()) || "damaged".equals(entry.getKey()))
                        && entry.getValue().isJsonPrimitive();
                if (isBlockRef) {
                    sink.accept(entry.getValue().getAsString());
                } else {
                    collectBlockRefs(entry.getValue(), sink);
                }
            });
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectBlockRefs(child, sink));
        }
    }
}

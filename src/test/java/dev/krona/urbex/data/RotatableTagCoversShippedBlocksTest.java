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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <p>
 * <b>Which strings this asks about is {@link ShippedBlockRefs}, shared with
 * {@link ShippedBlockIdsResolveTest} rather than copied.</b> It used to be a copy, keyed on version 1's
 * {@code block} and {@code damaged}, and converting the bundled pack to version 2 disabled it: a marker
 * written as {@code "k": "minecraft:rail[shape=north_south]"} has no {@code block} key, so the walk
 * found 200 refs where it had found 333, and seventy distinct block states - every {@code rail},
 * {@code powered_rail}, {@code ladder}, {@code lever}, {@code iron_trapdoor}, {@code iron_door},
 * {@code barrel}, {@code oak_fence} and most stairs - stopped being asked about at all. It kept
 * passing, because the tag was complete; the next palette entry for a directional block would have
 * shipped issue #117 again with nothing to catch it.
 */
class RotatableTagCoversShippedBlocksTest {

    private static final Path ROTATABLE = Path.of("src/main/resources/data/urbex/tags/block/rotatable.json");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyRotationSensitiveShippedBlockIsRotatable() throws IOException {
        Set<Identifier> rotatable = expand(ROTATABLE, Files.readString(ROTATABLE), new LinkedHashSet<>());
        assertFalse(rotatable.isEmpty(), "the rotatable tag expanded to nothing");

        List<ShippedBlockRefs.Ref> refs = ShippedBlockRefs.under(ShippedBlockRefs.DATA_ROOT);
        assertFalse(refs.isEmpty(), "found no block ids at all - the walk or the key names are wrong");

        Set<String> problems = new TreeSet<>();
        for (ShippedBlockRefs.Ref ref : refs) {
            BlockState state = Tools.stringToState(ref.value(), BuiltInRegistries.BLOCK, ref.file());
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

        // And the walk reached the spelling this test lost when it kept its own copy of it. A complete
        // tag makes a disabled guard indistinguishable from a working one on the assertion above -
        // every block it examined was in the tag either way - so what has to be asserted is that it
        // examines the markers version 2's string shorthand hid. rails.json is the sharpest instance:
        // twelve markers, every one written as a bare string with no 'block' key, and every one a rail
        // that turns under rotation. A walk keyed on version 1's names finds none of them, and finds a
        // rotation-sensitive block elsewhere in palettes/ anyway - oilrig's redstone wall torch still
        // has a 'block' key - so a weaker assertion than "this file, all twelve" passes on the bug.
        List<String> rails = refs.stream()
                .filter(ref -> ref.file().endsWith("rails.json"))
                .map(ShippedBlockRefs.Ref::value)
                .toList();
        assertEquals(12, rails.size(),
                () -> "palettes/rails.json writes twelve markers, all as bare strings, and the walk saw "
                        + rails.size() + " of them. A walk keyed on version 1's 'block' and 'damaged' "
                        + "sees zero: it finds 200 block strings across the pack where the shared walk "
                        + "finds 337, with every rail, ladder, lever, iron_trapdoor, iron_door, barrel "
                        + "and oak_fence invisible");
        rails.forEach(value -> {
            BlockState state = Tools.stringToState(value, BuiltInRegistries.BLOCK, "palettes/rails.json");
            assertTrue(state.rotate(Rotation.CLOCKWISE_90) != state,
                    () -> "and every one of them turns, which is why this file is the instance to "
                            + "assert on: " + value);
        });
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
}

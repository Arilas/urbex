package dev.krona.urbex.format.palette;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.krona.urbex.format.Diag;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.Versioned;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which format version a palette document is read as, and when that is decided.
 * <p>
 * The ordering is the whole subject. {@code VER.003} makes version selection precede decoding as a
 * rule, not as an implementation note, because getting it the other way round is silent: version 1
 * ignores keys it does not know and always will ({@code VER.004}), so a version 2 document handed to the
 * version 1 codec decodes successfully into a palette with no entries. Nothing fails, nothing warns, and
 * the pack generates something its author did not write - which is the exact failure
 * {@code docs/format/README.md} §1 exists to stop repeating.
 */
class VersionDispatchTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** {@code VER.001}: a palette file with no {@code version} is a version 1 palette. */
    @Test
    @Rule("VER.001")
    void aFileWithNoVersionIsReadAsVersionOne() {
        PaletteAssetDefinition decoded = decoded("""
                { "palette": [ { "char": "X", "block": "minecraft:stone_bricks" } ] }
                """);
        assertEquals(1, decoded.formatVersion());
        PaletteDefinition version1 = assertInstanceOf(PaletteDefinition.class, decoded);
        assertEquals(1, version1.getPaletteEntries().size());
        assertEquals("minecraft:stone_bricks", version1.getPaletteEntries().getFirst().getBlock());
    }

    /** {@code VER.002}: {@code "version": 2} selects this specification in full. */
    @Test
    @Rule("VER.002")
    void versionTwoSelectsTheVersionTwoRulesInFull() {
        PaletteAssetDefinition decoded = decoded("""
                { "version": 2, "palette": { "X": "minecraft:stone_bricks" } }
                """);
        assertEquals(2, decoded.formatVersion());
        PaletteV2Definition version2 = assertInstanceOf(PaletteV2Definition.class, decoded);
        assertEquals(RawNode.ofBlock("minecraft:stone_bricks"),
                version2.palette().orElseThrow().get(new Marker('X')));
    }

    /**
     * {@code VER.003}: selection happens by inspecting the raw document, so a version 2 file is never
     * first decoded by the version 1 codec.
     * <p>
     * The document below is a version 2 palette that keeps every marker in {@code $defs} and declares no
     * {@code palette} of its own - a perfectly ordinary shape once {@code MERGE.006} is used, since
     * repainting a definition repaints every marker that references it. Handed to the version 1 codec it
     * <em>succeeds</em>: {@code version} and {@code $defs} are keys version 1 does not know, so they are
     * discarded, and what is left decodes cleanly to a palette with no entries at all. Nothing fails and
     * nothing warns. That is what reading the version first prevents, and it is asserted here by doing
     * the wrong thing on purpose and showing that it is quiet.
     */
    @Test
    @Rule("VER.003")
    void aVersionTwoDocumentIsNeverHandedToTheVersionOneCodec() {
        String document = """
                { "version": 2, "extends": "urbex:common",
                  "$defs": { "rubble": "minecraft:cobblestone" } }
                """;

        PaletteAssetDefinition throughDispatcher = decoded(document);
        assertEquals(2, throughDispatcher.formatVersion());
        assertEquals(RawNode.ofBlock("minecraft:cobblestone"),
                assertInstanceOf(PaletteV2Definition.class, throughDispatcher).defs().get("rubble"));

        DataResult<PaletteDefinition> throughVersion1 =
                PaletteDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(document));
        assertTrue(throughVersion1.result().isPresent(),
                "this is what the ordering protects against: version 1 does not refuse it");
        assertNull(throughVersion1.result().orElseThrow().getPaletteEntries(),
                "it decodes to a palette with no entries and no diagnostic - the whole file is gone");
    }

    /**
     * {@code VER.004}: version 1 does not become stricter. {@code MODEL.004} applies to version 2 files
     * only.
     * <p>
     * A {@code MUST NOT} rule, proved the way a {@code MUST NOT} is: exercise the situation and assert
     * the behaviour does not occur. Making version 1 refuse unknown keys would break packs
     * retroactively, which this project has never done - strictness is a reason to migrate, not a
     * penalty for not having.
     */
    @Test
    @Rule("VER.004")
    void versionOneStillIgnoresAnUnknownKeyRatherThanRefusingIt() {
        PaletteAssetDefinition decoded = decoded("""
                { "palette": [ { "char": "X", "block": "minecraft:stone_bricks",
                                 "damagd": "minecraft:iron_bars" } ] }
                """);
        assertEquals(1, decoded.formatVersion());
        assertEquals(1, assertInstanceOf(PaletteDefinition.class, decoded)
                .getPaletteEntries().size());
    }

    /**
     * {@code MODEL.002}: a version other than 1 or 2 is refused - including a {@code version} that is
     * not a number at all.
     * <p>
     * {@code "version": "2"} and {@code "version": 2.5} are the cases worth pinning. Read with a default
     * the first would fall back to 1, hand the document to the version 1 codec, and lose every version 2
     * key in it silently; truncated, the second would be accepted as 2. Both are the failure
     * {@code VER.003} is about, arrived at through a plausible typo rather than through the wrong
     * ordering.
     * <p>
     * {@code "version": null} is deliberately not in this list. A JSON null field is an absent field
     * throughout DFU, and MODEL.002 gives absent its own reading - version 1 - so refusing it here would
     * be a rule this specification does not state.
     */
    @Test
    @Rule("MODEL.002")
    void aVersionThatIsNotOneOrTwoIsRefusedAndSoIsOneThatIsNotANumber() {
        for (String version : new String[]{"3", "0", "-1", "\"2\"", "2.5", "true", "[2]"}) {
            String json = "{ \"version\": " + version
                    + ", \"palette\": { \"X\": \"minecraft:stone\" } }";
            DataResult<PaletteAssetDefinition> result = decode(json);
            assertTrue(result.error().isPresent(), () -> "expected DIAG.001 for " + json + ", got "
                    + result);
            assertTrue(Diag.DIAG_001.matches(result.error().orElseThrow().message()),
                    () -> result.error().orElseThrow().message());
        }
    }

    /**
     * {@code VER.014}: an inline palette declaring a version other than 1 is refused by name.
     * <p>
     * The alternative was the one thing this format exists to remove. Nothing reads an inline version 2
     * palette yet - the dispatcher lives on the registry - so leaving the field on the version 1 codec
     * would have had it discard {@code version} and every version 2 key beside it, and load a part whose
     * palette maps nothing. The part would have generated, as air.
     * <p>
     * This rule is marked {@code [DEPRECATED → MERGE.011]} and is retired when an inline palette is read
     * by the version it declares; the test goes with it. {@code "version": 1} is accepted, because
     * version 1 is exactly what the inline path reads.
     */
    @Test
    @Rule("VER.014")
    void anInlinePaletteDeclaringVersionTwoIsRefusedRatherThanReadAsVersionOne() {
        String part = """
                { "xsize": 16, "zsize": 16,
                  "palette": { "version": 2, "palette": { "b": "minecraft:grass_block" } },
                  "slices": [] }
                """;
        DataResult<BuildingPartDefinition> refused = BuildingPartDefinition.CODEC.parse(
                JsonOps.INSTANCE, JsonParser.parseString(part));
        assertTrue(refused.error().isPresent(),
                () -> "a silently mis-read inline palette is the failure this refuses; got " + refused);
        assertTrue(Diag.DIAG_062.matches(refused.error().orElseThrow().message()),
                refused.error().orElseThrow().message());

        DataResult<BuildingPartDefinition> version1 = BuildingPartDefinition.CODEC.parse(
                JsonOps.INSTANCE, JsonParser.parseString("""
                { "xsize": 16, "zsize": 16,
                  "palette": { "version": 1, "palette": [ { "char": "b", "block": "minecraft:grass_block" } ] },
                  "slices": [] }
                """));
        assertTrue(version1.result().isPresent(),
                () -> "version 1 is what the inline path reads, so declaring it is true; got " + version1);
    }

    /**
     * {@code VER.040}: a registry adopting version 2 follows {@code VER.001}-{@code VER.004} unchanged,
     * which is only true if the mechanism that implements them is not palette-specific.
     * <p>
     * So this drives {@link Versioned#dispatch} with a type that has nothing to do with palettes:
     * an absent {@code version} selects 1, a declared one selects its branch, an unknown one is
     * {@code DIAG.001}, and a round trip through the dispatcher writes the version back - without which
     * an encoded version 2 document would read back as version 1.
     */
    @Test
    @Rule("VER.040")
    void theVersionMechanismIsRegistryAgnostic() {
        Codec<Thing> dispatcher = Versioned.dispatch("thing", Map.of(1, Thing.V1, 2, Thing.V2));

        assertEquals(new Thing(1, "a"), parse(dispatcher, "{ \"name\": \"a\" }"));
        assertEquals(new Thing(2, "b"), parse(dispatcher, "{ \"version\": 2, \"name\": \"b\" }"));

        DataResult<Thing> unknown = dispatcher.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{ \"version\": 7, \"name\": \"c\" }"));
        assertTrue(unknown.error().isPresent());
        assertTrue(Diag.DIAG_001.matches(unknown.error().orElseThrow().message()),
                unknown.error().orElseThrow().message());
        assertTrue(unknown.error().orElseThrow().message().contains("thing"),
                "the message names what was being decoded");

        JsonElement encoded = dispatcher.encodeStart(JsonOps.INSTANCE, new Thing(2, "b"))
                .getOrThrow();
        assertEquals(2, encoded.getAsJsonObject().get("version").getAsInt());
        assertEquals(new Thing(2, "b"), parse(dispatcher, encoded.toString()));
    }

    private static Thing parse(Codec<Thing> codec, String json) {
        return codec.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    /** A two-version type with no palette in it, for {@code VER.040}. */
    private record Thing(int formatVersion, String name) implements Versioned.Asset {

        static final Codec<Thing> V1 = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(Thing::name)
        ).apply(instance, name -> new Thing(1, name)));

        static final Codec<Thing> V2 = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("version").forGetter(Thing::formatVersion),
                Codec.STRING.fieldOf("name").forGetter(Thing::name)
        ).apply(instance, Thing::new));
    }

    private static DataResult<PaletteAssetDefinition> decode(String json) {
        return PaletteAssetDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static PaletteAssetDefinition decoded(String json) {
        DataResult<PaletteAssetDefinition> result = decode(json);
        assertTrue(result.result().isPresent(), () -> "expected a clean decode, got " + result);
        return result.result().orElseThrow();
    }
}

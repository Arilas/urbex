package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.DefinitionIndex;
import dev.krona.urbex.format.palette.Exclusion;
import dev.krona.urbex.format.palette.NodeResolver;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.TraitContext;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the bundled pack's two lighting palettes say, read out of the version 2 files that now carry
 * them.
 *
 * <h2>Why this test still exists after the conversion</h2>
 *
 * <p>It is the only test that asserts anything about the <em>shipped</em> pack's lighting. Everything
 * else about sockets is a fixture: {@code V2SocketsTest} compiles documents written in the test, and
 * {@code V2PackGoldenTest} pins a pack built to exercise constructs the bundled one does not have.
 * Neither would notice a bundled candidate being given a redstone torch, an unlit stand-in that emits
 * light, or a placement list that lost a candidate — which is what the assertions below are for.</p>
 *
 * <h2>What changed when the pack became version 2</h2>
 *
 * <p>The version 1 test read {@code PaletteEntry}/{@code LightSourceSettings} records straight off the
 * decoded file and compared them to expected records. Version 2 has no such record to compare: a
 * {@code light_socket} is a node whose candidates are {@link LightPool.Candidate}s only once the file
 * has been resolved and compiled. So the subject moved one stage later — this asks the compiled pool
 * what generation asks it — and the numbers moved with it. A placement list is apportioned to 128
 * slots ({@code WEIGHT.043}), so the file's {@code 6, 3, 1} reaches the placer as {@code 77, 38, 13};
 * both are asserted, the authored weights off the file and the slot counts off the pool, because a
 * test that only knew one of them could not tell an edit to the file from a change to the
 * apportionment.</p>
 */
class CommonPaletteLightingTest {

    /** The bundled pack root: the directory holding {@code palettes/} and {@code definitions/}. */
    private static final Path PACK = Path.of("src/main/resources/data/urbex/urbex");

    private static final Path COMMON = PACK.resolve("palettes/common.json");
    private static final Path OILRIG = PACK.resolve("palettes/oilrig.json");

    /** Any seed; every assertion here is about the pool, not about which candidate a position draws. */
    private static final long SEED = 20260818L;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Rule("MODEL.071")
    @Rule("WEIGHT.043")
    @Test
    void theCommonPaletteCompilesExactSocketsWithoutRedstoneTorches() {
        CompiledPalette common = compile(COMMON);
        LightPool torchPool = poolOf(common, 'T');
        LightPool freePool = poolOf(common, 'h');

        // The authored weights, read off the file, and the slot counts they are apportioned to. A
        // placement list is a weighted list addressed by position like any other (WEIGHT.043), so the
        // second is what OptionalLightPlacer draws against and the first is what an author edits.
        assertEquals(List.of(6, 3, 1), authoredWeights(COMMON, 'T', "floor"));
        assertEquals(List.of(8, 2), authoredWeights(COMMON, 'T', "wall"));
        assertEquals(List.of(8, 2), authoredWeights(COMMON, 'T', "ceiling"));
        assertEquals(List.of(6, 2, 1, 1), authoredWeights(COMMON, 'h', "free"));
        assertEquals(List.of(77, 38, 13, 102, 26, 102, 26), slotCounts(torchPool));
        assertEquals(List.of(77, 25, 13, 13), slotCounts(freePool));

        // The four placement lists in Placement order, which is the order allCandidates flattens them
        // in and therefore the order the slot counts above are in.
        assertEquals(List.of(Blocks.LANTERN, Blocks.TORCH, Blocks.END_ROD,
                        Blocks.WALL_TORCH, Blocks.END_ROD,
                        Blocks.LANTERN, Blocks.END_ROD), blocksOf(torchPool));
        assertEquals(Set.of(Blocks.LANTERN, Blocks.TORCH, Blocks.END_ROD),
                blocksIn(torchPool, LightPool.Placement.FLOOR));
        assertEquals(Set.of(Blocks.WALL_TORCH, Blocks.END_ROD),
                blocksIn(torchPool, LightPool.Placement.WALL));
        assertEquals(Set.of(Blocks.LANTERN, Blocks.END_ROD),
                blocksIn(torchPool, LightPool.Placement.CEILING));
        assertEquals(List.of(Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.SHROOMLIGHT,
                        Blocks.OCHRE_FROGLIGHT), blocksOf(freePool));
        assertFalse(freePool.hasCandidates(LightPool.Placement.FLOOR),
                "'h' declares 'free' alone, so the other three placement lists stay empty");

        // The marker beside the sockets that places a redstone torch is a plain block and not a light
        // at all: it is decoration, and OptionalLightPlacer must never be given it.
        CompiledPalette.Placed redstoneTorch = common.placedAt('g', SEED, 0, 64, 0);
        assertNotNull(redstoneTorch);
        assertEquals(Blocks.REDSTONE_TORCH, redstoneTorch.state().getBlock());
        // Null Info rather than an Info carrying a null light: a version 2 marker with no traits has
        // nothing to carry, and both spellings mean "generation is handed no light for this marker".
        assertNull(redstoneTorch.info());

        // The two fixtures that have an inert stand-in keep it, and it emits nothing: a torch that is
        // off is a spent candle, and a lantern that is off is the chain it hung from.
        assertEquals(Blocks.CANDLE, unlitOf(torchPool, Blocks.TORCH).getBlock());
        assertEquals(Blocks.IRON_CHAIN, unlitOf(torchPool, Blocks.LANTERN).getBlock());
        torchPool.allCandidates().stream().map(LightPool.Candidate::unlit).filter(Objects::nonNull)
                .forEach(unlit -> assertEquals(0, unlit.getLightEmission(),
                        () -> "An unlit replacement emits light: " + unlit));

        Stream.concat(torchPool.allCandidates().stream(), freePool.allCandidates().stream())
                .forEach(candidate -> {
                    assertTrue(candidate.state().getLightEmission() >= 14,
                            () -> "Bundled candidate emits too little light: " + candidate.state());
                    assertFalse(candidate.state().is(Blocks.REDSTONE_TORCH));
                    assertFalse(candidate.state().is(Blocks.REDSTONE_WALL_TORCH));
                });
    }

    /**
     * {@code TRAIT.055}, as the shipped pack now writes it.
     *
     * <p>Version 1 had no way to say "this socket's candidates leave nothing behind" other than by
     * saying nothing, and {@code LightSource.unlitFor} carried the silence forward to placement time.
     * Version 2 makes the socket's own {@code urbex:light} a trait its candidates inherit
     * ({@code TRAIT.005}), so the two sockets state their stand-in instead of defaulting into it — and
     * a candidate that names its own replaces it whole ({@code TRAIT.006}), which is the whole of
     * {@code TRAIT.055}.</p>
     *
     * <p>Both halves are asserted: that the file writes it, so a hand edit deleting it fails here
     * rather than only in a digest window, and that it compiles to air, which is what it was before
     * anyone wrote it down and is why saying it moved no golden.</p>
     */
    @Rule("TRAIT.005")
    @Rule("TRAIT.006")
    @Rule("TRAIT.055")
    @Test
    void bothBundledSocketsNameTheirUnlitStandInRatherThanDefaultingIntoIt() {
        for (char marker : new char[] {'T', 'h'}) {
            JsonObject socket = markerOf(COMMON, marker);
            assertEquals("light_socket", socket.get("kind").getAsString());
            assertEquals("minecraft:air", socket.getAsJsonObject("traits")
                            .getAsJsonObject("urbex:light").get("unlit").getAsString(),
                    () -> "marker '" + marker + "': the socket states the stand-in its candidates "
                            + "inherit, rather than leaving them to a default nothing can read");
        }

        CompiledPalette common = compile(COMMON);
        assertEquals(BlockChoice.AIR, lightSourceOf(common, 'T').unlit());
        assertEquals(BlockChoice.AIR, lightSourceOf(common, 'h').unlit());

        // TRAIT.006 over TRAIT.005: the two candidates naming their own keep it, and every candidate
        // naming none inherits the socket's air - which is the same block version 1 reached by
        // falling back at placement time, and is why writing it down changed no world.
        LightPool torchPool = poolOf(common, 'T');
        assertEquals(Blocks.CANDLE, unlitOf(torchPool, Blocks.TORCH).getBlock());
        torchPool.allCandidates().stream()
                .filter(candidate -> candidate.state().is(Blocks.END_ROD))
                .forEach(candidate -> assertEquals(Blocks.AIR, Objects.requireNonNull(candidate.unlit(),
                        "an inherited trait is a trait, so no candidate of a socket that states one "
                                + "carries a null replacement").getBlock()));
    }

    @Rule("TRAIT.051")
    @Test
    void theOilrigDeckLightsAreInPlaceSourcesThatLeaveTheirFixtureBehind() {
        CompiledPalette oilrig = compile(OILRIG);

        LightSource seaLantern = lightSourceOf(oilrig, 'J');
        assertNull(seaLantern.pool(), "an in-place source has no pool; its own block is the light");
        assertEquals(BlockChoice.of(Blocks.PRISMARINE_BRICKS.defaultBlockState()), seaLantern.unlit());

        LightSource wallTorch = lightSourceOf(oilrig, '|');
        assertNull(wallTorch.pool());
        assertEquals(0, ((BlockChoice.One) wallTorch.unlit()).state().getLightEmission());
        assertEquals(Blocks.REDSTONE_WALL_TORCH, ((BlockChoice.One) wallTorch.unlit()).state().getBlock());
    }

    /**
     * Every bundled palette is version 2 and compiles, which is the property Task 10 bought.
     *
     * <p>Compiles rather than merely decodes, because decoding is no longer the interesting half: a
     * marker written {@code { "$ref": "urbex:damageable", "block": … }} decodes without the
     * {@code definitions} registry existing and fails to resolve without it ({@code REF.013}). So the
     * walk runs all of {@code LOAD.001}'s stages 2 to 8 against the pack's own definitions.</p>
     */
    @Rule("VER.002")
    @Rule("REF.010")
    @Test
    void everyBundledPaletteIsVersion2AndCompilesAgainstThePacksOwnDefinitions() throws IOException {
        try (Stream<Path> files = Files.walk(PACK.resolve("palettes"))) {
            List<Path> palettes = files.filter(file -> file.toString().endsWith(".json"))
                    .sorted(Comparator.naturalOrder()).toList();
            assertEquals(30, palettes.size(), "the bundled pack ships thirty palettes");
            for (Path path : palettes) {
                assertEquals(2, JsonParser.parseString(read(path)).getAsJsonObject()
                                .get("version").getAsInt(),
                        () -> path + " is not written in format version 2");
                compile(path);
            }
        }
    }

    // ---- the harness ---------------------------------------------------------------------------

    /**
     * One bundled palette through stages 2 to 8 of {@code LOAD.001}, merged as a style's draw would.
     *
     * <p>The same shape {@code V2Palettes.compileV2} runs at world load, with the world's registries
     * replaced by the pack's own files: a {@link DefinitionIndex} built from {@code definitions/} and
     * the {@code urbex:conditions} ids read out of {@code conditions/} rather than listed here, so a
     * pool this pack stops shipping fails as a missing condition instead of passing against a stale
     * copy of the list.</p>
     */
    private static CompiledPalette compile(Path file) {
        Diagnostics diagnostics = new Diagnostics();
        DataResult<PaletteV2Definition> decoded = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(read(file)));
        PaletteV2Definition palette = decoded.result().orElseThrow(() -> new AssertionError(
                file + ": " + decoded.error().map(Object::toString).orElse("unknown decode error")));
        CompiledV2Palette compiled = NodeResolver
                .resolve(palette, definitions(), Map.of(), diagnostics)
                .flatMap(resolved -> CompiledV2Palette.compile(resolved, presence(), traits(),
                        "'" + file + "'", diagnostics))
                .orElseThrow(() -> new AssertionError(
                        file + ": " + diagnostics.asError().orElse("it did not compile")));
        return new CompiledPalette(dev.krona.urbex.worldgen.lost.cityassets.Palette.version2(
                idOf("palettes", file), compiled));
    }

    /** The {@code definitions} registry, as the bundled pack ships it ({@code REF.010}). */
    private static DefinitionIndex definitions() {
        Map<Identifier, DefinitionAssetDefinition> byId = new LinkedHashMap<>();
        for (Path file : jsonUnder(PACK.resolve("definitions"))) {
            DataResult<DefinitionAssetDefinition> decoded = DefinitionAssetDefinition.CODEC
                    .parse(JsonOps.INSTANCE, JsonParser.parseString(read(file)));
            byId.put(idOf("definitions", file), decoded.result().orElseThrow(() -> new AssertionError(
                    file + ": " + decoded.error().map(Object::toString).orElse("unknown error"))));
        }
        return new DefinitionIndex(byId);
    }

    private static Exclusion.Presence presence() {
        return Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("urbex", "minecraft"));
    }

    private static TraitContext traits() {
        Set<Identifier> conditions = new LinkedHashSet<>();
        for (Path file : jsonUnder(PACK.resolve("conditions"))) {
            conditions.add(idOf("conditions", file));
        }
        return TraitContext.withConditions(BuiltInRegistries.BLOCK, conditions);
    }

    /** {@code urbex:<name>} for a file under {@code <pack>/<registry>/}. */
    private static Identifier idOf(String registry, Path file) {
        String path = PACK.resolve(registry).relativize(file).toString();
        return Identifier.fromNamespaceAndPath("urbex",
                path.substring(0, path.length() - ".json".length()).replace('\\', '/'));
    }

    private static List<Path> jsonUnder(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(file -> file.toString().endsWith(".json"))
                    .sorted(Comparator.naturalOrder()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The raw node one marker is written as, for the assertions that are about the file. */
    private static JsonObject markerOf(Path file, char marker) {
        JsonElement node = JsonParser.parseString(read(file)).getAsJsonObject()
                .getAsJsonObject("palette").get(Character.toString(marker));
        assertNotNull(node, () -> file + " has no marker '" + marker + "'");
        return node.getAsJsonObject();
    }

    /** The sizes one placement list is written with, before {@code WEIGHT.043} apportions them. */
    private static List<Integer> authoredWeights(Path file, char marker, String placement) {
        return markerOf(file, marker).getAsJsonArray(placement).asList().stream()
                .map(choice -> choice.getAsJsonObject().get("weight").getAsInt()).toList();
    }

    private static LightSource lightSourceOf(CompiledPalette palette, char marker) {
        CompiledPalette.Placed placed = palette.placedAt(marker, SEED, 0, 64, 0);
        assertNotNull(placed, () -> "no marker '" + marker + "'");
        LightSource source = placed.info().lightSource();
        assertNotNull(source, () -> "marker '" + marker + "' carries no light");
        return source;
    }

    private static LightPool poolOf(CompiledPalette palette, char marker) {
        LightPool pool = lightSourceOf(palette, marker).pool();
        assertNotNull(pool, () -> "marker '" + marker + "' is not a socket");
        return pool;
    }

    /** Every candidate's slot count, in {@code Placement} order — what the placer draws against. */
    private static List<Integer> slotCounts(LightPool pool) {
        return pool.allCandidates().stream().map(LightPool.Candidate::weight).toList();
    }

    /** Every candidate's block, in the same {@code Placement} order {@link #slotCounts} is in. */
    private static List<Block> blocksOf(LightPool pool) {
        return pool.allCandidates().stream().map(candidate -> candidate.state().getBlock()).toList();
    }

    /**
     * Which blocks one placement list holds.
     *
     * <p>A set, and read through {@code weightedOrder}, because that is the only per-placement view a
     * pool offers and it deliberately rotates from the candidate the position drew — so it answers
     * "which candidates are in this list" and not "in what order were they written".</p>
     */
    private static Set<Block> blocksIn(LightPool pool, LightPool.Placement placement) {
        return pool.weightedOrder(placement, SEED, 0, 64, 0).stream()
                .map(candidate -> candidate.state().getBlock())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The replacement of the candidate that places {@code lit} and names one of its own.
     *
     * <p>The floor lantern and the ceiling lantern are both {@code minecraft:lantern} and only the
     * hanging one has a chain to leave behind, so the filter is on the replacement being something
     * other than the socket's inherited air, not on the lit block alone.</p>
     */
    private static BlockState unlitOf(LightPool pool, Block lit) {
        return pool.allCandidates().stream()
                .filter(candidate -> candidate.state().getBlock() == lit
                        && candidate.unlit() != null && !candidate.unlit().is(Blocks.AIR))
                .map(LightPool.Candidate::unlit)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate placing " + lit
                        + " names a replacement of its own"));
    }
}

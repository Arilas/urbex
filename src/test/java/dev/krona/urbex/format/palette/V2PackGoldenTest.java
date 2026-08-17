package dev.krona.urbex.format.palette;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.Diagnostics;
import dev.krona.urbex.format.Rule;
import dev.krona.urbex.worldgen.lost.cityassets.CompiledPalette;
import dev.krona.urbex.worldgen.lost.cityassets.MarkerTrait;
import dev.krona.urbex.worldgen.lost.cityassets.Palette;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import dev.krona.urbex.format.palette.traits.OptionalTrait;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first end-to-end evidence that Tasks 3 to 6 work against a pack rather than against a fixture.
 *
 * <p>Every stage of {@code LOAD.001} runs over one real palette file, its markers are merged into a
 * {@link CompiledPalette} exactly as a style's draw would be, and every marker is resolved at a fixed
 * lattice of positions. The result is hashed and pinned in {@code v2-pack.golden}.</p>
 *
 * <h2>What this proves, and what it does not</h2>
 *
 * <p><b>It proves</b> that a version 2 pack decodes, links, excludes, apportions and compiles; that its
 * markers merge; that resolution at a position is deterministic and addressed by position rather than by
 * call order; and that the per-slot traits survive all of it, because the digest covers them.</p>
 *
 * <p><b>It does not prove that a version 2 pack produces a correct city.</b> Nothing here runs
 * {@code Parts.generatePart}, writes a chunk or drives a world — that is what
 * {@code runDigestCheck} does for version 1, and version 2 has no bundled pack to point such a run at
 * until the converter lands. So this is evidence about the palette, not about the world, and the honest
 * reading of a passing run is "the pack compiles and answers every question the same way twice".</p>
 *
 * <h2>What the pack is for</h2>
 *
 * <p>It is deliberately not minimal. The shipped corpus was scanned for markers carrying more than one
 * of version 1's four metadata fields and holds <b>zero</b> — which is why fixing the {@code else if}
 * chain moved no golden — so a pack that exercises only what ships would test nothing the corpus does
 * not already cover. Every construct below is one the bundled pack cannot reach.</p>
 */
class V2PackGoldenTest {

    private static final Path GOLDEN = Path.of("v2-pack.golden");
    private static final String PACK = "/v2pack/palettes/every_kind_and_trait.json";

    /** Vanilla block tags are datapack content, and a bootstrapped registry binds none. */
    private static final TraitContext.TagEpoch TAGS = tag ->
            tag.equals(Identifier.parse("minecraft:planks"))
                    ? List.of("minecraft:oak_planks", "minecraft:spruce_planks",
                            "minecraft:birch_planks")
                    : List.of();

    private static final Set<Identifier> CONDITIONS = Set.of(
            Identifier.parse("urbex:chest"), Identifier.parse("urbex:easymobs"));

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Rule("LOAD.001")
    @Rule("LOAD.043")
    @Test
    void theVersion2PackResolvesToTheGoldenItWasPinnedAgainst() throws IOException {
        String digest = digestOf(merged());

        assertTrue(Files.exists(GOLDEN),
                () -> "no golden yet; the value this run produced is " + digest);
        assertEquals(Files.readString(GOLDEN).trim(), digest,
                "the version 2 pack resolved differently than it was pinned. If this is intended, "
                        + "delete v2-pack.golden and run twice - two independent runs must agree "
                        + "before a new value is committed.");
    }

    @Rule("LOAD.043")
    @Test
    void twoIndependentCompilationsOfTheSamePackAgree() {
        assertEquals(digestOf(merged()), digestOf(merged()),
                "LOAD.043: the result depends only on the seed, the marker, the position and the "
                        + "compiled palette - so compiling the document twice must answer identically");
    }

    /**
     * The case the shipped corpus does not have, asserted directly rather than only through the digest.
     *
     * <p>A scan over every {@code palette} list under {@code src/main/resources} finds zero entries
     * carrying more than one of {@code lightSource}, {@code loot}, {@code mob} and {@code tag}. That
     * absence is why fixing {@code Parts.generatePart}'s {@code else if} chain moved no golden, and it
     * means the trait loop has never run with more than one trait on a marker in a real generation.
     * These two markers are that case.</p>
     */
    @Rule("TRAIT.004")
    @Rule("TRAIT.095")
    @Test
    void aMarkerCarryingTwoMetadataTraitsCarriesBothIntoGeneration() {
        CompiledPalette merged = merged();

        CompiledPalette.Placed chest = merged.placedAt('C', 1L, 0, 64, 0);
        assertNotNull(chest.info());
        assertEquals(List.of(MarkerTrait.LOOT, MarkerTrait.BLOCK_ENTITY), chest.info().applied(),
                "a loot pool beside block entity NBT applies both, in TRAIT.095's phase order");

        CompiledPalette.Placed spawner = merged.placedAt('S', 1L, 0, 64, 0);
        assertNotNull(spawner.info());
        assertEquals(List.of(MarkerTrait.SPAWNER, MarkerTrait.BLOCK_ENTITY),
                spawner.info().applied(),
                "and so does a spawner pool beside it - the chain this replaces applied one and "
                        + "dropped the other");
    }

    /**
     * {@code LOAD.021}: traits are per slot, which is the claim version 1's per-marker table could not
     * represent, and the one thing a whole-palette digest would hide if it only covered states.
     */
    @Rule("LOAD.021")
    @Test
    void oneMarkersSlotsCarryDifferentTraitsFromEachOther() {
        CompiledPalette merged = merged();
        List<String> seen = new ArrayList<>();
        for (int x = 0; x < 256; x++) {
            CompiledPalette.Placed placed = merged.placedAt('m', 1L, x, 64, 0);
            String applied = placed.info() == null ? "none" : placed.info().applied().toString();
            if (!seen.contains(applied)) {
                seen.add(applied);
            }
        }

        assertEquals(2, seen.size(),
                "marker 'm' is a stone slot and a lantern slot; only the lantern carries urbex:light, "
                        + "so the two slots must differ: " + seen);
    }

    /**
     * Every construct the pack claims to exercise is actually present in the compiled palette.
     *
     * <p>Without this the golden would happily pin a pack that silently lost half of it: a marker
     * dropped by a stage that did not raise a diagnostic simply changes the digest, and the digest was
     * pinned <em>after</em> the loss. A claim about coverage has to be checked against the tree like any
     * other, which is {@code docs/format/README.md} §1's whole subject.</p>
     */
    @Rule("LOAD.001")
    @Test
    void everyConstructThePackClaimsToExerciseIsInTheCompiledPalette() {
        CompiledPalette merged = merged();

        assertEquals(List.of('@', 'C', 'F', 'L', 'S', 'T', 'd', 'e', 'm', 'n', 'o', 'p', 's', 'w'),
                merged.getCharacters().stream().sorted().toList(),
                "every marker the pack declares compiled; a construct silently dropped by a stage "
                        + "would change the digest and nothing else");

        // The eight cases the shipped corpus does not have, each asked of the thing that proves it.
        assertTrue(merged.isSimple('s'), "a plain block marker is one slot");
        assertFalse(merged.isSimple('w'), "a weighted marker is many - and 'w' is the $spread one");
        assertEquals(3, merged.getAll('w').size(),
                "'w' is one share plus two choices spread in from $defs, so three distinct states");
        assertEquals(3, merged.getAll('n').size(),
                "'n' nests a weighted node inside a weighted node: andesite, diorite, granite");
        assertEquals(3, merged.getAll('p').size(),
                "'p' is a tag source, expanded to its three members at load by MODEL.052");
        // '@' aliases 'w', which is three states. Replacing the alias with any literal block leaves one
        // state, so this cannot be satisfied by a coincidence the way aliasing a plain block could.
        assertEquals(merged.getAll('w'), merged.getAll('@'),
                "'@' is an alias and resolves to exactly what its target resolves to");
        assertEquals(3, merged.getAll('@').size(),
                "and its target is weighted, so a literal block written here would not pass");

        // 'e' names three blocks this game HAS, one of them behind a `when` for a mod it does not.
        // Naming an absent block instead would make the count identical whether the `when` is there or
        // not, which is exactly what made the first version of this assertion vacuous.
        assertEquals(2, merged.getAll('e').size(),
                "WEIGHT.020's 'when' excluded the choice whose mod is absent, and two survive; all "
                        + "three blocks exist, so removing the 'when' would make this three");

        assertEquals(Blocks.IRON_BARS.defaultBlockState(),
                merged.placedAt('L', 1L, 0, 64, 0).info().lightSource()
                        .unlitAt(1L, BlockPos.ZERO),
                "'L' carries urbex:light whose unlit satellite is a compiled entry - asserted as the "
                        + "block the file named, because a satellite that had been dropped would leave "
                        + "TRAIT.051's air default and still be non-null");
        assertTrue(merged.placedAt('T', 1L, 0, 64, 0).info().lightSource().isSocket(),
                "'T' is a light_socket and became a LightPool");
        assertEquals(Blocks.IRON_BARS.defaultBlockState(),
                merged.canBeDamagedToIronBars(merged.placedAt('d', 1L, 0, 64, 0).state()),
                "'d' reaches a satellite through a $ref into $defs");

        // The two traits that never cross the seam, so the digest cannot see them.
        CompiledEntry optional = compiled().entry('o');
        assertTrue(optional.slot(0).traits().has(OptionalTrait.TYPE),
                "'o' carries urbex:optional, which the decoration pass reads off the trait set");
        assertFalse(compiled().entry('F').slot(0).traits().rotatable(),
                "'F' opts out of rotation with TRAIT.071's false, which the part transform reads");
    }

    /** Everything the pack resolves to, hashed: marker, position, state, and what it carries. */
    private static String digestOf(CompiledPalette merged) {
        StringBuilder sink = new StringBuilder();
        List<Character> markers = new ArrayList<>(merged.getCharacters());
        markers.sort(null);
        for (char marker : markers) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int y = 64 + ((x + z) % 8);
                    CompiledPalette.Placed placed = merged.placedAt(marker, 20260817L, x, y, z);
                    sink.append(marker).append('@').append(x).append(',').append(y).append(',')
                            .append(z).append('=')
                            .append(placed == null ? "-" : describe(placed)).append('\n');
                }
            }
            sink.append(marker).append(" damaged=")
                    .append(damagedFor(merged, marker)).append('\n');
        }
        return sha256(sink.toString());
    }

    /**
     * A marker's {@code urbex:damaged} form, appended so the golden can see it.
     *
     * <p>{@code urbex:damaged} is applied by the damage pass off a state-keyed map, so it never reaches
     * {@link Palette.Info} and was invisible to the digest — deleting it from the pack changed nothing
     * and the golden would have pinned its absence. A digest that cannot see a construct is not evidence
     * about it, which is the whole reason this line exists.</p>
     *
     * <p>{@code urbex:optional} and {@code urbex:rotatable} are not here and cannot be: neither reaches
     * this side of the seam at all — the first is read by the decoration pass off the compiled trait set
     * and the second by the part transform — so they are asserted directly in
     * {@link #everyConstructThePackClaimsToExerciseIsInTheCompiledPalette} instead, where a mutation
     * does fail.</p>
     */
    private static String damagedFor(CompiledPalette merged, char marker) {
        BlockState from = merged.getRepresentative(marker);
        BlockState damaged = from == null ? null : merged.canBeDamagedToIronBars(from);
        return damaged == null ? "-" : damaged.toString();
    }

    /** A slot's state and every trait that applies to it, in a form a diff can be read from. */
    private static String describe(CompiledPalette.Placed placed) {
        StringBuilder text = new StringBuilder(placed.state().toString());
        Palette.Info info = placed.info();
        if (info == null) {
            return text.toString();
        }
        text.append(" traits=").append(info.applied());
        if (info.mobId() != null) {
            text.append(" mob=").append(info.mobId());
        }
        if (info.loot() != null) {
            text.append(" loot=").append(info.loot());
        }
        if (info.tag() != null) {
            text.append(" nbt=").append(info.tag());
        }
        if (info.lightSource() != null) {
            text.append(" light=socket:").append(info.lightSource().isSocket());
        }
        return text.toString();
    }

    /**
     * The pack, compiled and merged exactly as a style's draw of one palette would be.
     *
     * <p>Through {@link CompiledPalette} rather than asking {@code CompiledV2Palette} directly, because
     * the merge is the seam generation actually uses and a test that skipped it would be testing a
     * stage no world reaches.</p>
     */
    private static CompiledPalette merged() {
        return new CompiledPalette(Palette.version2(
                Identifier.parse("urbex:every_kind_and_trait"), compiled()));
    }

    /** The compiled version 2 palette itself, for the traits that never cross the seam. */
    private static CompiledV2Palette compiled() {
        Diagnostics diagnostics = new Diagnostics();
        PaletteV2Definition file = PaletteV2Definition.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(read()))
                .getOrThrow(message -> new AssertionError("the pack did not decode: " + message));
        CompiledV2Palette compiled = NodeResolver.resolve(file, diagnostics)
                .flatMap(resolved -> CompiledV2Palette.compile(resolved,
                        Exclusion.installed(BuiltInRegistries.BLOCK, Set.of("urbex", "minecraft")),
                        TraitContext.withConditions(BuiltInRegistries.BLOCK, CONDITIONS)
                                .withTags(TAGS),
                        "'urbex:every_kind_and_trait'", diagnostics))
                .orElseThrow(() -> new AssertionError(
                        "the pack did not compile: " + diagnostics.asError().orElse("?")));
        return compiled;
    }

    private static String read() {
        try (InputStream in = V2PackGoldenTest.class.getResourceAsStream(PACK)) {
            assertNotNull(in, PACK + " is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + PACK, e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}

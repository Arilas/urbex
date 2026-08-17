package dev.krona.urbex.format;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the specification's own fixtures against the decoder.
 * <p>
 * {@code docs/format/README.md} §4.2 gives this class four jobs. Three of them - a fixture citing an
 * undefined rule, a fixture citing an undefined diagnostic, and a rule of a fixture-needing class with
 * no fixture - need no decoder and are already {@code ConformanceIndexTest}'s. This is the fourth:
 * "runs each fixture against its declared outcome".
 * <p>
 * <b>Decode is not the whole load.</b> Most of what the specification refuses needs something this
 * task does not have: a resolution pass over the reference graph, a merged {@code extends} chain, a
 * trait registry, the block and tag registries of the world being loaded. A fixture whose outcome
 * depends on one of those is listed in {@link #PENDING} with the reason, and the list is not a comment:
 * {@link #theSetOfFixturesNotYetRunnableIsExactlyTheDocumentedOne} runs every pending fixture too and
 * fails if one of them has started behaving as the specification says. So an entry cannot outlive the
 * task that makes it runnable, and the count can only go down.
 * <p>
 * An {@code accept} fixture is asserted at decode strength: the document decodes. That is a necessary
 * condition of the fixture's claim and not the whole of it - a later stage may still refuse a document
 * that decoded - so those fixtures are run here <em>and</em> get stronger as the later stages land,
 * without moving in or out of any list. The display name of each dynamic test says which strength it
 * asserted, so a passing run is readable rather than merely green.
 */
class FormatFixtureTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Fixture address -> why its declared outcome cannot be decided by decoding alone yet.
     * <p>
     * Addressed as {@code <rule>#<ordinal>}, which is how {@code README.md} §4.1 says an unnamed
     * fixture is addressed: by rule and ordinal. A {@code file:line} key would move every time a
     * document is edited above it, and this list has to be diffable.
     * <p>
     * Every entry names a later task. Three earlier entries did not - they were specification defects
     * this task surfaced, in {@code CHAR.005}'s, {@code MODEL.051}'s and {@code VER.010}'s fixtures -
     * and all three were adjudicated and fixed in the documents rather than accommodated here, which is
     * why the list holds only work now.
     */
    static final Map<String, String> PENDING = pending();

    private static Map<String, String> pending() {
        Map<String, String> pending = new LinkedHashMap<>();

        // Task 3 - pointers, operands, and resolution.
        pending.put("MODEL.011#1", "MODEL.011's kind default is applied after reference resolution:"
                + " a node carrying $ref takes its kind from its target, so the two spellings are only"
                + " comparable once $ref is resolved (Task 3)");
        pending.put("MODEL.011#2", "the other half of the same equiv group");
        pending.put("MODEL.081#1", "MODEL.081 is completeness, checked where a definition is used and"
                + " therefore after $ref is resolved (Task 3)");
        pending.put("REF.013#1", "a $ref that names no definition is a resolution failure (Task 3)");
        pending.put("REF.032#1", "a reference cycle is found by the topological pass (Task 3)");
        pending.put("REF.071#1", "whether a $spread's pointer names a list is a property of the node it"
                + " points at (Task 3)");
        pending.put("REF.083#1", "an $imports alias is expanded where a pointer is parsed (Task 3)");

        // Task 4 - merging.
        pending.put("MODEL.062#1", "an alias names a marker answered by the merged palette (Task 4)");
        pending.put("MERGE.007#1", "palette is required somewhere in the extends chain, which needs the"
                + " chain (Task 4)");
        pending.put("MERGE.009#1", "the fixture is a part file, and an inline palette is decoded by the"
                + " part codec, which is still version 1 only (Task 4)");

        // Task 5 - sizes and selection.
        pending.put("WEIGHT.024#1", "exclusion by 'when' is evaluated against the loaded mods (Task 5)");
        pending.put("WEIGHT.032#1", "exclusion by absent blocks is evaluated against the block registry"
                + " (Task 5)");

        // Task 6 - traits, characters, and the compiled palette.
        pending.put("MODEL.033#1", "the satellite is a node inside a trait payload, which stays opaque"
                + " until the trait registry exists (Task 6)");
        pending.put("MODEL.043#1", "whether a property expression applies to a block needs the block"
                + " registry (Task 6)");
        pending.put("MODEL.053#1", "whether a tag expands to no blocks needs the tag registry (Task 6)");
        pending.put("TRAIT.003#1", "an unregistered trait id needs the trait registry (Task 6)");
        pending.put("TRAIT.021#1", "a pool naming no conditions asset needs that registry (Task 6)");
        pending.put("TRAIT.031#1", "a pool naming no conditions asset needs that registry (Task 6)");
        pending.put("TRAIT.041#1", "whether a block has a block entity needs the block registry (Task 6)");
        pending.put("TRAIT.051#1", "an absent 'unlit' defaults to air inside a trait, which is decoded"
                + " in Task 6");
        pending.put("TRAIT.051#2", "the other half of the same equiv group");
        pending.put("TRAIT.052#1", "whether a block emits light needs the block registry (Task 6)");
        pending.put("TRAIT.053#1", "whether an unlit replacement emits light needs the block registry"
                + " (Task 6)");
        pending.put("TRAIT.062#1", "an absent 'replacement' defaults to air inside a trait, which is"
                + " decoded in Task 6");
        pending.put("TRAIT.062#2", "the other half of the same equiv group");
        pending.put("TRAIT.064#1", "two traits on one node conflict, which needs the trait registry"
                + " (Task 6)");

        // VER.014's fixture is a part file, like MERGE.009's, and for the same reason it cannot run
        // here: this harness decodes every fixture as a palette file. The rule itself is covered by a
        // citing test that drives the real part codec, so it is not relying on this fixture.
        pending.put("VER.014#1", "the fixture is a part file, and this harness decodes fixtures as"
                + " palette files; the rule is covered by a citing test on the part codec (Task 4"
                + " retires the rule outright, by MERGE.011)");

        // Insertion order kept: this list is read as a diff and its failure messages have to come out
        // in the order the entries are written, not in whatever order hashing produced.
        return java.util.Collections.unmodifiableMap(pending);
    }

    @TestFactory
    Stream<DynamicTest> everyDecodeTimeFixtureBehavesAsTheSpecificationSays() {
        List<Addressed> fixtures = addressed();
        List<DynamicTest> tests = new ArrayList<>();
        for (Addressed fixture : fixtures) {
            if (PENDING.containsKey(fixture.address()) || fixture.isEquiv()) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest(name(fixture), () -> assertBehaves(fixture)));
        }
        for (Map.Entry<String, List<Addressed>> group : equivGroups(fixtures).entrySet()) {
            if (group.getValue().stream().anyMatch(fx -> PENDING.containsKey(fx.address()))) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest(
                    "equiv=" + group.getKey() + " — every spelling decodes to the same node tree",
                    () -> assertAllEqual(group.getKey(), group.getValue())));
        }
        return tests.stream();
    }

    /**
     * The pending list is exactly the fixtures that are still not decidable, with nothing stale in it.
     * <p>
     * Two directions, and the second is the one with teeth. An address that names no fixture is a stale
     * entry - a rule renumbered, a fixture deleted - and would silently drop a fixture out of coverage.
     * A pending fixture that <em>already</em> behaves as the specification says is a line somebody
     * forgot to delete when the task that made it runnable landed, and leaving it there would mean the
     * fixture is checked nowhere.
     */
    @Test
    void theSetOfFixturesNotYetRunnableIsExactlyTheDocumentedOne() {
        List<Addressed> fixtures = addressed();
        Set<String> addresses = new LinkedHashSet<>();
        fixtures.forEach(fixture -> addresses.add(fixture.address()));

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> pending : PENDING.entrySet()) {
            if (!addresses.contains(pending.getKey())) {
                failures.add(pending.getKey() + " is listed as pending, but no fixture has that"
                        + " address - the list is stale");
            }
            if (pending.getValue().isBlank()) {
                failures.add(pending.getKey() + " is listed as pending with no reason");
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));

        Map<String, List<Addressed>> groups = equivGroups(fixtures);
        for (Addressed fixture : fixtures) {
            if (!PENDING.containsKey(fixture.address())) {
                continue;
            }
            if (fixture.isEquiv()) {
                if (allEqual(groups.get(fixture.fixture().equivSlug().orElseThrow()))) {
                    failures.add(fixture.address() + " now decodes equal to the rest of its equiv"
                            + " group; delete it from PENDING");
                }
                continue;
            }
            if (behavesAsDeclared(fixture)) {
                failures.add(fixture.address() + " now behaves as the specification says;"
                        + " delete it from PENDING");
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    // ----------------------------------------------------------------------------------------------

    private static void assertBehaves(Addressed fixture) {
        DataResult<PaletteAssetDefinition> decoded = decode(fixture.fixture().json());
        switch (fixture.fixture().outcome()) {
            case ACCEPT -> assertTrue(decoded.result().isPresent(),
                    () -> fixture.where() + ": expected the document to decode, got "
                            + decoded.error().map(DataResult.Error::message).orElse("?"));
            case REJECT -> {
                String expected = fixture.fixture().diag().orElseThrow();
                assertTrue(decoded.error().isPresent(),
                        () -> fixture.where() + ": expected " + expected + ", but the document decoded");
                String message = decoded.error().orElseThrow().message();
                assertTrue(Diag.of(expected).matches(message),
                        () -> fixture.where() + ": expected " + expected + " ("
                                + Diag.of(expected).template() + ") but got: " + message);
                // And nothing else. A fixture that only checks its own diagnostic is present passes
                // while the loader says something false beside it - which is how a socket with one
                // malformed candidate came to be told it had no candidate. A refusal names one thing
                // wrong with a document that has one thing wrong with it.
                List<String> alsoMatched = Arrays.stream(Diag.values())
                        .filter(diag -> !diag.id().equals(expected))
                        .filter(diag -> !diag.template().equals("—"))
                        .filter(diag -> diag.matches(message))
                        .map(Diag::id)
                        .toList();
                assertTrue(alsoMatched.isEmpty(), () -> fixture.where() + ": expected " + expected
                        + " alone, but the message also reads as " + alsoMatched + ": " + message);
            }
            case EQUIV -> throw new IllegalStateException("equiv fixtures are run as a group");
            case FRAGMENT -> throw new IllegalStateException(fixture.where()
                    + ": no fixture uses 'fragment' yet, and nothing here knows how to embed one");
        }
    }

    private static void assertAllEqual(String slug, List<Addressed> group) {
        List<PaletteAssetDefinition> decoded = new ArrayList<>();
        for (Addressed fixture : group) {
            DataResult<PaletteAssetDefinition> result = decode(fixture.fixture().json());
            assertTrue(result.result().isPresent(), () -> fixture.where()
                    + ": an equiv fixture must decode, got "
                    + result.error().map(DataResult.Error::message).orElse("?"));
            decoded.add(result.result().orElseThrow());
        }
        for (int i = 1; i < decoded.size(); i++) {
            assertEquals(decoded.get(0), decoded.get(i),
                    "equiv=" + slug + ": " + group.get(0).where() + " and " + group.get(i).where()
                            + " must decode to the same thing");
        }
    }

    private static boolean behavesAsDeclared(Addressed fixture) {
        DataResult<PaletteAssetDefinition> decoded = decode(fixture.fixture().json());
        return switch (fixture.fixture().outcome()) {
            case ACCEPT -> decoded.result().isPresent();
            case REJECT -> decoded.error().isPresent()
                    && Diag.of(fixture.fixture().diag().orElseThrow())
                            .matches(decoded.error().orElseThrow().message());
            case EQUIV, FRAGMENT -> false;
        };
    }

    private static boolean allEqual(List<Addressed> group) {
        List<PaletteAssetDefinition> decoded = new ArrayList<>();
        for (Addressed fixture : group) {
            Optional<PaletteAssetDefinition> result = decode(fixture.fixture().json()).result();
            if (result.isEmpty()) {
                return false;
            }
            decoded.add(result.get());
        }
        return decoded.stream().distinct().count() == 1;
    }

    /** Every fixture decodes through the registry's codec, so version dispatch is part of every run. */
    private static DataResult<PaletteAssetDefinition> decode(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        return PaletteAssetDefinition.CODEC.parse(JsonOps.INSTANCE, parsed);
    }

    private static List<Addressed> addressed() {
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<Addressed> addressed = new ArrayList<>();
        for (SpecDocuments.Fixture fixture : SpecDocuments.load().fixtures()) {
            int ordinal = seen.merge(fixture.ruleId(), 1, Integer::sum);
            addressed.add(new Addressed(fixture, fixture.ruleId() + "#" + ordinal));
        }
        return addressed;
    }

    private static Map<String, List<Addressed>> equivGroups(List<Addressed> fixtures) {
        Map<String, List<Addressed>> groups = new LinkedHashMap<>();
        for (Addressed fixture : fixtures) {
            fixture.fixture().equivSlug().ifPresent(slug ->
                    groups.computeIfAbsent(slug, key -> new ArrayList<>()).add(fixture));
        }
        return groups;
    }

    private static String name(Addressed fixture) {
        String claim = switch (fixture.fixture().outcome()) {
            case ACCEPT -> "decodes";
            case REJECT -> "is refused with " + fixture.fixture().diag().orElseThrow();
            case EQUIV -> "equiv";
            case FRAGMENT -> "fragment";
        };
        return fixture.address() + " " + claim + " (" + fixture.where() + ")";
    }

    /** One fixture with the {@code <rule>#<ordinal>} address {@link #PENDING} keys on. */
    private record Addressed(SpecDocuments.Fixture fixture, String address) {

        boolean isEquiv() {
            return fixture.outcome() == SpecDocuments.Outcome.EQUIV;
        }

        String where() {
            return fixture.file() + ":" + fixture.line();
        }
    }
}

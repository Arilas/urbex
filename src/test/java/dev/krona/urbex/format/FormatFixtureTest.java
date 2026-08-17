package dev.krona.urbex.format;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.krona.urbex.format.palette.NodeResolver;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.format.palette.Pointer;
import dev.krona.urbex.format.palette.RawNode;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartDefinition;
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
 * <b>Decode is not the whole load.</b> Each fixture is taken as far through {@code LOAD.001}'s pipeline
 * as the stages that exist: stage 1, decode, always, and stage 3, link, whenever the document is
 * {@link #selfContained self-contained}. Most of what remains needs something no stage here has: a
 * merged {@code extends} chain, a trait registry, the block and tag registries of the world being
 * loaded. A fixture whose outcome depends on one of those is listed in {@link #PENDING} with the reason,
 * and the list is not a comment: {@link #theSetOfFixturesNotYetRunnableIsExactlyTheDocumentedOne} runs
 * every pending fixture too and fails if one of them has started behaving as the specification says. So
 * an entry cannot outlive the task that makes it runnable, and the count can only go down.
 * <p>
 * An {@code accept} fixture is asserted at whichever strength it reached, which is a necessary condition
 * of the fixture's claim and not the whole of it - a later stage may still refuse a document that got
 * this far - so those fixtures are run here <em>and</em> get stronger as the later stages land, without
 * moving in or out of any list. The display name of each dynamic test says which strength it asserted,
 * so a passing run is readable rather than merely green.
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

        // MODEL.062 was listed for Task 4 and is not decidable there, or by any check over one palette
        // chain. MODEL.060 resolves an alias "in the same merged palette" and MODEL.064 says which merge
        // that is: "the merged palette the part is generated with - including markers contributed by
        // palettes this file never mentions". The shipped pack relies on exactly that
        // (urbex:glass_side_variant_glass maps '@' to 'a' and declares nothing else), so an alias whose
        // target its own chain does not declare is not yet wrong. It becomes decidable where a style's
        // palette groups are merged, which is LOAD.013's stage.
        pending.put("MODEL.062#1", "an alias is answered by the merged palette a part is generated with"
                + " (MODEL.064), so an unresolvable one is only knowable where a style's palette groups"
                + " are merged (Task 7)");

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

        // Insertion order kept: this list is read as a diff and its failure messages have to come out
        // in the order the entries are written, not in whatever order hashing produced.
        return java.util.Collections.unmodifiableMap(pending);
    }

    @TestFactory
    Stream<DynamicTest> everyRunnableFixtureBehavesAsTheSpecificationSays() {
        List<Addressed> fixtures = addressed();
        List<DynamicTest> tests = new ArrayList<>();
        for (Addressed fixture : fixtures) {
            if (PENDING.containsKey(fixture.address()) || fixture.isEquiv()) {
                continue;
            }
            Loaded loaded = load(fixture.fixture().json());
            tests.add(DynamicTest.dynamicTest(name(fixture, loaded),
                    () -> assertBehaves(fixture, loaded)));
        }
        for (Map.Entry<String, List<Addressed>> group : equivGroups(fixtures).entrySet()) {
            if (group.getValue().stream().anyMatch(fx -> PENDING.containsKey(fx.address()))) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest(
                    "equiv=" + group.getKey() + " — every spelling means the same thing",
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

    private static void assertBehaves(Addressed fixture, Loaded loaded) {
        switch (fixture.fixture().outcome()) {
            case ACCEPT -> assertTrue(loaded.error().isEmpty(),
                    () -> fixture.where() + ": expected the document to " + loaded.strength()
                            + ", got " + loaded.error().orElse("?"));
            case REJECT -> {
                String expected = fixture.fixture().diag().orElseThrow();
                assertTrue(loaded.error().isPresent(),
                        () -> fixture.where() + ": expected " + expected + ", but the document "
                                + loaded.strength());
                String message = loaded.error().orElseThrow();
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
        List<Object> loaded = new ArrayList<>();
        for (Addressed fixture : group) {
            Loaded result = load(fixture.fixture().json());
            assertTrue(result.error().isEmpty(), () -> fixture.where()
                    + ": an equiv fixture must " + result.strength() + ", got "
                    + result.error().orElse("?"));
            loaded.add(result.value());
        }
        for (int i = 1; i < loaded.size(); i++) {
            assertEquals(loaded.get(0), loaded.get(i),
                    "equiv=" + slug + ": " + group.get(0).where() + " and " + group.get(i).where()
                            + " must mean the same thing");
        }
    }

    private static boolean behavesAsDeclared(Addressed fixture) {
        Loaded loaded = load(fixture.fixture().json());
        return switch (fixture.fixture().outcome()) {
            case ACCEPT -> loaded.error().isEmpty();
            case REJECT -> loaded.error().isPresent()
                    && Diag.of(fixture.fixture().diag().orElseThrow())
                            .matches(loaded.error().orElseThrow());
            case EQUIV, FRAGMENT -> false;
        };
    }

    private static boolean allEqual(List<Addressed> group) {
        List<Object> loaded = new ArrayList<>();
        for (Addressed fixture : group) {
            Loaded result = load(fixture.fixture().json());
            if (result.error().isPresent()) {
                return false;
            }
            loaded.add(result.value());
        }
        return loaded.stream().distinct().count() == 1;
    }

    /**
     * One fixture, taken as far through the pipeline as a harness holding a single document can.
     *
     * @param decoded stage 1 of {@code LOAD.001}
     * @param linked  stage 3, or empty when this document reaches outside itself - see
     *                {@link #selfContained}
     */
    private record Loaded(DataResult<?> decoded,
                          Optional<DataResult<NodeResolver.ResolvedPalette>> linked) {

        /** The first stage that refused the document, if either did. */
        Optional<String> error() {
            return decoded.error().map(DataResult.Error::message)
                    .or(() -> linked.flatMap(DataResult::error).map(DataResult.Error::message));
        }

        /** The strongest form this fixture reached, which is what an {@code equiv} group compares. */
        Object value() {
            return linked.flatMap(DataResult::result)
                    .map(Object.class::cast)
                    .orElseGet(() -> decoded.result().orElseThrow());
        }

        /** What the dynamic test's name claims was asserted. */
        String strength() {
            return linked.isPresent() ? "decode and link" : "decode";
        }
    }

    /**
     * Decodes a fixture and, when it is self-contained, links it too.
     * <p>
     * Every palette fixture decodes through the registry's codec, so version dispatch is part of every
     * run. A fixture whose top level is a <em>part</em> - {@code MERGE.009}'s, which is about a palette
     * written inside one - goes through the part codec instead, because that is the codec the rule it
     * demonstrates lives in. Detected by {@code slices}, which every part has and no palette of either
     * version does; the alternative, listing the part fixtures by address, would be a second place to
     * update when one is added.
     */
    private static Loaded load(String json) {
        JsonElement document = JsonParser.parseString(json);
        if (document.isJsonObject() && document.getAsJsonObject().has("slices")) {
            return new Loaded(BuildingPartDefinition.CODEC.parse(JsonOps.INSTANCE, document),
                    Optional.empty());
        }
        DataResult<PaletteAssetDefinition> decoded =
                PaletteAssetDefinition.CODEC.parse(JsonOps.INSTANCE, document);
        Optional<PaletteV2Definition> file = decoded.result()
                .filter(PaletteV2Definition.class::isInstance)
                .map(PaletteV2Definition.class::cast)
                .filter(FormatFixtureTest::selfContained);
        if (file.isEmpty()) {
            return new Loaded(decoded, Optional.empty());
        }
        Diagnostics diagnostics = new Diagnostics();
        Optional<NodeResolver.ResolvedPalette> resolved =
                NodeResolver.resolve(file.orElseThrow(), diagnostics);
        DataResult<NodeResolver.ResolvedPalette> linked = resolved
                .map(DataResult::success)
                .orElseGet(() -> DataResult.error(() -> diagnostics.asError()
                        .orElse("the link stage refused the document and said nothing")));
        return new Loaded(decoded, Optional.of(linked));
    }

    /**
     * Whether this document can be linked by a harness that holds one document.
     * <p>
     * A fixture that writes a pointer at another asset cannot be: nothing here loads
     * {@code urbex:common}, and {@code LOAD.025} makes stage 3 need every other palette's stage 2. That
     * is the same limitation the specification itself records, as the {@code [NO-FIXTURE: a second
     * asset]} marker on {@code REF.043} and {@code REF.045} - so such a fixture is asserted at decode
     * strength and its dynamic test says which strength it asserted, rather than being listed as pending
     * for a shortcoming of the harness rather than of the loader.
     * <p>
     * {@code extends} is the same case one level up, and it stays the same case now that the merge
     * exists: {@link dev.krona.urbex.format.palette.V2Chain} folds a chain it is handed, and the parent
     * a fixture names is not in the fixture. {@code MERGE.005}'s and {@code MERGE.006}'s fixtures are
     * therefore asserted at decode strength here and written out again over their parent in
     * {@code V2ChainTest}, which is what a chain needs and a fixture cannot carry.
     * <p>
     * A pointer that fails to <em>parse</em> is not a reach outside the document - it is a local mistake,
     * and {@code REF.083}'s fixture is exactly one - so it does not disqualify a fixture from linking.
     */
    private static boolean selfContained(PaletteV2Definition file) {
        if (file.extendsId().isPresent()) {
            return false;
        }
        List<RawNode> entries = new ArrayList<>(file.defs().values());
        entries.addAll(file.palette().orElse(Map.of()).values());
        for (RawNode entry : entries) {
            for (String written : entry.pointersWritten()) {
                Pointer pointer = Pointer.parse(written, file.imports(), "a fixture")
                        .result().orElse(null);
                if (pointer instanceof Pointer.Registry || pointer instanceof Pointer.Fragment) {
                    return false;
                }
            }
        }
        return true;
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

    private static String name(Addressed fixture, Loaded loaded) {
        String claim = switch (fixture.fixture().outcome()) {
            case ACCEPT -> "survives " + loaded.strength();
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

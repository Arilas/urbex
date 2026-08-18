package dev.krona.urbex.worldgen.lost.cityassets;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import dev.krona.urbex.format.palette.CompiledEntry;
import dev.krona.urbex.format.palette.CompiledV2Palette;
import dev.krona.urbex.format.palette.PaletteV2Definition;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.setup.TestRegistries;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleDefinition;
import dev.krona.urbex.worldgen.lost.regassets.DefinitionAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteAssetDefinition;
import dev.krona.urbex.worldgen.lost.regassets.PaletteDefinition;
import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsDefinition;
import dev.krona.urbex.worldgen.lost.regassets.VariantDefinition;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleEdge;
import dev.krona.urbex.worldgen.lost.regassets.data.CityStyleSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.TestWiring;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties {@link AssetCompiler} has to hold for a snapshot to be worth publishing (issue
 * #128): every stage compiled before anyone can look anything up, stages ordered so a dependency is
 * present when its dependent compiles, one report for every failure rather than a stop at the first,
 * and the stuff tag order that is an RNG address rather than a presentation detail.
 */
class AssetCompilerTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }


    @Test
    void aStageCanReadWhatAnEarlierStageCompiled() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        RegistryAccess access = registries(
                variant("rubble", "minecraft:deepslate"),
                paletteNamingVariant("walls", 'V', "urbex:rubble"));

        AssetSnapshot snapshot = AssetCompiler.compile(access, diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format("unexpected"));
        assertEquals(1, snapshot.variants().size());
        assertNotNull(snapshot.palettes().get("urbex:walls"), "the palette compiled");
        assertNotNull(snapshot.palettes().getOrThrow("urbex:walls").getPalette().get('V'),
                "and its variant entry resolved against the variants stage above it");
    }

    /**
     * One broken palette is one line in the report and does not stop the stages after it. The
     * registries this replaces threw out of the whole sweep on the first failure.
     */
    @Test
    void oneBrokenAssetDoesNotStopTheRestOfTheCompilation() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        RegistryAccess access = registries(
                variant("rubble", "minecraft:deepslate"),
                paletteNamingVariant("broken", 'V', "urbex:nonexistent"),
                paletteNamingVariant("walls", 'V', "urbex:rubble"));

        AssetSnapshot snapshot = AssetCompiler.compile(access, diagnostics);

        assertEquals(1, diagnostics.size(), () -> diagnostics.format("expected exactly one"));
        assertTrue(diagnostics.problems().getFirst().toString().contains("urbex:broken"),
                () -> "the report names the file that is wrong: " + diagnostics.problems());
        assertNull(snapshot.palettes().get("urbex:broken"), "the broken one is absent");
        assertNotNull(snapshot.palettes().get("urbex:walls"), "the one after it compiled anyway");
        assertEquals(1, snapshot.variants().size(), "and the stage before it is untouched");
    }

    /**
     * The snapshot {@link AssetCompiler#compileWithoutValidation} builds is the same snapshot
     * {@link AssetCompiler#compile} builds. Only the report differs.
     * <p>
     * This is the whole licence for the world-creation preview to take the cheap path: the
     * validation it skips is {@link AssetGraph} and the city-style promotion, both of which write
     * into an {@link AssetDiagnostics} and touch nothing else. If validation ever gains a side
     * effect on the snapshot, this test is what fails.
     */
    @Test
    void skippingValidationChangesTheReportAndNotTheSnapshot() {
        RegistryAccess access = registries(
                variant("rubble", "minecraft:deepslate"),
                paletteNamingVariant("walls", 'V', "urbex:rubble"),
                // Names a city style nothing registers, so the validated path has something to report.
                worldStyleEntry("missing", Optional.empty()));
        AssetDiagnostics diagnostics = new AssetDiagnostics();

        AssetSnapshot validated = AssetCompiler.compile(access, diagnostics);
        AssetSnapshot unvalidated = AssetCompiler.compileWithoutValidation(access);

        assertFalse(diagnostics.isEmpty(), "the validated path has something to say about this pack");
        assertEquals(validated.totalAssets(), unvalidated.totalAssets());
        assertEquals(names(validated.variants()), names(unvalidated.variants()));
        assertEquals(names(validated.palettes()), names(unvalidated.palettes()));
        assertEquals(names(validated.worldStyles()), names(unvalidated.worldStyles()));
        assertEquals(names(validated.cityStyles()), names(unvalidated.cityStyles()));
        assertNotNull(unvalidated.palettes().get("urbex:walls"),
                "and the stages themselves still ran");
    }

    /** Ids, not instances: two compiles of the same registries build equal content, not the same objects. */
    private static List<String> names(AssetIndex<?> index) {
        return index.ids().stream().map(Identifier::toString).sorted().toList();
    }

    /**
     * The stuff tag index is ordered by id, and that order is an RNG slot address:
     * {@code Stuff.generateStuff} walks each tag's list assigning a {@code stuffOrdinal} that every
     * placement attempt draws from. Left in the index's own order it would be {@code Identifier} hash
     * order, so renaming one decoration would relocate every decoration sharing its tag throughout
     * the world.
     */
    @Test
    void stuffSharingATagIsOrderedByIdRatherThanByHash() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        RegistryAccess access = registries();
        MappedRegistry<StuffSettingsDefinition> stuff = new MappedRegistry<>(
                CustomRegistries.STUFF_REGISTRY_KEY, Lifecycle.stable());
        // Registered in reverse order on purpose: if the index leaked registration or hash order,
        // this is what would show it.
        for (String path : List.of("zebra", "cobweb", "anvil")) {
            stuff.register(ResourceKey.create(CustomRegistries.STUFF_REGISTRY_KEY, id(path)),
                    stuffTagged(path, "rubble"), RegistrationInfo.BUILT_IN);
        }
        access = withRegistry(access, stuff.freeze());

        AssetSnapshot snapshot = AssetCompiler.compile(access, diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format("unexpected"));
        assertEquals(List.of("urbex:anvil", "urbex:cobweb", "urbex:zebra"),
                snapshot.stuffFor("rubble").stream().map(StuffObject::getName).toList());
    }

    /**
     * A city style that does not compile is only a load error when something can select it.
     * <p>
     * Requiredness is a property of the end of a chain, and a city style may exist only to be
     * extended: the bundled {@code citystyle_config} declares a street width and nothing else and is
     * complete only through {@code citystyle_common}. An earlier draft of this compiler treated every
     * city-style failure as fatal and refused the shipped pack's own world - the digest run caught it,
     * no unit test did, so here is the unit test.
     * <p>
     * The converse - a style something <em>does</em> select must resolve - is not unit-tested here,
     * because building a preset or a world style to name one takes more scaffolding than the assertion
     * is worth. It is covered end to end on every digest run, where the shipped world style names
     * {@code urbex:citystyle_standard} and the world would refuse to load if it stopped resolving.
     */
    @Test
    void aCityStyleNothingCanSelectIsAllowedToBeIncomplete() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        RegistryAccess access = registries(abstractBaseCityStyle("citystyle_config"));

        AssetSnapshot snapshot = AssetCompiler.compile(access, diagnostics);

        assertTrue(diagnostics.isEmpty(),
                () -> "an unreachable base is not wrong: " + diagnostics.format("reported"));
        assertNull(snapshot.cityStyles().get("urbex:citystyle_config"),
                "it did not compile, which is why nothing may resolve it either");
    }

    @Test
    void aWorldStyleSelectionMakesBothBaseAndEdgeCityStylesReachable() {
        WorldStyle worldStyle = new WorldStyle(id("family_world"), List.of(new WorldStyleDefinition(
                Optional.empty(), Optional.empty(), Optional.of("urbex:outside"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(TestWiring.partSelector()),
                Optional.of(new Mergeable<>(true, List.of(new CityStyleSelector(1.0f, "urbex:base", null,
                        Optional.of(new CityStyleEdge("urbex:edge", 0.4f)))))),
                Optional.empty(), Optional.empty())));

        Set<Identifier> reachable = AssetCompiler.reachableCityStyles(registries(),
                new AssetIndex<>("urbex:worldstyles", Map.of(id("family_world"), worldStyle)));

        assertEquals(Set.of(id("base"), id("edge")), reachable,
                "both members of a selected family must compile and validate eagerly");
    }

    @Test
    void aMissingEdgeCityStyleGetsTheSameLoadDiagnosticAsAMissingBaseCityStyle() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();

        AssetCompiler.compile(registries(worldStyleEntry("base", Optional.of(new CityStyleEdge("urbex:edge", 0.4f)))),
                diagnostics);

        Set<Identifier> missing = diagnostics.problems().stream()
                .filter(problem -> problem.message().contains("is selected by a world style"))
                .map(AssetDiagnostics.Problem::asset)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(id("base"), id("edge")), missing);
    }

    @Test
    void anIncompleteEdgeCityStyleRefusesCompilation() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();

        AssetCompiler.compile(registries(worldStyleEntry("base", Optional.of(new CityStyleEdge("urbex:edge", 0.4f))),
                abstractBaseCityStyle("edge")), diagnostics);

        assertTrue(diagnostics.problems().stream().anyMatch(problem -> id("edge").equals(problem.asset())),
                () -> diagnostics.format("the selected edge must not remain an unreachable incomplete style"));
    }

    /** A tag nothing files under is empty, not null - a pack may ship no stuff for another's tag. */
    @Test
    void aTagNothingIsFiledUnderIsEmptyRatherThanNull() {
        AssetSnapshot snapshot = AssetCompiler.compile(registries(), new AssetDiagnostics());

        assertEquals(List.of(), snapshot.stuffFor("rubble"));
    }

    @Test
    void anEmptySnapshotIsUsableRatherThanNull() {
        AssetSnapshot empty = AssetSnapshot.empty();

        assertEquals(0, empty.totalAssets());
        assertEquals(List.of(), empty.stuffFor("rubble"));
        assertNull(empty.parts().get("urbex:anything"));
    }

    // ---------------------------------------------------------------------------------------
    // Scaffolding: a RegistryAccess holding every registry the compiler walks, empty unless given.
    // ---------------------------------------------------------------------------------------

    private record Entry<T>(ResourceKey<Registry<T>> key, Identifier id, T value) { }

    private static Entry<VariantDefinition> variant(String path, String block) {
        return new Entry<>(CustomRegistries.VARIANTS_REGISTRY_KEY, id(path),
                new VariantDefinition(Optional.empty(),
                        Optional.of(new Mergeable<>(true, List.of(new BlockEntry(1, block))))));
    }

    /**
     * The production path: {@link AssetCompiler} compiling a <em>registered</em> version 2 palette whose
     * {@code $ref} resolves against the <em>world's</em> {@code definitions} registry.
     *
     * <h2>Why this test exists</h2>
     *
     * <p>Before it, the entire {@code AssetCompiler} → {@code V2Palettes} → version 2 path was reached by
     * no test at all. Proved three ways, each leaving the whole suite green: disabling the registry
     * lookup in {@code V2Palettes.definitions} so it always returns an empty index; making
     * {@code V2Palettes.compileV2} throw unconditionally; making the version 2 branch of
     * {@code V2Palettes.compile} throw. The first of those is the bug this branch shipped and fixed in
     * Task 9 — 14 of the 30 bundled palettes carry a {@code $ref}, so it breaks a real world load — and
     * only {@code runDigestCheck}, a manual task in neither {@code test} nor {@code check}, would have
     * caught it.</p>
     *
     * <p>Two things were missing at once and both are fixed here. No test registered a
     * {@link PaletteV2Definition} into the palette registry, so the version 2 branch never ran; and
     * every access a test built listed 13 of the 14 registries and omitted {@code definitions}, so even
     * if one had, the {@code $ref} would have failed with {@code DIAG.030} and the test would have been
     * written around it. {@link TestRegistries} is what stops the second half recurring.</p>
     *
     * <p><b>The assertion that carries the weight is the first one.</b> {@code V2Palettes.definitions}
     * reads the registry with {@code lookup} rather than {@code lookupOrThrow}, deliberately, so an
     * unwired registry produces an <em>empty index</em> and not an error — which means "the palette
     * compiled" is only evidence if the palette could not have compiled without the registry. This one
     * could not: {@code 'R'} is nothing but a {@code $ref} into {@code urbex:rubble}, so an empty index
     * refuses the asset by name and {@code diagnostics} is non-empty.</p>
     */
    @Test
    void aRegisteredVersion2PaletteCompilesThroughTheWorldsDefinitionsRegistry() {
        AssetDiagnostics diagnostics = new AssetDiagnostics();
        RegistryAccess access = registries(
                definitionsAsset("rubble", """
                        { "version": 2, "kind": "weighted", "choices": [
                            { "weight": 1, "block": "minecraft:cobweb" },
                            { "weight": 1, "block": "minecraft:iron_bars" } ] }
                        """),
                paletteV2("walls", """
                        { "version": 2, "palette": {
                            "R": { "$ref": "urbex:rubble" },
                            "S": "minecraft:stone_bricks" } }
                        """));

        AssetSnapshot snapshot = AssetCompiler.compile(access, diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format(
                "the $ref did not resolve, which is what an unwired definitions registry looks like"));

        Palette palette = snapshot.palettes().getOrThrow("urbex:walls");
        CompiledV2Palette compiled = palette.v2();
        assertNotNull(compiled, "AssetCompiler took the version 2 branch of V2Palettes.compile");

        Set<String> drawn = new LinkedHashSet<>();
        CompiledEntry rubble = compiled.entry('R');
        for (int slot = 0; slot < rubble.slotCount(); slot++) {
            drawn.add(rubble.slot(slot).state().getBlock().toString());
        }
        assertEquals(2, drawn.size(),
                () -> "'R' is only a $ref into urbex:rubble, so its two alternatives can only have come"
                        + " from the definitions registry this access carries: " + drawn);
        assertEquals("minecraft:stone_bricks",
                blockOf(compiled.entry('S').slot(0).state()),
                "and the marker that needs no reference compiled beside it");
    }

    private static String blockOf(net.minecraft.world.level.block.state.BlockState state) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** One {@code definitions} asset, decoded from the text a pack would ship. */
    private static Entry<DefinitionAssetDefinition> definitionsAsset(String path, String json) {
        return new Entry<>(CustomRegistries.DEFINITIONS_REGISTRY_KEY, id(path),
                DefinitionAssetDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                        .getOrThrow());
    }

    /** One registered version 2 palette, decoded through the registry's own dispatching codec. */
    private static Entry<PaletteAssetDefinition> paletteV2(String path, String json) {
        return new Entry<>(CustomRegistries.PALETTE_REGISTRY_KEY, id(path),
                PaletteAssetDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                        .getOrThrow());
    }

    private static Entry<PaletteAssetDefinition> paletteNamingVariant(String path, char marker, String variant) {
        PaletteEntry entry = new PaletteEntry(Character.toString(marker), Optional.empty(),
                Optional.of(variant), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        return new Entry<>(CustomRegistries.PALETTE_REGISTRY_KEY, id(path),
                new PaletteDefinition(Optional.empty(), Optional.of(List.of(entry))));
    }

    /** A city style declaring nothing: complete only through a child that extends it. */
    private static Entry<CityStyleDefinition> abstractBaseCityStyle(String path) {
        return new Entry<>(CustomRegistries.CITYSTYLES_REGISTRY_KEY, id(path),
                new CityStyleDefinition(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private static Entry<WorldStyleDefinition> worldStyleEntry(String base, Optional<CityStyleEdge> edge) {
        return new Entry<>(CustomRegistries.WORLDSTYLES_REGISTRY_KEY, id("family_world"),
                new WorldStyleDefinition(Optional.empty(), Optional.empty(), Optional.of("urbex:outside"),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(TestWiring.partSelector()),
                        Optional.of(new Mergeable<>(true, List.of(new CityStyleSelector(1.0f, "urbex:" + base,
                                null, edge)))), Optional.empty(), Optional.empty()));
    }

    private static StuffSettingsDefinition stuffTagged(String path, String tag) {
        return new StuffSettingsDefinition(Optional.empty(),
                Optional.of(new Mergeable<>(true, List.of(tag))),
                Optional.of("\\"),
                Optional.empty(), Optional.empty(),
                Optional.of(1), Optional.of(1), Optional.of(1),
                Optional.of(true), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("urbex", path);
    }

    /**
     * An access holding every Urbex registry, with {@code entries} filed under theirs.
     * <p>
     * The key list this used to hold was 13 of the 14 and omitted {@code definitions}, so nothing
     * reachable from this file could tell {@code V2Palettes.definitions} reading the registry from it
     * finding no registry at all - the two are the same empty index. {@link TestRegistries#keys()}
     * reads {@code CustomRegistries} instead, so a registry cannot be left out of an access built here.
     */
    @SafeVarargs
    private static RegistryAccess registries(Entry<?>... entries) {
        List<Registry<?>> all = new ArrayList<>();
        for (ResourceKey<? extends Registry<?>> key : TestRegistries.keys()) {
            all.add(fill(key, entries));
        }
        RegistryAccess access = TestRegistries.with(all.toArray(new Registry<?>[0]));
        TestRegistries.assertHoldsEveryUrbexRegistry(access);
        return access;
    }

    /** Replaces one registry in an existing access, for a test that builds one separately. */
    private static RegistryAccess withRegistry(RegistryAccess access, Registry<?> replacement) {
        List<Registry<?>> all = new ArrayList<>();
        access.registries().forEach(entry -> {
            if (!entry.key().equals(replacement.key())) {
                all.add(entry.value());
            }
        });
        all.add(replacement);
        return new RegistryAccess.ImmutableRegistryAccess(all).freeze();
    }

    @SuppressWarnings("unchecked")
    private static <T> Registry<T> fill(ResourceKey<? extends Registry<?>> key, Entry<?>[] entries) {
        MappedRegistry<T> registry = new MappedRegistry<>(
                (ResourceKey<Registry<T>>) key, Lifecycle.stable());
        for (Entry<?> entry : entries) {
            if (entry.key().equals(key)) {
                registry.register(ResourceKey.create((ResourceKey<Registry<T>>) key, entry.id()),
                        (T) entry.value(), RegistrationInfo.BUILT_IN);
            }
        }
        return registry.freeze();
    }
}

package dev.krona.urbex.worldgen.lost.cityassets;

import com.mojang.serialization.Lifecycle;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.CityStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.PaletteRE;
import dev.krona.urbex.worldgen.lost.regassets.StuffSettingsRE;
import dev.krona.urbex.worldgen.lost.regassets.VariantRE;
import dev.krona.urbex.worldgen.lost.regassets.data.BlockEntry;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PaletteEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        MappedRegistry<StuffSettingsRE> stuff = new MappedRegistry<>(
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

    private static Entry<VariantRE> variant(String path, String block) {
        return new Entry<>(CustomRegistries.VARIANTS_REGISTRY_KEY, id(path),
                new VariantRE(Optional.empty(),
                        Optional.of(new Mergeable<>(true, List.of(new BlockEntry(1, block))))));
    }

    private static Entry<PaletteRE> paletteNamingVariant(String path, char marker, String variant) {
        PaletteEntry entry = new PaletteEntry(Character.toString(marker), Optional.empty(),
                Optional.of(variant), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        return new Entry<>(CustomRegistries.PALETTE_REGISTRY_KEY, id(path),
                new PaletteRE(Optional.empty(), Optional.of(List.of(entry))));
    }

    /** A city style declaring nothing: complete only through a child that extends it. */
    private static Entry<CityStyleRE> abstractBaseCityStyle(String path) {
        return new Entry<>(CustomRegistries.CITYSTYLES_REGISTRY_KEY, id(path),
                new CityStyleRE(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private static StuffSettingsRE stuffTagged(String path, String tag) {
        return new StuffSettingsRE(Optional.empty(),
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

    @SafeVarargs
    private static RegistryAccess registries(Entry<?>... entries) {
        List<Registry<?>> all = new ArrayList<>();
        for (ResourceKey<? extends Registry<?>> key : List.of(
                CustomRegistries.VARIANTS_REGISTRY_KEY, CustomRegistries.PALETTE_REGISTRY_KEY,
                CustomRegistries.CONDITIONS_REGISTRY_KEY, CustomRegistries.STYLE_REGISTRY_KEY,
                CustomRegistries.PART_REGISTRY_KEY, CustomRegistries.BUILDING_REGISTRY_KEY,
                CustomRegistries.MULTIBUILDINGS_REGISTRY_KEY, CustomRegistries.SCATTERED_REGISTRY_KEY,
                CustomRegistries.WORLDSTYLES_REGISTRY_KEY, CustomRegistries.CITYSTYLES_REGISTRY_KEY,
                CustomRegistries.PREDEFINEDCITIES_REGISTRY_KEY, CustomRegistries.STUFF_REGISTRY_KEY,
                CustomRegistries.PRESET_REGISTRY_KEY)) {
            all.add(fill(key, entries));
        }
        return new RegistryAccess.ImmutableRegistryAccess(all).freeze();
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

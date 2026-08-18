package dev.krona.urbex.worldgen.lost.cityassets;

import com.mojang.serialization.Lifecycle;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.setup.TestRegistries;
import dev.krona.urbex.worldgen.lost.regassets.BuildingPartDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pack with several broken files is diagnosed once, not once per world load (issue #56), and
 * asking for that diagnosis on a running world changes nothing.
 * <p>
 * Both properties are exercised through the {@code parts} registry, whose entries are the cheapest
 * asset to break: a {@code BuildingPartDefinition} declaring no {@code slices} anywhere in its chain is
 * exactly the "a field nothing declares" failure, and it needs no other registry to be populated to
 * reach it - a part naming no {@code refpalette} asks the palettes index for nothing.
 * <p>
 * This was the {@code variants} registry until {@code VER.017} removed it. Parts are the replacement
 * rather than palettes, because a palette is the asset this whole branch is changing and a fixture that
 * moves with its subject stops being a fixture.
 */
class AssetValidationTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }


    @Test
    void everyBrokenAssetIsReportedRatherThanOnlyTheFirst() {
        RegistryAccess access = registriesWithBrokenParts("rubble", "leaves", "gravel");

        AssetDiagnostics diagnostics = new AssetDiagnostics();
        AssetCompiler.compile(access, diagnostics);

        assertEquals(3, diagnostics.size(),
                () -> "all three must be named at once, not one per world load: "
                        + diagnostics.format("found"));
        List<String> lines = diagnostics.problems().stream()
                .map(AssetDiagnostics.Problem::toString).toList();
        assertTrue(lines.stream().allMatch(line -> line.contains("declares no slices")), lines::toString);
        for (String path : List.of("urbex:gravel", "urbex:leaves", "urbex:rubble")) {
            assertTrue(lines.stream().anyMatch(line -> line.contains(path)),
                    () -> path + " is missing from " + lines);
        }
    }

    @Test
    void aPackWithNothingWrongReportsNothing() {
        RegistryAccess access = registriesWithBrokenParts();

        AssetDiagnostics diagnostics = new AssetDiagnostics();
        AssetCompiler.compile(access, diagnostics);

        assertTrue(diagnostics.isEmpty(), () -> diagnostics.format("unexpected"));
    }

    /**
     * Compiling for a report publishes nothing. What {@code /urbex validate} needs is a second,
     * throwaway snapshot: the running world's chunks are generating against theirs, and asking what
     * is wrong must not replace it. There is no longer a static registry to leave alone - that is the
     * point of issue #128 - so what is asserted is that two compiles of the same registries are
     * independent objects rather than one shared, mutable view.
     */
    @Test
    void compilingForAReportPublishesNothing() {
        RegistryAccess access = registriesWithBrokenParts();

        AssetSnapshot first = AssetCompiler.compile(access, new AssetDiagnostics());
        AssetSnapshot second = AssetCompiler.compile(access, new AssetDiagnostics());

        assertNotSame(first, second);
        assertNotSame(first.parts(), second.parts());
    }


    /**
     * A registry access holding every registry {@code validate} walks, all empty except {@code parts},
     * which holds one entry per name given - each declaring no {@code slices}.
     */
    private static RegistryAccess registriesWithBrokenParts(String... brokenPaths) {
        MappedRegistry<BuildingPartDefinition> parts = new MappedRegistry<>(
                CustomRegistries.PART_REGISTRY_KEY, Lifecycle.stable());
        for (String path : brokenPaths) {
            parts.register(
                    ResourceKey.create(CustomRegistries.PART_REGISTRY_KEY,
                            Identifier.fromNamespaceAndPath("urbex", path)),
                    new BuildingPartDefinition(Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                    RegistrationInfo.BUILT_IN);
        }
        // Every Urbex registry, derived from CustomRegistries rather than listed: the list this
        // replaced named 13 of the 14 and omitted 'definitions', under a javadoc claiming "every
        // registry validate walks".
        return TestRegistries.with(parts.freeze());
    }
}

package dev.krona.urbex.worldgen.lost.cityassets;

import com.mojang.serialization.Lifecycle;
import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.worldgen.lost.regassets.VariantRE;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pack with several broken files is diagnosed once, not once per world load (issue #56), and
 * asking for that diagnosis on a running world changes nothing.
 * <p>
 * Both properties are exercised through the {@code variants} registry, whose entries are the
 * cheapest asset to break: a {@code VariantRE} declaring no {@code blocks} anywhere in its chain is
 * exactly the "a field nothing declares" failure {@link Resolved} raises, and it needs no other
 * registry to be populated to reach it.
 */
class AssetValidationTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    @AfterEach
    void clearRegistries() {
        AssetRegistries.reset();
    }

    @Test
    void everyBrokenAssetIsReportedRatherThanOnlyTheFirst() {
        RegistryAccess access = registriesWithBrokenVariants("rubble", "leaves", "gravel");

        AssetDiagnostics diagnostics = AssetRegistries.validate(access);

        assertEquals(3, diagnostics.size(),
                () -> "all three must be named at once, not one per world load: "
                        + diagnostics.format("found"));
        List<String> lines = diagnostics.problems().stream()
                .map(AssetDiagnostics.Problem::toString).toList();
        assertTrue(lines.stream().allMatch(line -> line.contains("declares no 'blocks'")), lines::toString);
        for (String path : List.of("urbex:gravel", "urbex:leaves", "urbex:rubble")) {
            assertTrue(lines.stream().anyMatch(line -> line.contains(path)),
                    () -> path + " is missing from " + lines);
        }
    }

    @Test
    void aPackWithNothingWrongReportsNothing() {
        RegistryAccess access = registriesWithBrokenVariants();

        assertTrue(AssetRegistries.validate(access).isEmpty());
    }

    /**
     * Validation is a throwaway pass. The live compiled assets belong to the loaded world and chunks
     * are generating against them, so asking what is wrong must not populate, replace or clear them -
     * clearing them mid-session is exactly what issue #125 removed.
     */
    @Test
    void validatingLeavesTheLiveRegistriesAlone() {
        RegistryAccess access = registriesWithBrokenVariants("rubble");

        AssetRegistries.validate(access);

        assertFalse(AssetRegistries.isLoaded(), "validation does not latch the registries as loaded");
        assertEquals(List.of(), copyOf(AssetRegistries.VARIANTS.getIterable()),
                "nor cache anything it compiled on the way");
    }

    private static List<Object> copyOf(Iterable<?> iterable) {
        List<Object> copy = new ArrayList<>();
        iterable.forEach(copy::add);
        return copy;
    }

    /**
     * A registry access holding every registry {@code validate} walks, all empty except
     * {@code variants}, which holds one entry per name given - each declaring no {@code blocks}.
     */
    private static RegistryAccess registriesWithBrokenVariants(String... brokenPaths) {
        MappedRegistry<VariantRE> variants = new MappedRegistry<>(
                CustomRegistries.VARIANTS_REGISTRY_KEY, Lifecycle.stable());
        for (String path : brokenPaths) {
            variants.register(
                    ResourceKey.create(CustomRegistries.VARIANTS_REGISTRY_KEY,
                            Identifier.fromNamespaceAndPath("urbex", path)),
                    new VariantRE(Optional.empty(), Optional.empty()),
                    RegistrationInfo.BUILT_IN);
        }
        return new RegistryAccess.ImmutableRegistryAccess(List.of(
                variants.freeze(),
                empty(CustomRegistries.PALETTE_REGISTRY_KEY),
                empty(CustomRegistries.CONDITIONS_REGISTRY_KEY),
                empty(CustomRegistries.STYLE_REGISTRY_KEY),
                empty(CustomRegistries.PART_REGISTRY_KEY),
                empty(CustomRegistries.BUILDING_REGISTRY_KEY),
                empty(CustomRegistries.MULTIBUILDINGS_REGISTRY_KEY),
                empty(CustomRegistries.SCATTERED_REGISTRY_KEY),
                empty(CustomRegistries.WORLDSTYLES_REGISTRY_KEY),
                empty(CustomRegistries.CITYSTYLES_REGISTRY_KEY),
                empty(CustomRegistries.PREDEFINEDCITIES_REGISTRY_KEY),
                empty(CustomRegistries.STUFF_REGISTRY_KEY))).freeze();
    }

    private static <T> Registry<T> empty(ResourceKey<Registry<T>> key) {
        return new MappedRegistry<>(key, Lifecycle.stable()).freeze();
    }
}

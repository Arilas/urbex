package dev.krona.urbex.gui.preview;

import dev.krona.urbex.setup.CustomRegistries;
import dev.krona.urbex.setup.TestRegistries;
import dev.krona.urbex.worldgen.lost.cityassets.AssetSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The preview's asset snapshot is compiled once per set of registries, not once per recompute.
 *
 * <p>Every preset switch, world-style switch and seed edit rebuilds a {@link PreviewContext}, and
 * each one used to compile the whole snapshot again - hundreds of milliseconds on the render thread
 * for a value that is a pure function of registries that had not changed. The registries do change,
 * though: switching datapacks in the create-world screen produces a new {@code RegistryAccess}, and
 * a stale snapshot there would preview a pack the player has just turned off.</p>
 */
class PreviewAssetsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        PreviewAssets.clear();
    }

    @Test
    void theSameRegistriesCompileOnce() {
        RegistryAccess access = registries();

        AssetSnapshot first = PreviewAssets.of(access);
        AssetSnapshot second = PreviewAssets.of(access);

        assertSame(first, second, "a second recompute against the same registries must reuse the snapshot");
    }

    @Test
    void differentRegistriesCompileAgain() {
        AssetSnapshot first = PreviewAssets.of(registries());

        AssetSnapshot second = PreviewAssets.of(registries());

        assertNotSame(first, second, "new registries are a new pack set, so the snapshot must be rebuilt");
    }

    @Test
    void clearingDropsTheSnapshot() {
        RegistryAccess access = registries();
        AssetSnapshot first = PreviewAssets.of(access);

        PreviewAssets.clear();

        assertNotSame(first, PreviewAssets.of(access),
                "clear() must release the snapshot, not just mark it stale");
    }

    @Test
    void noRegistriesIsAnEmptySnapshotRatherThanACompile() {
        AssetSnapshot empty = PreviewAssets.of(null);

        assertSame(0, empty.totalAssets(), "the null-registry fallback compiles nothing");
    }

    /**
     * An access with every Urbex registry present and empty, which is all this cache needs.
     * <p>
     * "Every" is now derived rather than listed: the list this held named 13 of the 14 and left out
     * {@code definitions}, under a javadoc saying exactly this sentence.
     */
    private static RegistryAccess registries() {
        return TestRegistries.with();
    }
}

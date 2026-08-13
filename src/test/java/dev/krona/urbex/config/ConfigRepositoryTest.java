package dev.krona.urbex.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storage and migration, tested without publishing anything.
 * <p>
 * This is what the split is for. Reading a file, migrating an older format and merging a world's
 * overrides used to be methods on {@code Config} that wrote to its static slots on the way through,
 * so a test of the file handling was a test of the whole process-wide configuration state - which is
 * why {@code ExperimentalMixGateTest} has to reset that state in a {@code @BeforeEach} and say so
 * (issue #130). Nothing here touches anything outside its temporary directory.
 */
class ConfigRepositoryTest {

    @Test
    void anAbsentConfigYieldsTheDefaultsAndWritesThemOut(@TempDir Path configDir) throws IOException {
        UrbexConfig loaded = ConfigRepository.loadGlobal(configDir);

        assertEquals(UrbexConfig.DEFAULT, loaded);
        Path written = configDir.resolve("urbex").resolve("urbex.json");
        assertTrue(Files.exists(written), "the file is written back so its options are discoverable");
        assertTrue(Files.readString(written).contains("experimentalMultiWorldStyles"),
                "including keys still at their default - that is what makes the file document "
                        + "itself, and what it did not do while writing only the differences");
    }

    @Test
    void aValidConfigIsReadBack(@TempDir Path configDir) throws IOException {
        writeGlobal(configDir, "{\"heightSampleSize\": 7, \"avoidVillages\": false}");

        UrbexConfig loaded = ConfigRepository.loadGlobal(configDir);

        assertEquals(7, loaded.heightSampleSize());
        assertFalse(loaded.avoidVillages());
        assertEquals(UrbexConfig.DEFAULT.todoQueueSize(), loaded.todoQueueSize(),
                "a key the file does not mention keeps its default");
    }

    @Test
    void anUnparseableConfigLeavesTheDefaultsRatherThanRefusingToStart(@TempDir Path configDir) throws IOException {
        // Out of range for the codec's intRange(1, 100). A settings file nobody can parse is not a
        // reason a player cannot open their world - unlike a datapack, there is something sensible
        // to fall back to.
        writeGlobal(configDir, "{\"heightSampleSize\": 9999}");

        assertEquals(UrbexConfig.DEFAULT, ConfigRepository.loadGlobal(configDir));
    }

    @Test
    void aLegacyTomlIsMigratedOnFirstRun(@TempDir Path configDir) throws IOException {
        Path dir = Files.createDirectories(configDir.resolve("urbex"));
        Files.writeString(dir.resolve("common.toml"), String.join("\n", List.of(
                "heightSampleSize = 9",
                "avoidFlattening = false")));

        UrbexConfig loaded = ConfigRepository.loadGlobal(configDir);

        assertEquals(9, loaded.heightSampleSize());
        assertFalse(loaded.avoidFlattening());
        assertTrue(Files.exists(dir.resolve("urbex.json")),
                "the migrated values are written as JSON, so the TOML is read once and never again");
    }

    // ------------------------------------------------------------------ world overrides

    @Test
    void aWorldWithNoOverridesKeepsTheGlobalConfigItself(@TempDir Path worldRoot) {
        UrbexConfig global = UrbexConfig.DEFAULT;

        assertSame(global, ConfigRepository.applyWorldOverrides(global, worldRoot),
                "no file means no decision to make, not a rebuild of the same values");
    }

    @Test
    void aWorldOverridesOnlyTheKeysItNames(@TempDir Path worldRoot) throws IOException {
        UrbexConfig global = ConfigRepository.applyWorldOverrides(UrbexConfig.DEFAULT, worldRoot);
        writeWorld(worldRoot, "{\"selectedPreset\": \"urbex:largecities\"}");

        UrbexConfig merged = ConfigRepository.applyWorldOverrides(global, worldRoot);

        assertEquals("urbex:largecities", merged.selectedPreset());
        assertEquals(global.heightSampleSize(), merged.heightSampleSize(),
                "everything the world file does not mention comes from the global config");
    }

    @Test
    void anUnparseableWorldFileIsIgnoredRatherThanTakingTheWorldDown(@TempDir Path worldRoot) throws IOException {
        writeWorld(worldRoot, "{\"cacheCleanupSeconds\": -5}");

        assertSame(UrbexConfig.DEFAULT,
                ConfigRepository.applyWorldOverrides(UrbexConfig.DEFAULT, worldRoot));
    }

    @Test
    void aLegacyWorldTomlIsMigratedAndNotReadTwice(@TempDir Path worldRoot) throws IOException {
        Path dir = Files.createDirectories(worldRoot.resolve("serverconfig"));
        Files.writeString(dir.resolve("urbex-server.toml"), "todoQueueSize = 42");

        UrbexConfig merged = ConfigRepository.applyWorldOverrides(UrbexConfig.DEFAULT, worldRoot);

        assertEquals(42, merged.todoQueueSize());
        assertTrue(Files.exists(dir.resolve("urbex.json")),
                "written on migration, or the migration would run again on every start");
    }

    /**
     * The world file is the player's own list of differences. Normalizing it the way the global file
     * is normalized would fill it with every key they did not ask to change, and the next global
     * default change would then silently not reach that world.
     */
    @Test
    void anExistingWorldFileIsNotRewritten(@TempDir Path worldRoot) throws IOException {
        writeWorld(worldRoot, "{\"todoQueueSize\": 42}");
        Path file = worldRoot.resolve("serverconfig").resolve("urbex.json");
        String before = Files.readString(file);

        ConfigRepository.applyWorldOverrides(UrbexConfig.DEFAULT, worldRoot);

        assertEquals(before, Files.readString(file));
    }

    private static void writeGlobal(Path configDir, String json) throws IOException {
        Path dir = Files.createDirectories(configDir.resolve("urbex"));
        Files.writeString(dir.resolve("urbex.json"), json);
    }

    private static void writeWorld(Path worldRoot, String json) throws IOException {
        Path dir = Files.createDirectories(worldRoot.resolve("serverconfig"));
        Files.writeString(dir.resolve("urbex.json"), json);
    }
}

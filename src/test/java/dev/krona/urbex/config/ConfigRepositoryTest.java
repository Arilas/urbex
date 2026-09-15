package dev.krona.urbex.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON storage, tested without publishing anything.
 * <p>
 * This is what the split is for. Reading a file and merging a world's
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
        String invalid = "{\"heightSampleSize\": 9999, \"selectedPreset\": \"urbex:largecities\"}";
        writeGlobal(configDir, invalid);

        assertEquals(UrbexConfig.DEFAULT, ConfigRepository.loadGlobal(configDir));
        assertEquals(invalid, Files.readString(configDir.resolve("urbex").resolve("urbex.json")),
                "using defaults for this run must not erase settings the player can repair");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", ""})
    void anUnreadableJsonObjectIsPreserved(String invalid, @TempDir Path configDir) throws IOException {
        writeGlobal(configDir, invalid);

        assertEquals(UrbexConfig.DEFAULT, ConfigRepository.loadGlobal(configDir));
        assertEquals(invalid, Files.readString(configDir.resolve("urbex").resolve("urbex.json")));
    }

    @Test
    void anObsoleteTomlDoesNotReplaceTheJsonDefaults(@TempDir Path configDir) throws IOException {
        Path dir = Files.createDirectories(configDir.resolve("urbex"));
        String obsolete = "heightSampleSize = 9\navoidFlattening = false";
        Files.writeString(dir.resolve("common.toml"), obsolete);

        UrbexConfig loaded = ConfigRepository.loadGlobal(configDir);

        assertEquals(UrbexConfig.DEFAULT, loaded);
        assertTrue(Files.exists(dir.resolve("urbex.json")));
        assertEquals(obsolete, Files.readString(dir.resolve("common.toml")),
                "obsolete files are ignored, not deleted or rewritten");
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
        UrbexConfig global = UrbexConfig.fromJson(JsonParser.parseString(
                "{\"heightSampleSize\": 7, \"todoQueueSize\": 42}").getAsJsonObject()).orElseThrow();
        writeWorld(worldRoot, "{\"selectedPreset\": \"urbex:largecities\", \"todoQueueSize\": 20}");

        UrbexConfig merged = ConfigRepository.applyWorldOverrides(global, worldRoot);

        assertEquals("urbex:largecities", merged.selectedPreset());
        assertEquals(global.heightSampleSize(), merged.heightSampleSize(),
                "everything the world file does not mention comes from the global config");
        assertEquals(UrbexConfig.DEFAULT.todoQueueSize(), merged.todoQueueSize(),
                "an explicit world override can restore a key to its code default");
    }

    @Test
    void anUnparseableWorldFileIsIgnoredRatherThanTakingTheWorldDown(@TempDir Path worldRoot) throws IOException {
        writeWorld(worldRoot, "{\"cacheCleanupSeconds\": -5}");

        assertSame(UrbexConfig.DEFAULT,
                ConfigRepository.applyWorldOverrides(UrbexConfig.DEFAULT, worldRoot));
    }

    @Test
    void anObsoleteWorldTomlDoesNotCreateOverrides(@TempDir Path worldRoot) throws IOException {
        Path dir = Files.createDirectories(worldRoot.resolve("serverconfig"));
        String obsolete = "todoQueueSize = 42";
        Files.writeString(dir.resolve("urbex-server.toml"), obsolete);

        UrbexConfig merged = ConfigRepository.applyWorldOverrides(UrbexConfig.DEFAULT, worldRoot);

        assertSame(UrbexConfig.DEFAULT, merged);
        assertFalse(Files.exists(dir.resolve("urbex.json")));
        assertEquals(obsolete, Files.readString(dir.resolve("urbex-server.toml")));
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

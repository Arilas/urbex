package dev.krona.urbex.setup;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code experimentalMultiWorldStyles} gates the value, not just the UI: a config line hand-written
 * with several styles must not quietly get them on an install that never opted in. Exercised
 * through {@link Config#loadGlobal} against a real config file rather than by poking the active
 * config, because that write-then-read is the path a server owner actually takes.
 */
class ExperimentalMixGateTest {

    private static final String MIXED_ENTRY =
            "minecraft:overworld=urbex:default@urbex:standard*0.1+urbexmt:moderntweaks*0.9";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void restoreDefaults(@TempDir Path clean) throws IOException {
        // loadGlobal replaces the process-wide active config, so leaving it on would leak the
        // opt-in into every test that runs after this class.
        writeConfig(clean, false);
        Config.loadGlobal(clean);
        Config.reset();
    }

    private static void writeConfig(Path dir, boolean experimental) throws IOException {
        Path configDir = dir.resolve("urbex");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("urbex.json"),
                "{\"experimentalMultiWorldStyles\": " + experimental + "}");
    }

    private static PresetChoice parse(String entry) {
        Optional<Map.Entry<ResourceKey<Level>, PresetChoice>> parsed = Config.parseDimensionPresetEntry(entry);
        assertTrue(parsed.isPresent(), "entry should parse: " + entry);
        assertEquals(ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld")),
                parsed.get().getKey());
        return parsed.get().getValue();
    }

    @Test
    void withTheFlagOffAMixedEntryKeepsOnlyItsHeaviestStyle(@TempDir Path dir) throws IOException {
        writeConfig(dir, false);
        Config.loadGlobal(dir);
        assertFalse(Config.EXPERIMENTAL_MULTI_WORLD_STYLES.get());

        WorldStyleMix styles = parse(MIXED_ENTRY).worldStyles();
        assertTrue(styles.isSingle());
        assertEquals(Identifier.fromNamespaceAndPath("urbexmt", "moderntweaks"), styles.primary());
    }

    @Test
    void withTheFlagOnTheWholeMixSurvives(@TempDir Path dir) throws IOException {
        writeConfig(dir, true);
        Config.loadGlobal(dir);
        assertTrue(Config.EXPERIMENTAL_MULTI_WORLD_STYLES.get());

        WorldStyleMix styles = parse(MIXED_ENTRY).worldStyles();
        assertFalse(styles.isSingle());
        assertEquals(2, styles.entries().size());
        assertEquals(Identifier.fromNamespaceAndPath("urbex", "standard"), styles.entries().get(0).style());
        assertEquals(0.1f, styles.entries().get(0).weight());
        assertEquals(Identifier.fromNamespaceAndPath("urbexmt", "moderntweaks"), styles.entries().get(1).style());
        assertEquals(0.9f, styles.entries().get(1).weight());
        assertEquals(Identifier.fromNamespaceAndPath("urbexmt", "moderntweaks"), styles.primary());
    }

    @Test
    void aSingleStyleEntryIsUntouchedByTheGate(@TempDir Path dir) throws IOException {
        writeConfig(dir, false);
        Config.loadGlobal(dir);
        assertEquals(WorldStyleMix.of(Identifier.fromNamespaceAndPath("urbex", "standard")),
                parse("minecraft:overworld=urbex:default@urbex:standard").worldStyles());
    }
}

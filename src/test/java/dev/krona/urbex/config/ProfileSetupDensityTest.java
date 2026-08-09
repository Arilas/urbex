package dev.krona.urbex.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileSetupDensityTest {

    private static final Map<String, float[]> EXPECTED = Map.ofEntries(
            Map.entry("default", new float[]{0.15f, 0.65f}),
            Map.entry("nodamage", new float[]{0.15f, 0.65f}),
            Map.entry("floating", new float[]{0.15f, 0.65f}),
            Map.entry("rarecities", new float[]{0.15f, 0.65f}),
            Map.entry("onlycities", new float[]{0.15f, 0.65f}),
            Map.entry("tallbuildings", new float[]{0.15f, 0.65f}),
            Map.entry("atlantis", new float[]{0.15f, 0.65f}),
            Map.entry("cavern", new float[]{0.65f, 0.65f}),
            Map.entry("biosphere_caves", new float[]{0.65f, 0.65f}),
            Map.entry("space", new float[]{0.50f, 0.65f}),
            Map.entry("biosphere", new float[]{0.50f, 0.65f}),
            Map.entry("largecities", new float[]{0.35f, 0.65f}),
            Map.entry("ancient", new float[]{0.05f, 0.40f}),
            Map.entry("wasteland", new float[]{0.05f, 0.40f}),
            Map.entry("bio_wasteland", new float[]{0.05f, 0.40f}),
            Map.entry("safe", new float[]{1.00f, 0.00f}),
            Map.entry("void_outside", new float[]{0.00f, 0.00f})
    );

    @Test
    void standardProfilesUseApprovedDensityMatrix() {
        ProfileSetup.initStandardProfiles();

        assertEquals(EXPECTED.size(), ProfileSetup.STANDARD_PROFILES.size());
        for (Map.Entry<String, float[]> entry : EXPECTED.entrySet()) {
            LostCityProfile profile = ProfileSetup.STANDARD_PROFILES.get(entry.getKey());
            assertNotNull(profile, entry.getKey());
            assertArrayEquals(entry.getValue(), new float[]{profile.LIGHTING_DENSITY, profile.LOOT_DENSITY}, entry.getKey());
        }
    }

    @Test
    void profileSeedingPreservesUserFilesAndRegeneratesDefaults(@TempDir Path tempDir) throws IOException {
        ProfileSetup.initStandardProfiles();
        Path profileDir = tempDir.resolve("profiles");
        Files.createDirectories(profileDir);
        Path userDefault = profileDir.resolve("default.json");
        Files.writeString(userDefault, "sentinel");

        ProfileSetup.writeProfileFiles(profileDir);

        assertEquals("sentinel", Files.readString(userDefault));
        String defaultReference = Files.readString(profileDir.resolve("defaults/default.json"));
        assertTrue(defaultReference.contains("lightingDensity"));
        assertTrue(defaultReference.contains("lootDensity"));
    }
}

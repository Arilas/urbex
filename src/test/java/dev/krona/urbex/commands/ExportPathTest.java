package dev.krona.urbex.commands;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ExportPathTest {

    private static final Path BASE = Path.of("config", "urbex", "profiles");

    @Test
    public void resolvesPlainNameInsideBaseWithJsonSuffix() {
        Path result = ExportPath.resolve(BASE, "default");
        assertEquals(BASE.resolve("default.json"), result);
    }

    @Test
    public void allowsLettersDigitsUnderscoreAndHyphen() {
        Path result = ExportPath.resolve(BASE, "my-Profile_2");
        assertEquals(BASE.resolve("my-Profile_2.json"), result);
    }

    @Test
    public void rejectsDotsSoServerJsonFilesCannotBeTargeted() {
        // "whitelist.json" would escape the .json suffix scheme: it must not
        // silently become whitelist.json.json either - reject loudly.
        assertThrows(IllegalArgumentException.class, () -> ExportPath.resolve(BASE, "whitelist.json"));
    }

    @Test
    public void rejectsParentDirectoryTraversal() {
        assertThrows(IllegalArgumentException.class, () -> ExportPath.resolve(BASE, ".."));
    }

    @Test
    public void rejectsEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> ExportPath.resolve(BASE, ""));
    }

    @Test
    public void rejectsPathSeparators() {
        // brigadier word() cannot produce these today, but the helper must not
        // rely on the argument type for safety
        assertThrows(IllegalArgumentException.class, () -> ExportPath.resolve(BASE, "a/b"));
        assertThrows(IllegalArgumentException.class, () -> ExportPath.resolve(BASE, "a\\b"));
    }

    @Test
    public void rejectsPlusAndOtherWordCharsOutsideAllowlist() {
        // '+' is legal in brigadier word() but stays outside the allowlist
        assertThrows(IllegalArgumentException.class, () -> ExportPath.resolve(BASE, "a+b"));
    }
}

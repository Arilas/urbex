package dev.krona.urbex.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Pins every fail-soft override boundary to the strict retired-key parser and marker rethrow. */
class PresetOverrideDecodeSitesTest {

    private static final Path MAIN = Path.of("src/main/java/dev/krona/urbex");
    private static final Pattern CENTRAL_PARSE = Pattern.compile("PresetDefinition\\s*\\.\\s*parseOverrides\\s*\\(");
    private static final Pattern DIRECT_PARSE = Pattern.compile("PresetDefinition\\s*\\.\\s*CODEC\\s*\\.\\s*parse\\s*\\(");
    private static final Pattern MARKER_RETHROW = Pattern.compile(
            "catch\\s*\\(\\s*RetiredPresetKeyException\\s+(\\w+)\\s*\\)\\s*\\{\\s*throw\\s+\\1\\s*;\\s*}\\s*catch\\s*\\(\\s*Exception\\s+\\w+\\s*\\)",
            Pattern.DOTALL);

    @Test
    void everyOverrideBoundaryUsesTheCentralParserAndRethrowsRetiredKeys() throws IOException {
        Map<String, Integer> expectedSites = new LinkedHashMap<>();
        expectedSites.put("gui/PresetSelection.java", 2);
        expectedSites.put("setup/Config.java", 1);
        expectedSites.put("worldgen/DimensionRuntime.java", 1);

        for (Map.Entry<String, Integer> entry : expectedSites.entrySet()) {
            String source = Files.readString(MAIN.resolve(entry.getKey()));
            assertEquals(entry.getValue(), matches(CENTRAL_PARSE, source),
                    entry.getKey() + " must route every override decode through PresetDefinition.parseOverrides");
            assertEquals(entry.getValue(), matches(MARKER_RETHROW, source),
                    entry.getKey() + " must rethrow RetiredPresetKeyException before its fail-soft catch");
            assertFalse(DIRECT_PARSE.matcher(source).find(),
                    entry.getKey() + " still decodes override JSON through PresetDefinition.CODEC directly");
        }
    }

    private static int matches(Pattern pattern, String source) {
        int count = 0;
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}

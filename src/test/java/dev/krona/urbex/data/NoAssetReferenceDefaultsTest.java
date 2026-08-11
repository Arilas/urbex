package dev.krona.urbex.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No asset reference may have a code-side default: a reference no datapack file wrote is
 * unfindable when it misbehaves, and it is what let a third-party world style silently inherit
 * Urbex's own wide-road parts for road classes it never mentioned.
 */
class NoAssetReferenceDefaultsTest {

    private static final Path DATA = Path.of("src/main/java/dev/krona/urbex/worldgen/lost/regassets/data");

    /** listOrStringList("field", "some_default", Getter::x) - three arguments means a default. */
    private static final Pattern WITH_DEFAULT =
            Pattern.compile("listOrStringList\\(\\s*\"[^\"]+\"\\s*,\\s*\"[^\"]+\"\\s*,");

    @Test
    void noWiringFieldCarriesADefaultAssetName() throws IOException {
        List<String> problems = new ArrayList<>();
        try (var files = Files.walk(DATA)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    Matcher m = WITH_DEFAULT.matcher(Files.readString(p));
                    while (m.find()) {
                        problems.add(p + ": " + m.group());
                    }
                } catch (IOException e) {
                    problems.add(p + ": unreadable: " + e.getMessage());
                }
            });
        }
        assertTrue(problems.isEmpty(),
                () -> "asset reference fields with code-side defaults:\n" + String.join("\n", problems));
    }
}

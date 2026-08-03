package dev.krona.urbex.plan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan module's whole value is that it can be iterated in seconds without Minecraft. That
 * property does not survive contact with a codebase unless something enforces it: one convenient
 * import of a Minecraft type, and the module needs a game to test.
 */
class PurityTest {

    private static final Path PLAN_SRC = Path.of("src/main/java/dev/krona/urbex/plan");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)");

    private static final List<String> ALLOWED_PREFIXES = List.of(
            "dev.krona.urbex.plan.",
            "java."
    );

    @Test
    void planModuleImportsNothingOutsideItselfAndTheJdk() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PLAN_SRC)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    Matcher m = IMPORT.matcher(line.strip());
                    if (!m.find()) {
                        continue;
                    }
                    String imported = m.group(1);
                    boolean allowed = ALLOWED_PREFIXES.stream().anyMatch(imported::startsWith);
                    if (!allowed) {
                        violations.add(file.getFileName() + " imports " + imported);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "plan must not depend on anything outside itself and the JDK:\n  "
                        + String.join("\n  ", violations));
    }
}

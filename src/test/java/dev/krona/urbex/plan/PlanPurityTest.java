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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the invariant {@link Hash}'s javadoc calls "the purity test": {@code dev.krona.urbex.plan}
 * and its sub-packages must never import Minecraft. The road field, its hashing and its settings
 * validation exist specifically to be pure functions of (seed, dimension id, coordinates, settings)
 * that a plain unit test can exercise with no game running (design spec
 * {@code 2026-08-10-urbex-hierarchical-streets-design.md} sections 1.1 and 3.1-3.2) - that is the
 * whole reason {@link RoadField} is a seam a future terrain-aware planner can implement without
 * touching the renderer. Nothing checked this before this test existed: no ArchUnit dependency, no
 * checkstyle rule, no import-scanning test of any kind, so a {@code net.minecraft.*} import could
 * land in a {@code plan} file and every other test would keep passing while the property this
 * package exists to guarantee quietly stopped holding.
 *
 * <p>A straight import-line scan needs no special case for the module's one documented reach
 * towards Minecraft-adjacent code: {@link dev.krona.urbex.plan.grid.GridSettings#fromPreset} takes
 * a {@code dev.krona.urbex.config.Preset} parameter, but writes that type fully-qualified in
 * the method signature rather than importing it, specifically so a scan like this one has nothing to
 * exclude. There is deliberately no allowlist below - if that ever stops being true, this test is
 * supposed to fail, not learn to ignore the new import.
 */
class PlanPurityTest {

    private static final Path PLAN_ROOT = Path.of("src/main/java/dev/krona/urbex/plan");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");

    @Test
    void noFileUnderThePlanPackageImportsMinecraft() throws IOException {
        List<Path> javaFiles = listJavaFiles();
        // A scan that silently finds nothing proves nothing - assert the walk actually reached the
        // package's files, so a moved or renamed root fails loudly here instead of leaving this test
        // green for the wrong reason.
        assertFalse(javaFiles.isEmpty(), "expected to find at least one .java file under " + PLAN_ROOT
                + ", but found none - the scan path is probably wrong");

        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles) {
            String source = Files.readString(file);
            Matcher matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (imported.startsWith("net.minecraft.") || imported.equals("net.minecraft")) {
                    offenders.add(file + " imports " + imported);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "dev.krona.urbex.plan is the dependency-free planning module every other worldgen "
                        + "piece depends on, never the reverse - it must import nothing from "
                        + "net.minecraft. Offending imports:\n" + String.join("\n", offenders));
    }

    private static List<Path> listJavaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(PLAN_ROOT)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}

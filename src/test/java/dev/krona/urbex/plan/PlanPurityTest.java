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
 * and its sub-packages may depend on {@code java.*} and on themselves, and on nothing else.
 *
 * <p>The road field, its hashing and its settings validation exist specifically to be pure functions
 * of (seed, dimension id, coordinates, settings) that a plain unit test can exercise with no game
 * running (design spec {@code 2026-08-10-urbex-hierarchical-streets-design.md} sections 1.1 and
 * 3.1-3.2) - that is the whole reason {@link RoadField} is a seam a future terrain-aware planner can
 * implement without touching the renderer. Nothing checked this before this test existed: no ArchUnit
 * dependency, no checkstyle rule, no import-scanning test of any kind, so a {@code net.minecraft.*}
 * import could land in a {@code plan} file and every other test would keep passing while the property
 * this package exists to guarantee quietly stopped holding.
 *
 * <h2>Why it also reads the source text</h2>
 *
 * <p>An import scan alone was not enough, and the package's own code proved it: {@code GridSettings}
 * carried a {@code fromPreset(dev.krona.urbex.config.Preset)} factory whose parameter type was
 * written out fully-qualified <em>specifically so that a scan like this one had nothing to
 * exclude</em>, with a comment saying as much. The reach was real - the planning module knew about
 * the configuration module - and the check saw nothing. The conversion lives on the configuration
 * side now, as {@code PresetRoadGrid} (issue #129), and the second test below closes the loophole
 * rather than trusting the next author to remember the first one.
 *
 * <p>There is deliberately no allowlist. {@code javax.annotation.Nullable} is the one non-{@code java}
 * name the package uses, and it is permitted below by an explicit, narrow exception with its reason
 * written down - if anything else ever needs one, this test is supposed to fail and the exception is
 * supposed to be argued, not widened.
 */
class PlanPurityTest {

    private static final Path PLAN_ROOT = Path.of("src/main/java/dev/krona/urbex/plan");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");
    /**
     * Any fully-qualified reference to a package of this mod outside {@code plan}, anywhere in the
     * source text - which is how the one real violation was written. Matched with a leading
     * non-word boundary so an import line (already covered above) and a javadoc {@code @link} both
     * count: a {@code @link} to another module is a documentation reference, not a dependency, but
     * there is currently none, and one appearing is worth a second look either way.
     */
    private static final Pattern FOREIGN_MOD_REFERENCE =
            Pattern.compile("dev\\.krona\\.urbex\\.(?!plan\\b)\\w+");
    /** Any reference to Minecraft at all, qualified or imported. */
    private static final Pattern MINECRAFT_REFERENCE = Pattern.compile("net\\.minecraft\\b");

    /**
     * The one permitted non-{@code java} import: a nullability annotation. It has no runtime
     * behaviour, resolves to nothing at execution time, and is not a dependency on the game or the
     * configuration - which is what this package is being kept free of.
     */
    private static final String ALLOWED_ANNOTATION_PACKAGE = "javax.annotation.";

    @Test
    void everyImportUnderThePlanPackageIsJavaOrPlanItself() throws IOException {
        List<Path> javaFiles = listJavaFiles();
        // A scan that silently finds nothing proves nothing - assert the walk actually reached the
        // package's files, so a moved or renamed root fails loudly here instead of leaving this test
        // green for the wrong reason.
        assertFalse(javaFiles.isEmpty(), "expected to find at least one .java file under " + PLAN_ROOT
                + ", but found none - the scan path is probably wrong");

        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles) {
            Matcher matcher = IMPORT.matcher(Files.readString(file));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (imported.startsWith("java.") || imported.startsWith("dev.krona.urbex.plan.")
                        || imported.startsWith(ALLOWED_ANNOTATION_PACKAGE)) {
                    continue;
                }
                offenders.add(file + " imports " + imported);
            }
        }
        assertTrue(offenders.isEmpty(),
                "dev.krona.urbex.plan is the dependency-free planning module every other worldgen "
                        + "piece depends on, never the reverse - it may import java.* and itself, and "
                        + "nothing else. Offending imports:\n" + String.join("\n", offenders));
    }

    @Test
    void noFileUnderThePlanPackageNamesMinecraftOrAnotherModulePackageInItsText() throws IOException {
        List<Path> javaFiles = listJavaFiles();
        assertFalse(javaFiles.isEmpty(), "expected to find at least one .java file under " + PLAN_ROOT);

        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles) {
            String source = Files.readString(file);
            collect(offenders, file, source, MINECRAFT_REFERENCE);
            collect(offenders, file, source, FOREIGN_MOD_REFERENCE);
        }
        assertTrue(offenders.isEmpty(),
                "a fully-qualified name reaches just as far as an import does, and writing one is how "
                        + "the previous version of this rule was worked around. Offending references:\n"
                        + String.join("\n", offenders));
    }

    private static void collect(List<String> offenders, Path file, String source, Pattern pattern) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            offenders.add(file + " names " + matcher.group() + " at offset " + matcher.start());
        }
    }

    private static List<Path> listJavaFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(PLAN_ROOT)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}

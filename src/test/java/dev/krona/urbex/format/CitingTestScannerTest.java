package dev.krona.urbex.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code SpecDocuments.scanCitingTestsInFile}'s comment-stripping against a real bug: a
 * {@code //} that is not a comment - a URL inside a string or javadoc text - used to be
 * indistinguishable from an actual line comment once the block-comment and line-comment patterns
 * shared one {@link java.util.regex.Pattern#DOTALL} alternation, and {@code DOTALL} let the
 * line-comment branch's greedy {@code .*} consume every line after it, deleting real code including
 * any citation annotation that followed. This exercises the fix directly against a synthetic file
 * written to a {@code @TempDir}, rather than a fixture living in the real {@code src/test/java} tree.
 * <p>
 * That choice of a synthetic, out-of-tree file is not just convenience: {@code SpecDocuments.load()}
 * scans every {@code .java} file under {@code src/test/java}, including this one, by raw text, with
 * no notion of what a Java string literal is. An earlier draft of this test wrote the fixture source
 * directly into a text block, and its literal {@code @Rule("REF.032")} - meant only as data describing
 * what the scanner should find in the file it builds - was itself picked up as a real citation the
 * next time anyone ran {@code ./gradlew regenerateConformance}, attributing REF.032 to a test that
 * does not actually exercise it. The fix is below: the {@code @} is injected via
 * {@link String#formatted} rather than written contiguously with {@code Rule(...)}, so this file's
 * own checked-in text never contains the substring {@code @Rule(} for the scanner to find twice.
 */
class CitingTestScannerTest {

    /** See the class javadoc: keeps this file's own source from citing anything, even by accident. */
    private static final String AT = "@";

    @Test
    void aUrlBeforeACitationDoesNotSwallowIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Example.java");
        Files.writeString(file, """
                package dev.krona.urbex.format;

                import org.junit.jupiter.api.Test;

                class Example {
                    // see https://example.com/reference for background - the "//" here is not the
                    // start of a citation, it is a URL, and must not swallow the method below.
                    %sRule("REF.032")
                    @Test
                    void aReferenceCycleIsRefusedNamingEveryNodeInIt() {
                    }
                }
                """.formatted(AT));

        Map<String, List<String>> citingTests = new LinkedHashMap<>();
        SpecDocuments.scanCitingTestsInFile(file, citingTests);

        assertTrue(citingTests.containsKey("REF.032"),
                "expected REF.032 to be found; the URL line must have swallowed it - got: " + citingTests);
        assertEquals(List.of("Example.aReferenceCycleIsRefusedNamingEveryNodeInIt"), citingTests.get("REF.032"));
    }

    @Test
    void aBlockCommentSpanningLinesIsStillStripped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Example.java");
        Files.writeString(file, """
                package dev.krona.urbex.format;

                class Example {
                    /*
                     * %sRule("A.999") would be a false citation if this block comment were not
                     * stripped as a whole - see https://example.com too, for good measure.
                     */
                    %sRule("REF.032")
                    void aReferenceCycleIsRefusedNamingEveryNodeInIt() {
                    }
                }
                """.formatted(AT, AT));

        Map<String, List<String>> citingTests = new LinkedHashMap<>();
        SpecDocuments.scanCitingTestsInFile(file, citingTests);

        assertEquals(Map.of("REF.032", List.of("Example.aReferenceCycleIsRefusedNamingEveryNodeInIt")),
                citingTests);
    }
}

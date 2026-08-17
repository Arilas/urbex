package dev.krona.urbex.format;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift-guards {@code docs/format/palette/conformance.md} against the specification documents it is
 * generated from - the same shape of check {@link dev.krona.urbex.config.PresetSchemaTest} already
 * runs for {@code docs/schema/preset.schema.json}, applied here to the palette v2 rule set instead of
 * a codec's key sets.
 * <p>
 * Every assertion below is one of the promises {@code docs/format/README.md} §5 makes about this
 * index: that it reflects the documents exactly, that nothing it lists points at something undefined,
 * and that nothing undefined points back at it. Each test loads its own {@link SpecDocuments} rather
 * than sharing one instance, so a test failure's stack trace lands on the assertion that actually
 * failed instead of on a shared {@code @BeforeAll} a reader has to trace back through.
 */
class ConformanceIndexTest {

    @Test
    void everyCitedRuleIdentifierIsDefined() {
        SpecDocuments spec = SpecDocuments.load();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : spec.proseCitations().entrySet()) {
            if (!spec.rules().containsKey(entry.getKey())) {
                failures.add(entry.getValue().get(0) + ": cites " + entry.getKey()
                        + ", which no document defines");
            }
        }
        for (Map.Entry<String, List<String>> entry : spec.citingTests().entrySet()) {
            if (!spec.rules().containsKey(entry.getKey())) {
                failures.add(entry.getValue().get(0) + ": cites " + entry.getKey()
                        + ", which no document defines - a stale test");
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    @Test
    void everyRejectRuleCitesADefinedDiagnostic() {
        SpecDocuments spec = SpecDocuments.load();
        List<String> failures = new ArrayList<>();
        for (SpecDocuments.SpecRule rule : spec.rules().values()) {
            if (!"REJECT".equals(rule.cls())) {
                continue;
            }
            if (rule.diag().isEmpty()) {
                failures.add(rule.file() + ": " + rule.id() + " is REJECT but cites no DIAG");
            } else if (!spec.rules().containsKey(rule.diag().get())) {
                failures.add(rule.file() + ": " + rule.id() + " cites " + rule.diag().get()
                        + ", which the catalogue does not define");
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    @Test
    void everyFixtureCitesADefinedRule() {
        SpecDocuments spec = SpecDocuments.load();
        List<String> failures = new ArrayList<>();
        for (SpecDocuments.Fixture fixture : spec.fixtures()) {
            if (!spec.rules().containsKey(fixture.ruleId())) {
                failures.add(fixture.file() + ":" + fixture.line() + ": fixture cites "
                        + fixture.ruleId() + ", which no document defines");
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    @Test
    void everyFixtureCitingADiagnosticCitesADefinedOne() {
        SpecDocuments spec = SpecDocuments.load();
        List<String> failures = new ArrayList<>();
        for (SpecDocuments.Fixture fixture : spec.fixtures()) {
            if (fixture.diag().isPresent() && !spec.rules().containsKey(fixture.diag().get())) {
                failures.add(fixture.file() + ":" + fixture.line() + ": fixture for " + fixture.ruleId()
                        + " cites " + fixture.diag().get() + ", which the catalogue does not define");
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * {@code docs/format/README.md} §5: "any rule has no citing test and no fixture" is the actual
     * completeness promise the conformance index makes - broader than §4.2 rule 4, which only
     * requires a fixture for {@code ACCEPT}/{@code REJECT}/{@code DEFAULT}/{@code EQUIV} rules. A
     * {@code MUST} or {@code INVARIANT} rule with neither a fixture nor a citing test is just as
     * much an unenforced claim as an {@code ACCEPT} rule would be, so this checks every rule, not
     * only the four classes a fixture alone can discharge. The one exclusion is a diagnostic
     * catalogue row (cls {@code DIAG}) - not a rule under §3.2's own six classes, only a
     * bookkeeping entry this parser stores alongside rules; {@code DIAG.001} is exercised through
     * the {@code REJECT} rule that cites it, not by a test of its own.
     * <p>
     * The general check above is suspended for a {@code [DRAFT]} document, exactly as §4.2 rule 4
     * already was - that suspension is what keeps this test from failing on ~175 {@code MUST} and
     * {@code INVARIANT} rules while every specification document is still draft. It is <em>not</em>
     * the same suspension a {@code [NO-FIXTURE]} rule gets, though: §4.3 says a
     * {@code [NO-FIXTURE]} rule's citing-test requirement is "a stricter requirement than the one
     * it replaces" - stricter specifically because it is never suspended by draft. So although a
     * {@code [NO-FIXTURE]} rule looks like it could fall out of the general check as a special case
     * (it was already required to have a citing test), it stays a separate branch: merging it into
     * the draft-suspended branch would silently exempt every {@code [NO-FIXTURE]} rule for as long
     * as its document stays draft, which is exactly the loophole §4.3 exists to close.
     * <p>
     * Disabled for the same reason as before: nothing cites anything yet -
     * {@link SpecDocuments#citingTests()} scans {@code @Rule} annotations, and Task 2 is the first
     * task to write any. That alone is enough to fail the {@code [NO-FIXTURE]} branch today, since
     * it is never draft-suspended; re-enabling this test is how Task 2 finds out whether its first
     * citing tests actually cover the rules they claim to.
     */
    @Test
    @Disabled("no citing tests until Task 2")
    void everyRuleNeedingAFixtureHasOneOrIsMarkedOrIsDraft() {
        SpecDocuments spec = SpecDocuments.load();
        Map<String, List<SpecDocuments.Fixture>> fixturesByRule = new LinkedHashMap<>();
        for (SpecDocuments.Fixture fixture : spec.fixtures()) {
            fixturesByRule.computeIfAbsent(fixture.ruleId(), k -> new ArrayList<>()).add(fixture);
        }

        List<String> failures = new ArrayList<>();
        for (SpecDocuments.SpecRule rule : spec.rules().values()) {
            if (SpecDocuments.CATALOGUE_ROW_CLASS.equals(rule.cls())) {
                continue;
            }
            boolean hasFixture = !fixturesByRule.getOrDefault(rule.id(), List.of()).isEmpty();
            boolean hasCitingTest = !spec.citingTests().getOrDefault(rule.id(), List.of()).isEmpty();

            if (rule.noFixtureReason().isPresent()) {
                if (!hasCitingTest) {
                    failures.add(rule.file() + ": " + rule.id() + " is [NO-FIXTURE] but has no citing test");
                }
                continue;
            }
            boolean draft = spec.draftFiles().contains(rule.file());
            if (!hasFixture && !hasCitingTest && !draft) {
                failures.add(rule.file() + ": " + rule.id() + " has no fixture and no citing test");
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    @Test
    void theCheckedInIndexMatchesWhatTheDocumentsSay() throws IOException {
        SpecDocuments spec = SpecDocuments.load();
        Path checkedIn = SpecDocuments.repoRoot().resolve("docs/format/palette/conformance.md");
        assertEquals(spec.renderIndex(), Files.readString(checkedIn),
                "docs/format/palette/conformance.md is out of date; run ./gradlew regenerateConformance");
    }
}

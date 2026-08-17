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
     * §4.2 rule 4: a rule of class {@code ACCEPT}, {@code REJECT}, {@code DEFAULT} or {@code EQUIV}
     * needs a fixture, unless its document is {@code [DRAFT]} (rule-writing still in progress) or the
     * rule itself is marked {@code [NO-FIXTURE]}. §4.3 tightens the second exemption: a
     * {@code [NO-FIXTURE]} rule is not simply excused, it moves the whole burden onto a citing test,
     * so it must have at least one.
     * <p>
     * That second half fails today for all thirteen {@code [NO-FIXTURE]} rules, because nothing cites
     * anything yet - {@link SpecDocuments#citingTests()} scans {@code @Rule} annotations, and Task 2
     * is the first task to write any. Disabled until then rather than weakened, so the moment a
     * citing test lands for one of the thirteen, re-enabling this test is the way to find out whether
     * it actually covers the rule it claims to.
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
            if (rule.noFixtureReason().isPresent()) {
                if (spec.citingTests().getOrDefault(rule.id(), List.of()).isEmpty()) {
                    failures.add(rule.file() + ": " + rule.id() + " is [NO-FIXTURE] but has no citing test");
                }
                continue;
            }
            boolean needsFixture = SpecDocuments.NEEDS_FIXTURE.contains(rule.cls());
            boolean hasFixture = !fixturesByRule.getOrDefault(rule.id(), List.of()).isEmpty();
            boolean draft = spec.draftFiles().contains(rule.file());
            if (needsFixture && !hasFixture && !draft) {
                failures.add(rule.file() + ": " + rule.id() + " is " + rule.cls()
                        + " with no fixture and no [NO-FIXTURE]");
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

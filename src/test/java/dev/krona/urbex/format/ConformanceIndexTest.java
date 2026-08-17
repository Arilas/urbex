package dev.krona.urbex.format;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * The {@code [NO-FIXTURE]} rules that do not have a citing test yet, and what each is waiting for.
     * <p>
     * <b>This field is temporary and deletes itself.</b> Every entry names the task that will cover its
     * rule; {@link #everyExemptNoFixtureRuleIsStillWaiting} fails the moment one of them gains a citing
     * test, so an entry cannot outlive the work it is waiting for. When the last one goes, so does the
     * field and the branch below that reads it.
     * <p>
     * §4.3 gives a {@code [NO-FIXTURE]} rule a stricter requirement than the fixture-completeness one it
     * replaces: it must have a citing test, and unlike the general check that requirement is never
     * suspended by draft. That is the right rule and this list does not weaken it - it records that
     * <em>today</em> none of the thirteen is coverable, because a decoder is all that exists and every
     * one of them needs something else: a second asset, a resolved {@code extends} chain, a part file, a
     * command invocation, or a generated 129-choice list.
     * <p>
     * <b>Why a list and not a disabled test.</b> Until Task 2 this whole test was {@code @Disabled},
     * which checked nothing at all - including the twelve other assertions in it and the five hundred
     * other rules. This is strictly stronger: every branch of the check now runs, the exemptions are
     * enumerated and diffable, and {@link #everyExemptNoFixtureRuleIsStillWaiting} deletes them for you
     * by failing when one gains a citing test. It shrinks to nothing as Tasks 3 to 6 land, and the
     * intent is that the field goes with it.
     */
    private static final Map<String, String> NO_FIXTURE_RULES_AWAITING_A_CITING_TEST =
            noFixtureRulesAwaitingACitingTest();

    private static Map<String, String> noFixtureRulesAwaitingACitingTest() {
        Map<String, String> awaiting = new LinkedHashMap<>();
        awaiting.put("REF.043", "a pointer into a second registry; Task 3");
        awaiting.put("REF.045", "a pointer at a second asset; Task 3");
        awaiting.put("REF.062", "$super in an entry that inherits nothing; Task 4");
        awaiting.put("MERGE.010", "a version 1 palette extended by a version 2 one; Task 4");
        awaiting.put("MERGE.012", "a part carrying an inline palette; Task 4");
        awaiting.put("VER.005", "an extends chain across the two versions; Task 4");
        awaiting.put("WEIGHT.019", "a parent palette to spread shares from; Task 5");
        awaiting.put("WEIGHT.063", "a generated 129-choice list; Task 5");
        awaiting.put("LOAD.013", "a style with several palette groups; Task 7");
        awaiting.put("VER.006", "a style drawing both versions into one merge; Task 7");
        awaiting.put("VER.013", "a version 2 palette referencing a conditions asset; Task 6");
        awaiting.put("CHAR.011", "a part file's slice rows; Task 6");
        awaiting.put("CHAR.022", "a marker-assigning command; Task 6");
        return Collections.unmodifiableMap(awaiting);
    }

    /**
     * Nothing on the exemption list has quietly become covered.
     * <p>
     * This is what makes the list above self-deleting rather than a place entries go to rest: the moment
     * a later task writes a citing test for one of these rules, this fails and says which line to remove.
     * It also fails on an exemption for a rule that is not {@code [NO-FIXTURE]} at all, which is how a
     * renumbered or reclassified rule stops silently carrying an exemption it never needed.
     */
    @Test
    void everyExemptNoFixtureRuleIsStillWaiting() {
        SpecDocuments spec = SpecDocuments.load();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> exempt : NO_FIXTURE_RULES_AWAITING_A_CITING_TEST.entrySet()) {
            SpecDocuments.SpecRule rule = spec.rules().get(exempt.getKey());
            if (rule == null) {
                failures.add(exempt.getKey() + " is exempt from the [NO-FIXTURE] citing-test"
                        + " requirement, but no document defines it");
                continue;
            }
            if (rule.noFixtureReason().isEmpty()) {
                failures.add(exempt.getKey() + " is exempt from the [NO-FIXTURE] citing-test"
                        + " requirement, but is not marked [NO-FIXTURE]; delete the exemption");
            }
            if (!spec.citingTests().getOrDefault(exempt.getKey(), List.of()).isEmpty()) {
                failures.add(exempt.getKey() + " now has a citing test ("
                        + spec.citingTests().get(exempt.getKey()) + "); delete its exemption");
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

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
     * Enabled as of Task 2, the first task to write a citing test. The {@code [NO-FIXTURE]} branch
     * consults {@link #NO_FIXTURE_RULES_AWAITING_A_CITING_TEST}, because not one of the thirteen
     * {@code [NO-FIXTURE]} rules is coverable by a decoder: each needs a second asset, a resolved
     * chain, a part file, a command or a generated input. That list is a weaker promise than
     * §4.3's, and a far stronger one than the {@code @Disabled} it replaces - see its own javadoc.
     */
    @Test
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
                if (!hasCitingTest
                        && !NO_FIXTURE_RULES_AWAITING_A_CITING_TEST.containsKey(rule.id())) {
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

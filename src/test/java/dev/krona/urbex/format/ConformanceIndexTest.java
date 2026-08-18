package dev.krona.urbex.format;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * <em>today</em> none of the entries below is coverable, because each needs something no stage that
     * exists has: a style with several palette groups, a part file's slice rows, or a command
     * invocation. The three a second asset was enough for - {@code REF.043}, {@code REF.045} and
     * {@code REF.062} - went when the resolver landed and could be handed one; the two a generated list
     * was enough for - {@code WEIGHT.019} and {@code WEIGHT.063} - went when stage 4 landed; and
     * {@code VER.013} went when the trait registry made a version 2 palette able to name a
     * {@code conditions} asset at all.
     * <p>
     * <b>Why a list and not a disabled test.</b> Until Task 2 this whole test was {@code @Disabled},
     * which checked nothing at all - including the twelve other assertions in it and the five hundred
     * other rules. This is strictly stronger: every branch of the check now runs, the exemptions are
     * enumerated and diffable, and {@link #everyExemptNoFixtureRuleIsStillWaiting} deletes them for you
     * by failing when one gains a citing test. It shrinks to nothing as the tasks land, and the
     * intent is that the field goes with it.
     */
    private static final Map<String, String> NO_FIXTURE_RULES_AWAITING_A_CITING_TEST =
            noFixtureRulesAwaitingACitingTest();

    private static Map<String, String> noFixtureRulesAwaitingACitingTest() {
        Map<String, String> awaiting = new LinkedHashMap<>();
        awaiting.put("CHAR.011", "a part file's slice rows; Task 7, with the part loader");
        awaiting.put("CHAR.022", "a marker-assigning command; not scheduled - /exportpart is version 1's");
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

    /**
     * {@code README.md} §3.2's table is the definition of the rule classes, and both the count it states
     * and the classes the documents use agree with it.
     * <p>
     * <b>Why this exists at all.</b> §3.2's opening sentence carries the number of classes in words
     * ("Eight classes, and every rule has exactly one"), and two javadoc comments in {@link
     * SpecDocuments} carried it as well. When the seventh class was added they said six, and stayed
     * saying six until the eighth arrived - so a count that was wrong for a whole round was corrected by
     * a change that had nothing to do with it. A number in prose is a claim about the tree like any
     * other, and the only counts that cannot drift are the ones nothing states twice: the comments now
     * name the section instead of counting it, and the one place the count survives - the sentence that
     * introduces the table - is checked against the table.
     * <p>
     * The second half is the more useful one: a rule whose class is not in the table is a class nobody
     * defined. That was reachable, since the class is parsed as {@code [A-Z ]+} and nothing compared it
     * to anything.
     */
    @Test
    void everyRuleClassIsOneTheReadmeDefinesAndTheCountItStatesIsRight() throws IOException {
        String readme = Files.readString(SpecDocuments.repoRoot().resolve("docs/format/README.md"));
        String classes = readme.substring(readme.indexOf("### 3.2 Classes"),
                readme.indexOf("### 3.3"));
        Set<String> defined = new LinkedHashSet<>();
        Matcher row = Pattern.compile("(?m)^\\| `([A-Z ]+)` \\|").matcher(classes);
        while (row.find()) {
            defined.add(row.group(1));
        }
        assertFalse(defined.isEmpty(), "§3.2's class table did not parse");

        Matcher stated = Pattern.compile("([A-Za-z]+) classes, and every rule has exactly one")
                .matcher(classes);
        assertTrue(stated.find(), "§3.2 no longer states how many classes there are");
        assertEquals(NUMBER_WORDS.indexOf(stated.group(1).toLowerCase(Locale.ROOT)), defined.size(),
                () -> "§3.2 says '" + stated.group(1) + " classes' and its table has " + defined.size()
                        + ": " + defined);

        List<String> failures = new ArrayList<>();
        for (SpecDocuments.SpecRule rule : SpecDocuments.load().rules().values()) {
            if (rule.cls().equals("DIAG") || rule.cls().equals("RETIRED")
                    || defined.contains(rule.cls())) {
                continue;
            }
            failures.add(rule.file() + ": " + rule.id() + " has class '" + rule.cls()
                    + "', which README.md §3.2 does not define");
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /** Index is the number: {@code NUMBER_WORDS.indexOf("eight") == 8}. */
    private static final List<String> NUMBER_WORDS = List.of("zero", "one", "two", "three", "four",
            "five", "six", "seven", "eight", "nine", "ten");

    /**
     * {@code README.md} §3.2: "A {@code REJECT} or {@code WARN} rule always cites a {@code DIAG}
     * identifier."
     * <p>
     * Both classes and not only {@code REJECT}, since the {@code WARN} class landed: the reason the
     * section gives is that a refusal whose message is not specified cannot be tested without pinning an
     * implementation detail, and that is just as true of a report which does not refuse. A {@code WARN}
     * rule with no diagnostic would be a rule saying only "something is said", which is not falsifiable.
     */
    @Test
    void everyRejectOrWarnRuleCitesADefinedDiagnostic() {
        SpecDocuments spec = SpecDocuments.load();
        List<String> failures = new ArrayList<>();
        for (SpecDocuments.SpecRule rule : spec.rules().values()) {
            if (!"REJECT".equals(rule.cls()) && !"WARN".equals(rule.cls())) {
                continue;
            }
            if (rule.diag().isEmpty()) {
                failures.add(rule.file() + ": " + rule.id() + " is " + rule.cls()
                        + " but cites no DIAG");
            } else if (!spec.rules().containsKey(rule.diag().get())) {
                failures.add(rule.file() + ": " + rule.id() + " cites " + rule.diag().get()
                        + ", which the catalogue does not define");
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * The converse of the check above, and the one that would have caught the three rules it missed.
     * <p>
     * {@code README.md} §4.1: a {@code reject=<DIAG-ID>} fixture claims "the load fails, and the
     * diagnostic identifier matches". That is a {@code REJECT} rule's claim, stated by the fixture - so
     * a rule carrying one and classed anything else has said two different things about itself. Three
     * did: {@code MODEL.013}, {@code MODEL.051} and {@code REF.082} were all {@code MUST} with a
     * {@code reject=} fixture, which is why
     * {@link #everyRejectOrWarnRuleCitesADefinedDiagnostic()} - which starts by filtering to
     * {@code REJECT} and {@code WARN} - skipped all three and enforced "every rejection cites a DIAG"
     * over 311 of 314 identifiers while reading as though it covered them all.
     * <p>
     * The diagnostic is compared too, not only the class. A rule that refuses with one {@code DIAG} and
     * demonstrates the refusal with a fixture expecting another is a rule whose fixture proves something
     * else, and nothing else in this suite reads both halves at once.
     */
    @Test
    void everyRuleWithARejectFixtureIsClassRejectAndCitesThatFixturesDiagnostic() {
        SpecDocuments spec = SpecDocuments.load();
        List<String> failures = new ArrayList<>();
        for (SpecDocuments.Fixture fixture : spec.fixtures()) {
            if (fixture.outcome() != SpecDocuments.Outcome.REJECT) {
                continue;
            }
            SpecDocuments.SpecRule rule = spec.rules().get(fixture.ruleId());
            if (rule == null) {
                continue;
            }
            if (!"REJECT".equals(rule.cls())) {
                failures.add(fixture.file() + ":" + fixture.line() + ": " + rule.id()
                        + " has a reject= fixture but is class " + rule.cls()
                        + "; a fixture that says the load fails is a REJECT rule's claim");
            } else if (!rule.diag().equals(fixture.diag())) {
                failures.add(fixture.file() + ":" + fixture.line() + ": " + rule.id() + " refuses with "
                        + rule.diag().orElse("no DIAG") + " and its fixture expects "
                        + fixture.diag().orElseThrow());
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * {@code README.md} §3.3's third status marker is parsed, is defined where it is used, and names an
     * issue.
     * <p>
     * <b>It was a convention with no tooling</b> - the same defect Task 4 found and fixed for tombstones.
     * {@code SpecDocuments}' rule pattern read {@code [NO-FIXTURE]} and not this, §3.3 defined two
     * markers and not three, and {@code TRAIT.011} therefore parsed as an ordinary {@code MUST}: the
     * index listed two citing tests beside a rule the specification declares unreached, with nothing
     * anywhere saying what those tests were able to assert.
     * <p>
     * Three things are asserted, and the first is the one that makes the marker tooling rather than a
     * string in a document: at least one rule carries it. A parse that had silently stopped matching
     * would leave the other two assertions true of an empty list.
     */
    @Test
    void everyNotYetReachedRuleIsParsedNamesAnIssueAndIsCoveredByACitingTest() {
        SpecDocuments spec = SpecDocuments.load();
        List<SpecDocuments.SpecRule> marked = spec.rules().values().stream()
                .filter(rule -> rule.notYetReachedReason().isPresent())
                .toList();
        assertFalse(marked.isEmpty(),
                "no rule parses as [NOT-YET-REACHED]; §3.3 defines the marker, so either the documents"
                        + " stopped using it or SpecDocuments stopped reading it");

        List<String> failures = new ArrayList<>();
        for (SpecDocuments.SpecRule rule : marked) {
            String reason = rule.notYetReachedReason().orElseThrow();
            if (!Pattern.compile("issue #\\d+").matcher(reason).find()) {
                failures.add(rule.file() + ": " + rule.id() + " is [NOT-YET-REACHED: " + reason
                        + "], which names no issue; §3.3 says the reason names the issue that reaches it");
            }
            if (spec.citingTests().getOrDefault(rule.id(), List.of()).isEmpty()) {
                failures.add(rule.file() + ": " + rule.id() + " is [NOT-YET-REACHED] and has no citing"
                        + " test; the marker says what a test can assert, not that none is needed");
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /** §3.3's marker list and the markers the documents actually use are the same list. */
    @Test
    void theStatusMarkersTheDocumentsUseAreTheOnesTheReadmeDefines() throws IOException {
        String readme = Files.readString(SpecDocuments.repoRoot().resolve("docs/format/README.md"));
        String status = readme.substring(readme.indexOf("### 3.3 Status"),
                readme.indexOf("### 3.4"));
        Matcher stated = Pattern.compile("([A-Za-z]+) markers exist").matcher(status);
        assertTrue(stated.find(), "§3.3 no longer states how many status markers there are");
        Set<String> defined = new LinkedHashSet<>();
        Matcher bullet = Pattern.compile("(?m)^- `\\[([A-Z-]+)").matcher(status);
        while (bullet.find()) {
            defined.add(bullet.group(1));
        }
        assertEquals(NUMBER_WORDS.indexOf(stated.group(1).toLowerCase(Locale.ROOT)), defined.size(),
                () -> "§3.3 says '" + stated.group(1) + " markers' and defines " + defined);
        assertTrue(defined.contains("NOT-YET-REACHED"),
                () -> "§3.3 must define the marker TRAIT.011 carries: " + defined);
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
     * catalogue row (cls {@code DIAG}) - not a rule under any of §3.2's own classes, only a
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
     * consults {@link #NO_FIXTURE_RULES_AWAITING_A_CITING_TEST}, whose remaining entries each need a
     * resolved chain, a part file, a command or a generated input. That list is a weaker promise than
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
            if (SpecDocuments.CATALOGUE_ROW_CLASS.equals(rule.cls())
                    || SpecDocuments.TOMBSTONE_CLASS.equals(rule.cls())) {
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

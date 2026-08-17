package dev.krona.urbex.format;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift-guards {@link Diag} against the catalogue in {@code docs/format/palette/08-errors.md} §4, and
 * checks the four promises that document makes about diagnostics in general.
 * <p>
 * The enum is a hand-written copy of a table, which is exactly the shape of claim
 * {@code docs/format/README.md} §1 says version 1 got wrong: a claim that exists in one place and is
 * compared to nothing. Here it exists in two and they are compared - the same drift guard
 * {@link dev.krona.urbex.config.PresetSchemaTest} runs between the preset schema and the section
 * codecs' key sets.
 */
class DiagCatalogueTest {

    /**
     * The one row whose message is not a message: {@code DIAG.042} is retired, and its catalogue cell
     * holds an em dash. It is still an identifier the enum must carry, by {@code DIAG.910}.
     */
    private static final String RETIRED_ROW_MESSAGE = "—";

    @Test
    @Rule("DIAG.910")
    void theDiagEnumCoversExactlyTheCatalogue() {
        assertEquals(SpecDocuments.load().diagnostics().keySet(),
                Arrays.stream(Diag.values()).map(Diag::id).collect(Collectors.toSet()),
                "every catalogue row is an enum constant and every constant is a catalogue row -"
                        + " including a retired one, whose number DIAG.910 makes permanent");
    }

    /**
     * Every word of an enum template appears in the catalogue row it copies.
     * <p>
     * A subset rather than an equality, in that direction, on purpose. The rows carry alternative
     * clauses ({@code <a / b / c>}) and clauses that appear only when they apply, and a message
     * assembled from one of the alternatives legitimately uses fewer words than the row lists. What the
     * subset does catch is the failure that matters: a row reworded in the document leaves the enum
     * holding a word the row no longer has, and that fails here rather than surfacing as a diagnostic
     * that quotes a specification nobody wrote.
     */
    @Test
    @Rule("DIAG.910")
    void everyDiagTemplateIsWordedAsItsCatalogueRowIs() {
        Map<String, String> catalogue = SpecDocuments.load().diagnostics();
        List<String> failures = new ArrayList<>();
        for (Diag diag : Diag.values()) {
            Set<String> rowWords = words(catalogue.get(diag.id()));
            Set<String> templateWords = new LinkedHashSet<>();
            Diag.literalSegments(diag.template()).forEach(segment ->
                    templateWords.addAll(words(segment)));
            templateWords.removeAll(rowWords);
            if (!templateWords.isEmpty()) {
                failures.add(diag.id() + " uses words its catalogue row does not: " + templateWords
                        + "\n  enum: " + diag.template() + "\n  row:  " + catalogue.get(diag.id()));
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * The other direction: every word a catalogue row states <em>outside</em> a placeholder appears in
     * the template.
     * <p>
     * The subset check above runs enum→row, so a template that <em>drops</em> row text passes it. That is
     * how ruling 4 of this task's review landed in {@code 08-errors.md} and not in {@link Diag}:
     * {@code DIAG.053} gained "Correct the row, or the declared width, so the two agree." in the document
     * while the enum kept the single sentence, and every check was still green. A remedy that exists only
     * in the specification is the doc/code divergence {@code docs/format/README.md} §1 describes as
     * version 1's whole failure mode, arrived at from the other side.
     * <p>
     * Text inside a {@code <…>} placeholder is excluded, because that is text the row delegates: an
     * alternative clause the caller picks between, or a clause that appears only when it applies. Only
     * what the row states unconditionally is required of the template. The location prefix - everything
     * up to the first {@code ": "} - is excluded too, because every template folds it into one leading
     * placeholder (see {@link Diag}), so the row's literal "marker" is not a word a template carries.
     */
    @Test
    @Rule("DIAG.910")
    void everyWordACatalogueRowStatesOutrightAppearsInItsTemplate() {
        Map<String, String> catalogue = SpecDocuments.load().diagnostics();
        List<String> failures = new ArrayList<>();
        for (Diag diag : Diag.values()) {
            Set<String> required = words(outsidePlaceholders(afterLocation(catalogue.get(diag.id()))));
            required.removeAll(words(afterLocation(diag.template())));
            if (!required.isEmpty()) {
                failures.add(diag.id() + " drops words its catalogue row states outright: " + required
                        + "\n  row:  " + catalogue.get(diag.id()) + "\n  enum: " + diag.template());
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * {@code DIAG.900} and §2's message shape: a diagnostic names the thing it is about, and then says
     * what to write instead.
     * <p>
     * Checked against the catalogue rather than against the enum, because the catalogue is the normative
     * text - a row that names a problem and no remedy is a specification defect, and it stays one
     * however carefully the enum copies it. The two halves of §2 that are mechanically checkable are the
     * leading placeholder (the asset, and whatever else of the location applies) and the second
     * sentence, which §2 calls required.
     * <p>
     * <b>Which half of {@code DIAG.900} this proves.</b> The rule is about a produced diagnostic; this
     * asserts over the catalogue rows the diagnostics are formatted from, so it proves the messages are
     * <em>specified</em> to name the asset and the remedy, not that a produced one names the marker. The
     * marker clause needs the loader stage that {@code LOAD.051} describes - a codec is handed a document
     * and knows neither the asset id nor which marker it is inside - so it is disclosed as a gap in this
     * task's report alongside {@code DIAG.901} and {@code DIAG.902}, and the annotation stays here
     * because this is the half that is checkable today.
     */
    @Test
    @Rule("DIAG.900")
    void everyCatalogueRowNamesWhatItIsAboutAndWhatToWriteInstead() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> row : SpecDocuments.load().diagnostics().entrySet()) {
            String message = Diag.normalise(row.getValue());
            if (RETIRED_ROW_MESSAGE.equals(message)) {
                continue;
            }
            if (!message.startsWith("<")) {
                failures.add(row.getKey() + " does not begin by naming what it is about: " + message);
            }
            // A trailing placeholder is a second sentence the row delegates rather than writes -
            // DIAG.061's "<explanation>" is the remedy, supplied per retired key. What this counts as
            // missing is a row with one sentence and nothing after it.
            String afterFirstSentence = message.contains(". ")
                    ? message.substring(message.indexOf(". ") + 2)
                    : "";
            if (afterFirstSentence.isBlank()) {
                failures.add(row.getKey() + " has no second sentence, which section 2 requires - a"
                        + " diagnostic that names a problem without naming a remedy is incomplete: "
                        + message);
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * {@code DIAG.903}: diagnostics are collected and reported together, not thrown at the first
     * failure.
     */
    @Test
    @Rule("DIAG.903")
    void severalDiagnosticsAreReportedTogetherRatherThanOneAtATime() {
        Diagnostics diagnostics = new Diagnostics();
        diagnostics.error(Diag.DIAG_003, "this palette", "damagd", "a block node");
        diagnostics.error(Diag.DIAG_003, "this palette", "blcok", "a block node");
        diagnostics.error(Diag.DIAG_007, "this palette");

        assertEquals(3, diagnostics.all().size(), "all three, not the first");
        String reported = diagnostics.asError().orElseThrow();
        assertTrue(reported.contains("damagd"), reported);
        assertTrue(reported.contains("blcok"), reported);
        assertTrue(Diag.DIAG_007.matches(reported), reported);
    }

    /**
     * {@code DIAG.904}: a diagnostic is an error or a warning, a warning does not refuse the world, and
     * there is no third level.
     */
    @Test
    @Rule("DIAG.904")
    void aDiagnosticIsAnErrorOrAWarningAndThereIsNoThirdLevel() {
        assertEquals(List.of(Diagnostics.Level.ERROR, Diagnostics.Level.WARN),
                Arrays.asList(Diagnostics.Level.values()));

        Diagnostics warningOnly = new Diagnostics();
        warningOnly.warn(Diag.DIAG_007, "this palette");
        assertFalse(warningOnly.hasFatal(), "a warning does not refuse the world");
        assertTrue(warningOnly.asError().isEmpty(), "and does not become a decode failure");

        Diagnostics withError = new Diagnostics();
        withError.warn(Diag.DIAG_007, "this palette");
        withError.error(Diag.DIAG_010, "this palette");
        assertTrue(withError.hasFatal());
        assertFalse(withError.asError().orElseThrow().contains("declares no choices"),
                "the warning is not smuggled into the failure that refuses the load");
    }

    /** A formatted message is a message of its own diagnostic and of no other. */
    @Test
    @Rule("DIAG.910")
    void aFormattedMessageIsRecognisedAsItsOwnDiagnostic() {
        List<String> failures = new ArrayList<>();
        for (Diag diag : Diag.values()) {
            if (RETIRED_ROW_MESSAGE.equals(diag.template())) {
                continue;
            }
            String[] args = new String[Diag.literalSegments(diag.template()).size() - 1];
            Arrays.fill(args, "X");
            String message = diag.message((Object[]) args);
            if (!diag.matches(message)) {
                failures.add(diag.id() + " does not recognise its own message: " + message);
            }
            for (Diag other : Diag.values()) {
                if (other != diag && !RETIRED_ROW_MESSAGE.equals(other.template())
                        && other.matches(message)) {
                    failures.add(other.id() + " also matches " + diag.id() + "'s message: " + message);
                }
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /** {@link Diag#of} answers for every identifier the catalogue defines, and for nothing else. */
    @Test
    @Rule("DIAG.910")
    void everyCatalogueIdentifierIsLookedUpByItsId() {
        for (String id : SpecDocuments.load().diagnostics().keySet()) {
            assertEquals(id, Diag.of(id).id());
        }
        assertEquals("no diagnostic DIAG.999 in the catalogue",
                org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                        () -> Diag.of("DIAG.999")).getMessage());
    }

    /**
     * Every clause a catalogue row delegates is either a slot in the template or spelled out in it.
     * <p>
     * <b>The hole the two word-subset guards above have, and why it took two rows to find.</b> Both
     * compare <em>words</em>, and {@link #outsidePlaceholders} deletes everything inside a {@code <…>}
     * before either of them looks - which is right, since that text is delegated to the caller. The
     * consequence is that a row's optional or alternative clause contributes no words to compare at all,
     * so a template can drop one entirely and stay green. That is not hypothetical: {@code DIAG.045}'s
     * <code>&lt; — &lt;a&gt; written here and &lt;b&gt; spread from '&lt;id&gt;'&gt;</code> was missing
     * from the enum, which made {@code WEIGHT.019} - a rule whose whole content is "name the incoming and
     * inherited totals separately" - unsatisfiable whatever a caller passed; and {@code DIAG.020}'s
     * namespace clause was missing beside it, unnoticed only because nothing raises that row yet.
     * <p>
     * This is the check that could not be written as a comparison of words, because there are none: it
     * compares <em>arity</em>. Each depth-1 {@code <…>} group after the location is a value the row says
     * the caller supplies, so the template needs a {@code %s} for it - unless the template writes the
     * clause out, which is legitimate when the row brackets something that has only one possible value
     * ({@code DIAG.030}'s closing sentence is the only such row today). "Writes it out" is a verbatim
     * substring and not a word overlap, and it needs three words to count, so a single-word group like
     * {@code <n>} cannot be spuriously matched by the letter {@code n} appearing somewhere.
     */
    @Test
    @Rule("DIAG.910")
    void everyClauseACatalogueRowDelegatesIsASlotOrIsSpelledOutInTheTemplate() {
        Map<String, String> catalogue = SpecDocuments.load().diagnostics();
        List<String> failures = new ArrayList<>();
        for (Diag diag : Diag.values()) {
            if (RETIRED_ROW_MESSAGE.equals(diag.template())) {
                continue;
            }
            String template = afterLocation(diag.template());
            long slots = Diag.literalSegments(template).size() - 1;
            List<String> needingASlot = new ArrayList<>();
            for (String clause : delegatedClauses(afterLocation(catalogue.get(diag.id())))) {
                String stated = Diag.normalise(outsidePlaceholders(clause));
                boolean spelledOut = stated.split("\\s+").length >= 3
                        && template.contains(stated);
                if (!spelledOut) {
                    needingASlot.add(clause);
                }
            }
            if (needingASlot.size() != slots) {
                failures.add(diag.id() + ": the row delegates " + needingASlot.size()
                        + " value(s) the template does not spell out, and the template has " + slots
                        + " placeholder(s) for them: " + needingASlot
                        + "\n  row:  " + catalogue.get(diag.id()) + "\n  enum: " + diag.template());
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * The top-level {@code <…>} groups of a message - the values it delegates to whoever formats it.
     * <p>
     * Depth-counted for the reason {@link #outsidePlaceholders} is: the rows nest placeholders inside
     * placeholders, and a group that holds three of them is still one value the caller supplies.
     */
    private static List<String> delegatedClauses(String message) {
        List<String> clauses = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int at = 0; at < message.length(); at++) {
            char character = message.charAt(at);
            if (character == '<') {
                if (depth == 0) {
                    start = at + 1;
                }
                depth++;
            } else if (character == '>' && depth > 0) {
                depth--;
                if (depth == 0) {
                    clauses.add(message.substring(start, at));
                }
            }
        }
        return clauses;
    }

    /** Everything after the location prefix - the first {@code ": "} - or all of it if there is none. */
    private static String afterLocation(String message) {
        String normalised = Diag.normalise(message);
        int colon = normalised.indexOf(": ");
        return colon < 0 ? normalised : normalised.substring(colon + 2);
    }

    /**
     * The text a message states unconditionally, with every {@code <…>} placeholder removed.
     * <p>
     * Depth-counted rather than regex-stripped: the rows nest placeholders inside placeholders -
     * {@code DIAG.040}'s alternation holds {@code <w>}, {@code <f>} and {@code <none of|both>} inside one
     * outer {@code <…>} - and a non-greedy regex would close the outer group at the first inner
     * {@code >}, leaving half an alternative behind as if the row had stated it.
     */
    private static String outsidePlaceholders(String message) {
        StringBuilder outside = new StringBuilder();
        int depth = 0;
        for (char character : message.toCharArray()) {
            if (character == '<') {
                depth++;
            } else if (character == '>') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                outside.append(character);
            }
        }
        return outside.toString();
    }

    /**
     * The words of a message, with punctuation and placeholder brackets dropped.
     * <p>
     * Punctuation has to go, because the same word carries different punctuation in the two places:
     * a catalogue row writes {@code is registered`<, and nothing loaded…>`} where a produced message
     * writes {@code is registered.}, and the row spells its dash as an em dash where a message written
     * for a log spells it as a hyphen. Comparing those would compare typesetting. A colon and a dollar
     * stay part of a word, because {@code urbex:damaged} and {@code $imports} are single words that a
     * reader would notice changing.
     */
    private static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : Diag.normalise(text).split("[^\\p{Alnum}$:_]+")) {
            if (!word.isBlank()) {
                words.add(word);
            }
        }
        return words;
    }
}

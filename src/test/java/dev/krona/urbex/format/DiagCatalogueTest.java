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

    /**
     * Catalogue rows that do not yet have the second sentence §2 requires.
     * <p>
     * <b>{@code DIAG.053} is a specification defect, not an exemption this test is granting.</b> Its row
     * reads "{@code <part> slice <i> row <j>: <n> codepoints, but the part declares a width of <w>.}" -
     * one sentence, which names the problem and no remedy, and §2 says in as many words that such a
     * diagnostic is incomplete. It is listed rather than fixed here because fixing it means editing
     * {@code 08-errors.md}, and this task's licence to edit the specification covers exactly one thing
     * (§3.2's missing rule class). It is written up in the task report; the entry goes away with the row.
     */
    private static final Set<String> ROWS_WITHOUT_A_REMEDY_SENTENCE = Set.of("DIAG.053");

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
     * {@code DIAG.900} and §2's message shape: a diagnostic names the thing it is about, and then says
     * what to write instead.
     * <p>
     * Checked against the catalogue rather than against the enum, because the catalogue is the normative
     * text - a row that names a problem and no remedy is a specification defect, and it stays one
     * however carefully the enum copies it. The two halves of §2 that are mechanically checkable are the
     * leading placeholder (the asset, and whatever else of the location applies) and the second
     * sentence, which §2 calls required.
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
            boolean hasRemedy = !afterFirstSentence.isBlank();
            if (!hasRemedy && !ROWS_WITHOUT_A_REMEDY_SENTENCE.contains(row.getKey())) {
                failures.add(row.getKey() + " has no second sentence, which section 2 requires - a"
                        + " diagnostic that names a problem without naming a remedy is incomplete: "
                        + message);
            }
            if (hasRemedy && ROWS_WITHOUT_A_REMEDY_SENTENCE.contains(row.getKey())) {
                failures.add(row.getKey() + " now has a remedy sentence; delete it from"
                        + " ROWS_WITHOUT_A_REMEDY_SENTENCE");
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

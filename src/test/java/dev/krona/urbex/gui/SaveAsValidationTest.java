package dev.krona.urbex.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SaveAsDialog#validateName} is the headless, pure core of the Save-as flow (the dialog's own
 * widgets are GL code, exercised only manually). It rejects empty names, names already taken (built-in
 * presets and existing customs), and anything that isn't {@code [a-z0-9_]+} after lowercasing; every
 * rejection is a lang-keyed {@code urbex.saveas.err.*} component so the dialog can surface it verbatim.
 */
class SaveAsValidationTest {

    private static final Set<String> TAKEN = Set.of("default", "cavern", "my_wasteland");

    private static String errKey(Optional<Component> result) {
        assertTrue(result.isPresent(), "expected a validation error, got none");
        assertTrue(result.get().getContents() instanceof TranslatableContents,
                "error must be a lang-keyed translatable component, got: " + result.get());
        return ((TranslatableContents) result.get().getContents()).getKey();
    }

    @Test
    void acceptsALowercaseUnderscoreName() {
        assertTrue(SaveAsDialog.validateName("my_wasteland", Set.of()).isEmpty(),
                "a clean [a-z0-9_]+ name that isn't taken must be accepted");
    }

    @Test
    void acceptsDigits() {
        assertTrue(SaveAsDialog.validateName("wasteland_2", Set.of()).isEmpty());
    }

    @Test
    void rejectsEmpty() {
        assertEquals("urbex.saveas.err.empty", errKey(SaveAsDialog.validateName("", Set.of())));
    }

    @Test
    void rejectsBlankWhitespace() {
        assertEquals("urbex.saveas.err.empty", errKey(SaveAsDialog.validateName("   ", Set.of())));
    }

    @Test
    void rejectsNull() {
        assertEquals("urbex.saveas.err.empty", errKey(SaveAsDialog.validateName(null, Set.of())));
    }

    @Test
    void rejectsNameAlreadyTaken() {
        assertEquals("urbex.saveas.err.taken", errKey(SaveAsDialog.validateName("cavern", TAKEN)));
    }

    @Test
    void rejectsNameTakenCaseInsensitivelyAfterLowercasing() {
        // "Cavern" lowercases to a taken id; the compare must be against the lowercased candidate.
        assertEquals("urbex.saveas.err.taken", errKey(SaveAsDialog.validateName("Cavern", TAKEN)));
    }

    @Test
    void rejectsSpaces() {
        assertEquals("urbex.saveas.err.invalid", errKey(SaveAsDialog.validateName("my wasteland", Set.of())));
    }

    @Test
    void rejectsPunctuation() {
        assertEquals("urbex.saveas.err.invalid", errKey(SaveAsDialog.validateName("waste-land!", Set.of())));
    }

    @Test
    void acceptsMixedCaseThatIsCleanAfterLowercasing() {
        // Uppercase letters are not rejected outright: they lowercase into the [a-z0-9_] set.
        Optional<Component> result = SaveAsDialog.validateName("MyWasteland", Set.of());
        assertTrue(result.isEmpty(), "mixed-case letters lowercase into the allowed set and must be accepted");
    }

    @Test
    void takenCheckDoesNotFalseTripOnADistinctName() {
        assertFalse(SaveAsDialog.validateName("fresh_name", TAKEN).isPresent());
    }

    @Test
    void rejectsReservedDisabledIdWhenIncludedInTaken() {
        // openSaveAs unions the reserved selection ids into taken; "disabled" must be rejected so it
        // can't double-list against the built-in Disabled row.
        Set<String> taken = Set.of("disabled", "customized");
        assertEquals("urbex.saveas.err.taken", errKey(SaveAsDialog.validateName("disabled", taken)));
    }

    @Test
    void rejectsReservedCustomizedIdWhenIncludedInTaken() {
        // "customized" is the transient marker entries() never lists as a saved file; rejecting it up
        // front stops a preset that would save invisibly.
        Set<String> taken = Set.of("disabled", "customized");
        assertEquals("urbex.saveas.err.taken", errKey(SaveAsDialog.validateName("customized", taken)));
    }
}

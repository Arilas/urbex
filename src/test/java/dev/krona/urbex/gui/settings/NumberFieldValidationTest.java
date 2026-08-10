package dev.krona.urbex.gui.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the safety contract of the {@link ControlKind#NUMBER} control: {@link SettingControls#validateNumber}
 * only ever returns a value the descriptor's setter can accept without corrupting the profile. The control's
 * responder writes the field <em>only</em> when this returns non-null, so a {@code null} here means "keep the
 * last valid value". The method is pure (no GL), so it is exercised directly.
 *
 * <p>The regression that motivates the int-range cases: an {@code int}-backed field (e.g. {@code CITY_SPAWN_
 * DISTANCE1}) whose setter narrows via {@code (int) Math.round((Double) v)} would silently wrap a value like
 * {@code 3000000000} to a negative number. Validation must refuse it before it ever reaches the setter.</p>
 */
class NumberFieldValidationTest {

    // ---- int-range safety (the CRITICAL regression) -------------------------

    @Test
    void integerFieldRejectsValueAboveIntRange() {
        // 3_000_000_000 parses as a long but would wrap negative through the setter's (int) narrowing.
        assertNull(SettingControls.validateNumber("3000000000", true, 0, Integer.MAX_VALUE));
    }

    @Test
    void integerFieldRejectsValueBelowIntRange() {
        assertNull(SettingControls.validateNumber("-3000000000", true, Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    // ---- declared-range safety ---------------------------------------------

    @Test
    void valueAboveMaxIsRejected() {
        // SPAWN_CHECK_RADIUS-style bounds: 1..100000.
        assertNull(SettingControls.validateNumber("200000", true, 1, 100000));
    }

    @Test
    void valueBelowMinIsRejected() {
        assertNull(SettingControls.validateNumber("0", true, 1, 100000));
    }

    @Test
    void boundaryValuesAreAccepted() {
        assertEquals(1.0, SettingControls.validateNumber("1", true, 1, 100000));
        assertEquals(100000.0, SettingControls.validateNumber("100000", true, 1, 100000));
    }

    // ---- valid values pass --------------------------------------------------

    @Test
    void validIntegerPasses() {
        assertEquals(20000.0, SettingControls.validateNumber("20000", true, 1, 1000000));
    }

    @Test
    void doubleFieldAcceptsDecimals() {
        assertEquals(0.1, SettingControls.validateNumber("0.1", false, -1000000, 1000000));
        assertEquals(-2.5, SettingControls.validateNumber("-2.5", false, -1000000, 1000000));
    }

    // ---- integerOnly rejects decimals --------------------------------------

    @Test
    void integerFieldRejectsDecimalInput() {
        assertNull(SettingControls.validateNumber("3.5", true, 0, 100));
    }

    // ---- partial / non-numeric input ---------------------------------------

    @Test
    void partialAndNonNumericInputIsRejected() {
        assertNull(SettingControls.validateNumber("", true, 0, 100));
        assertNull(SettingControls.validateNumber("   ", true, 0, 100));
        assertNull(SettingControls.validateNumber("-", true, -100, 100));
        assertNull(SettingControls.validateNumber("+", false, -100, 100));
        assertNull(SettingControls.validateNumber("abc", false, -100, 100));
        assertNull(SettingControls.validateNumber("1.2.3", false, -100, 100));
        assertNull(SettingControls.validateNumber(null, false, -100, 100));
    }
}

package dev.krona.urbex.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DigestCheckSpecTest {

    @Test
    public void parsesRadiusOrderOffset() {
        DigestCheck.Spec spec = DigestCheck.Spec.parse("3,rowmajor,100");
        assertEquals(3, spec.radius());
        assertEquals("rowmajor", spec.order());
        assertEquals(100, spec.offset());
    }

    @Test
    public void parsesNegativeOffsetAndOtherOrders() {
        DigestCheck.Spec spec = DigestCheck.Spec.parse("5,shuffled,-40");
        assertEquals(5, spec.radius());
        assertEquals("shuffled", spec.order());
        assertEquals(-40, spec.offset());
    }

    @Test
    public void rejectsUnknownOrder() {
        assertThrows(IllegalArgumentException.class, () -> DigestCheck.Spec.parse("3,spiral,0"));
    }

    @Test
    public void rejectsWrongArity() {
        assertThrows(IllegalArgumentException.class, () -> DigestCheck.Spec.parse("3,rowmajor"));
        assertThrows(IllegalArgumentException.class, () -> DigestCheck.Spec.parse(""));
    }

    @Test
    public void rejectsNonPositiveRadius() {
        assertThrows(IllegalArgumentException.class, () -> DigestCheck.Spec.parse("0,rowmajor,0"));
    }
}

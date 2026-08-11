package dev.krona.urbex.worldgen.lost.regassets.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataToolsStrictNameTest {

    @Test
    void qualifiedNamesParse() {
        assertEquals("urbex:street_straight", DataTools.fromName("urbex:street_straight").toString());
        assertEquals("urbexmt:street_straight", DataTools.fromName("urbexmt:street_straight").toString());
    }

    @Test
    void unqualifiedNameIsRejectedAndSuggestsTheFix() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataTools.fromName("street_straight"));
        assertTrue(e.getMessage().contains("street_straight"),
                "the message must name the offending string: " + e.getMessage());
        assertTrue(e.getMessage().contains("urbex:street_straight"),
                "and show what it should have looked like: " + e.getMessage());
    }
}

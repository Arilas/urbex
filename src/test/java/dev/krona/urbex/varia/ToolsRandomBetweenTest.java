package dev.krona.urbex.varia;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ToolsRandomBetweenTest {

    @Test
    public void equalBoundsReturnMinInsteadOfThrowing() {
        // Profile fields are user-editable: EXPLOSION_MINRADIUS == EXPLOSION_MAXRADIUS used to
        // crash chunk generation with IllegalArgumentException (issue #47).
        assertEquals(12, Tools.randomBetween(new XoroshiroRandomSource(1), 12, 12));
    }

    @Test
    public void invertedBoundsReturnMin() {
        assertEquals(12, Tools.randomBetween(new XoroshiroRandomSource(1), 12, 5));
    }

    @Test
    public void validBoundsStayInHalfOpenRange() {
        for (long seed = 0; seed < 200; seed++) {
            int v = Tools.randomBetween(new XoroshiroRandomSource(seed), 10, 13);
            assertTrue(v >= 10 && v < 13, "got " + v);
        }
    }

    @Test
    public void validBoundsMatchTheOldDrawExactly() {
        // Sites replaced by this helper drew min + nextInt(max - min); worlds must not shift.
        for (long seed = 0; seed < 50; seed++) {
            int expected = 7 + new XoroshiroRandomSource(seed).nextInt(9);
            assertEquals(expected, Tools.randomBetween(new XoroshiroRandomSource(seed), 7, 16));
        }
    }
}

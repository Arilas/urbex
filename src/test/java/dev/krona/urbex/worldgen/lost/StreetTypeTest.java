package dev.krona.urbex.worldgen.lost;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreetTypeTest {

    @Test
    public void nonParkChoiceReachesBothNormalAndFull() {
        // The old magic `values()[nextInt(values().length - 2)]` could only ever produce
        // NORMAL, leaving FULL street sections and the parts.full() asset list dead (issue #36).
        EnumSet<BuildingInfo.StreetType> seen = EnumSet.noneOf(BuildingInfo.StreetType.class);
        for (long seed = 0; seed < 64; seed++) {
            seen.add(BuildingInfo.StreetType.randomNonPark(new XoroshiroRandomSource(seed)));
        }
        assertTrue(seen.contains(BuildingInfo.StreetType.NORMAL), "NORMAL should be reachable");
        assertTrue(seen.contains(BuildingInfo.StreetType.FULL), "FULL should be reachable");
        assertFalse(seen.contains(BuildingInfo.StreetType.PARK), "PARK is chosen by park chance, never here");
    }
}

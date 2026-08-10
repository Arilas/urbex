package dev.krona.urbex.plan.grid;

import dev.krona.urbex.plan.RoadCell;
import dev.krona.urbex.plan.RoadType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridRoadFieldTest {

    private static GridRoadField field(long seed) {
        return new GridRoadField(seed, "urbex:test", GridSettings.defaults());
    }

    private static String sample(GridRoadField f, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int x = from; x < to; x++) {
            for (int z = from; z < to; z++) {
                sb.append(f.at(x, z).type().ordinal());
            }
        }
        return sb.toString();
    }

    @Test
    void sameInputsReproduceTheFieldExactly() {
        assertEquals(sample(field(1337L), -40, 40), sample(field(1337L), -40, 40));
    }

    @Test
    void changingTheSeedChangesTheField() {
        assertNotEquals(sample(field(1337L), -40, 40), sample(field(9001L), -40, 40));
    }

    @Test
    void changingTheDimensionChangesTheField() {
        GridRoadField a = new GridRoadField(1337L, "urbex:one", GridSettings.defaults());
        GridRoadField b = new GridRoadField(1337L, "urbex:two", GridSettings.defaults());
        assertNotEquals(sample(a, -40, 40), sample(b, -40, 40));
    }

    @Test
    void queryOrderCannotChangeTheAnswer() {
        GridRoadField f = field(1337L);
        List<int[]> coords = new ArrayList<>();
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                coords.add(new int[]{x, z});
            }
        }
        String rowMajor = sample(f, -40, 40);

        GridRoadField shuffledField = field(1337L);
        Collections.shuffle(coords, new java.util.Random(42));
        for (int[] c : coords) {
            shuffledField.at(c[0], c[1]);
        }
        assertEquals(rowMajor, sample(shuffledField, -40, 40),
                "a shuffled warm-up must not change later answers");
    }

    @Test
    void anActivePrimaryIsStraightAndContinuous() {
        GridRoadField f = field(1337L);
        int found = 0;
        for (int x = -64; x < 64; x++) {
            if (f.at(x, 0).type() != RoadType.PRIMARY) {
                continue;
            }
            boolean verticalEverywhere = true;
            for (int z = -64; z < 64; z++) {
                if (f.at(x, z).type() != RoadType.PRIMARY) {
                    verticalEverywhere = false;
                    break;
                }
            }
            if (verticalEverywhere) {
                found++;
            }
        }
        assertTrue(found > 0, "expected at least one continuous vertical primary corridor");
    }

    @Test
    void thereIsNoSeamAtCoordinateZero() {
        // Containment, not just non-inverted bounds: truncating division instead of floorDiv would
        // still leave eastX >= westX, but it would assign a chunk to a block it isn't inside.
        GridRoadField f = field(1337L);
        for (int x = -64; x < 64; x++) {
            for (int z = -64; z < 64; z++) {
                RoadCell cell = f.at(x, z);
                assertTrue(cell.westX() <= x && x < cell.eastX(),
                        "chunk x=" + x + " outside its primary block [" + cell.westX() + "," + cell.eastX() + ")");
                assertTrue(cell.northZ() <= z && z < cell.southZ(),
                        "chunk z=" + z + " outside its primary block [" + cell.northZ() + "," + cell.southZ() + ")");
            }
        }
    }
}

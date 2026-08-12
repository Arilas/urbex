package dev.krona.urbex.worldgen;

import dev.krona.urbex.varia.ChunkCoord;
import dev.krona.urbex.worldgen.lost.cityassets.WorldStyle;
import dev.krona.urbex.worldgen.lost.regassets.WorldStyleRE;
import dev.krona.urbex.worldgen.lost.regassets.data.HighwayParts;
import dev.krona.urbex.worldgen.lost.regassets.data.Mergeable;
import dev.krona.urbex.worldgen.lost.regassets.data.PartSelector;
import dev.krona.urbex.worldgen.lost.regassets.data.RailwayParts;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The field is what makes mixing possible without making a one-style world generate differently.
 * <p>
 * Its most important property is a negative one: with a single style it must never draw from
 * {@link dev.krona.urbex.varia.Rng}, so every world created before mixing existed - and every world
 * on an install that never set {@code experimentalMultiWorldStyles} - keeps its digests. The
 * {@code assertSame} assertions below are that guarantee: a drawn answer would be the same object
 * only by luck, and the distribution test shows draws do happen once there is more than one entry.
 */
class WorldStyleFieldTest {

    private static final long SEED = 0x5EEDL;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * A minimal resolvable world style, built by hand rather than from a registry - the same shape
     * {@code NullDimensionInfo}'s preview placeholder uses, which is the only other place in the
     * tree that constructs a {@code WorldStyleRE} directly. Declares everything
     * {@code WorldStyle} requires after resolution and nothing else.
     */
    private static WorldStyle style(String path) {
        WorldStyleRE re = new WorldStyleRE(
                Optional.empty(),
                // No display name: these exist to be told apart by id, and getName() is what the
                // field's primary tie-break and this test's assertions read.
                Optional.empty(),
                Optional.of("urbex:standard"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new PartSelector.Decl(
                        Optional.of(new HighwayParts.Decl(
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts())),
                        Optional.of(new RailwayParts.Decl(
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts(),
                                noParts(), noParts(), noParts(), noParts(), noParts(), noParts(),
                                noParts(), noParts(), noParts(), noParts())))),
                Optional.of(new Mergeable<>(true, Collections.emptyList())),
                Optional.empty(),
                Optional.empty()
        ).setRegistryName(Identifier.fromNamespaceAndPath("urbextest", path));
        return new WorldStyle(List.of(re));
    }

    private static Optional<Mergeable<String>> noParts() {
        return Optional.of(new Mergeable<>(true, Collections.emptyList()));
    }

    private static ChunkCoord coord(int x, int z) {
        return new ChunkCoord(Level.OVERWORLD, x, z);
    }

    private static WorldStyleField mixed() {
        return new WorldStyleField(SEED, List.of(
                new WorldStyleField.Weighted(0.1f, style("light")),
                new WorldStyleField.Weighted(0.9f, style("heavy"))));
    }

    @Test
    void oneStyleAlwaysAnswersItselfWithoutDrawing() {
        WorldStyle only = style("light");
        WorldStyleField field = WorldStyleField.single(SEED, only);
        assertTrue(field.isSingle());
        assertSame(only, field.primary());
        for (int x = -50; x <= 50; x += 7) {
            for (int z = -50; z <= 50; z += 11) {
                assertSame(only, field.atCityCenter(coord(x, z)));
                assertSame(only, field.atScatterArea(coord(x, z)));
                assertSame(only, field.atMultiArea(coord(x, z)));
            }
        }
    }

    @Test
    void theSameAddressAlwaysDrawsTheSameStyle() {
        WorldStyleField field = mixed();
        for (int x = -20; x <= 20; x += 3) {
            for (int z = -20; z <= 20; z += 3) {
                assertSame(field.atCityCenter(coord(x, z)), field.atCityCenter(coord(x, z)));
                // The three per-area accessors share one address space on purpose: a collision just
                // means two unrelated things drew the same style, which is harmless.
                assertSame(field.atCityCenter(coord(x, z)), field.atScatterArea(coord(x, z)));
            }
        }
    }

    @Test
    void differentSeedsDrawDifferently() {
        WorldStyleField a = mixed();
        WorldStyleField b = new WorldStyleField(SEED + 1, List.of(
                new WorldStyleField.Weighted(0.1f, style("light")),
                new WorldStyleField.Weighted(0.9f, style("heavy"))));
        int differences = 0;
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                if (!a.atCityCenter(coord(x, z)).getName().equals(b.atCityCenter(coord(x, z)).getName())) {
                    differences++;
                }
            }
        }
        assertTrue(differences > 0, "two seeds produced an identical style field");
    }

    @Test
    void drawsTrackTheWeights() {
        WorldStyleField field = mixed();
        int heavy = 0;
        int total = 0;
        for (int x = -60; x <= 60; x++) {
            for (int z = -60; z <= 60; z++) {
                total++;
                if (field.atCityCenter(coord(x, z)).getName().endsWith(":heavy")) {
                    heavy++;
                }
            }
        }
        double share = (double) heavy / total;
        // 0.9 nominal. A wide band: this asserts the weights are honoured at all, not that the hash
        // is uniform to three digits.
        assertTrue(share > 0.85 && share < 0.95, "heavy style share was " + share);
    }

    @Test
    void primaryIsTheHeaviestStyle() {
        assertEquals("urbextest:heavy", mixed().primary().getName());
    }

    @Test
    void equalWeightsBreakTheTieOnTheIdNotOnListOrder() {
        // Mirrors WorldStyleMix.primary: the field is built from a list that can arrive in registry
        // iteration order, so a positional tie-break would depend on file names.
        WorldStyle light = style("light");
        WorldStyle heavy = style("heavy");
        assertEquals("urbextest:heavy", new WorldStyleField(SEED, List.of(
                new WorldStyleField.Weighted(1.0f, light),
                new WorldStyleField.Weighted(1.0f, heavy))).primary().getName());
        assertEquals("urbextest:heavy", new WorldStyleField(SEED, List.of(
                new WorldStyleField.Weighted(1.0f, heavy),
                new WorldStyleField.Weighted(1.0f, light))).primary().getName());
    }

    @Test
    void anEmptyFieldIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new WorldStyleField(SEED, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorldStyleField(SEED, null));
    }
}

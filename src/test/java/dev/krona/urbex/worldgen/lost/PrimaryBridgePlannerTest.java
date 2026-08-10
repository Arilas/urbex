package dev.krona.urbex.worldgen.lost;

import dev.krona.urbex.varia.ChunkCoord;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The two properties a planned bridge lives or dies by: a span is only accepted when the whole run
 * of water is bridgeable, and every chunk of it - and of any span crossing it - reaches the same
 * verdict without knowing which chunk asked first.
 * <p>
 * The scan is exercised against a hand-drawn map of chunks rather than a live world, which is what
 * {@link PrimaryBridgePlanner.ChunkFacts} exists for. Bootstrapped because {@code ChunkCoord}
 * carries a {@code ResourceKey<Level>}.
 */
class PrimaryBridgePlannerTest {

    private static final long SEED = 0x5eed_1234_abcd_0001L;
    private static final int MAX_GAP = 12;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ChunkCoord at(int x, int z) {
        return new ChunkCoord(Level.OVERWORLD, x, z);
    }

    /**
     * A map of chunks, one row per {@code z} starting at zero and one character per {@code x}:
     * <ul>
     *     <li>{@code P} - city, the primary road renders, city level zero: a valid endpoint</li>
     *     <li>{@code H} - city, the primary road renders, but city level one</li>
     *     <li>{@code C} - city on the primary line where the road does not survive the clip</li>
     *     <li>{@code w} - open water on the primary line: a bridgeable gap</li>
     *     <li>{@code l} - dry land on the primary line</li>
     *     <li>{@code .} - not on the raw primary line at all</li>
     * </ul>
     * Anything off the map is {@code .}.
     */
    private static PrimaryBridgePlanner.ChunkFacts map(String... rows) {
        return new PrimaryBridgePlanner.ChunkFacts() {
            private char at(ChunkCoord coord) {
                int x = coord.chunkX();
                int z = coord.chunkZ();
                if (z < 0 || z >= rows.length || x < 0 || x >= rows[z].length()) {
                    return '.';
                }
                return rows[z].charAt(x);
            }

            @Override
            public boolean isRawPrimary(ChunkCoord coord) {
                return at(coord) != '.';
            }

            @Override
            public boolean isCity(ChunkCoord coord) {
                char c = at(coord);
                return c == 'P' || c == 'H' || c == 'C';
            }

            @Override
            public boolean isEffectivePrimary(ChunkCoord coord) {
                char c = at(coord);
                return c == 'P' || c == 'H';
            }

            @Override
            public int cityLevel(ChunkCoord coord) {
                return at(coord) == 'H' ? 1 : 0;
            }

            @Override
            public boolean isWaterLike(ChunkCoord coord) {
                return at(coord) == 'w';
            }
        };
    }

    @Test
    void aSpanShorterThanTheMinimumIsRejected() {
        // Two primary road chunks meeting with no water between them. There is nothing to bridge,
        // and neither chunk is a gap, so no scan can start.
        PrimaryBridgePlanner.ChunkFacts facts = map("PP");
        assertNull(PrimaryBridgePlanner.scanSpan(at(0, 0), Orientation.X, facts, MAX_GAP),
                "a road chunk is not a bridge candidate");
        assertNull(PrimaryBridgePlanner.scanSpan(at(1, 0), Orientation.X, facts, MAX_GAP),
                "and neither is the road chunk facing it");
    }

    @Test
    void aSpanContainingACityChunkIsRejected() {
        // An island in the middle of the water, on the primary line but with no road rendering on
        // it. The deck may only cross open water, so the span is refused rather than shortened.
        PrimaryBridgePlanner.ChunkFacts facts = map("PwCwP");
        assertNull(PrimaryBridgePlanner.scanSpan(at(1, 0), Orientation.X, facts, MAX_GAP),
                "scanning towards the island from the low side must find no span");
        assertNull(PrimaryBridgePlanner.scanSpan(at(3, 0), Orientation.X, facts, MAX_GAP),
                "and scanning towards it from the high side must agree");
    }

    @Test
    void anEndThatIsNotAnEffectivePrimaryAtLevelZeroIsRejected() {
        assertNull(PrimaryBridgePlanner.scanSpan(at(1, 0), Orientation.X, map("PwwC"), MAX_GAP),
                "a city end where the road does not render cannot carry the deck");
        assertNull(PrimaryBridgePlanner.scanSpan(at(1, 0), Orientation.X, map("PwwH"), MAX_GAP),
                "a city end one level up cannot either: the deck is authored flat");
        assertNull(PrimaryBridgePlanner.scanSpan(at(1, 0), Orientation.X, map("Pwwl"), MAX_GAP),
                "and dry land is not an end at all");
    }

    @Test
    void waterWiderThanTheLimitIsRejected() {
        PrimaryBridgePlanner.ChunkFacts facts = map("PwwwwwP");
        assertNull(PrimaryBridgePlanner.scanSpan(at(3, 0), Orientation.X, facts, 4),
                "five chunks of water is beyond a limit of four");
        assertNotNull(PrimaryBridgePlanner.scanSpan(at(3, 0), Orientation.X, facts, 5),
                "and within a limit of five");
    }

    @Test
    void everyChunkOfASpanReconstructsTheSameAddress() {
        // The whole point of addressing the roll by the span rather than by the chunk: three water
        // chunks, each of which must independently arrive at the same identity.
        PrimaryBridgePlanner.ChunkFacts facts = map("PwwwP");
        PrimaryBridgePlanner.BridgeSpan expected =
                new PrimaryBridgePlanner.BridgeSpan(Orientation.X, 0, 0, 4, 0);
        for (int x = 1; x <= 3; x++) {
            PrimaryBridgePlanner.BridgeSpan span =
                    PrimaryBridgePlanner.scanSpan(at(x, 0), Orientation.X, facts, MAX_GAP);
            assertEquals(expected, span, "chunk " + x + " must find the same span");
            assertEquals(PrimaryBridgePlanner.address(SEED, expected), PrimaryBridgePlanner.address(SEED, span),
                    "chunk " + x + " must take the same roll");
        }
        // And the address survives having the endpoints handed over the other way round, which is
        // what a scan started from the far end would produce if the sort were ever dropped.
        assertEquals(PrimaryBridgePlanner.address(SEED, expected),
                PrimaryBridgePlanner.address(SEED, new PrimaryBridgePlanner.BridgeSpan(Orientation.X, 4, 0, 0, 0)),
                "the endpoints are sorted, so either order is the same span");
    }

    @Test
    void bothSidesOfACrossingAgreeOnTheWinner() {
        long horizontal = 0x1234_5678_9abc_def0L;
        long vertical = 0x0fed_cba9_8765_4321L;
        assertEquals(PrimaryBridgePlanner.winsCrossing(horizontal, vertical),
                PrimaryBridgePlanner.winsCrossing(horizontal, vertical),
                "the rule must not depend on which side asks");
        assertNotEquals(PrimaryBridgePlanner.winsCrossing(horizontal, vertical),
                PrimaryBridgePlanner.winsCrossing(vertical, horizontal),
                "exactly one orientation wins");
    }

    @Test
    void aCrossingLooksTheSameFromEitherSpan() {
        // A river crossing a lake: the horizontal span (0,2)-(4,2) and the vertical span (2,0)-(2,4)
        // both want the chunk at (2,2). Neither side is allowed to reach a different pair of
        // addresses than the other, whichever of its own chunks does the asking.
        PrimaryBridgePlanner.ChunkFacts facts = map(
                "..P..",
                "..w..",
                "PwwwP",
                "..w..",
                "..P..");
        PrimaryBridgePlanner.BridgeSpan horizontal =
                new PrimaryBridgePlanner.BridgeSpan(Orientation.X, 0, 2, 4, 2);
        PrimaryBridgePlanner.BridgeSpan vertical =
                new PrimaryBridgePlanner.BridgeSpan(Orientation.Z, 2, 0, 2, 4);
        for (int x = 1; x <= 3; x++) {
            assertEquals(horizontal, PrimaryBridgePlanner.scanSpan(at(x, 2), Orientation.X, facts, MAX_GAP),
                    "every chunk of the horizontal span must find it");
        }
        for (int z = 1; z <= 3; z++) {
            assertEquals(vertical, PrimaryBridgePlanner.scanSpan(at(2, z), Orientation.Z, facts, MAX_GAP),
                    "every chunk of the vertical span must find it");
        }
        // The chunk under the crossing sees both, and sees exactly the same two spans its
        // neighbours on either arm reconstructed on their own.
        assertEquals(horizontal, PrimaryBridgePlanner.scanSpan(at(2, 2), Orientation.X, facts, MAX_GAP));
        assertEquals(vertical, PrimaryBridgePlanner.scanSpan(at(2, 2), Orientation.Z, facts, MAX_GAP));

        long horizontalAddress = PrimaryBridgePlanner.address(SEED, horizontal);
        long verticalAddress = PrimaryBridgePlanner.address(SEED, vertical);
        assertNotEquals(PrimaryBridgePlanner.winsCrossing(horizontalAddress, verticalAddress),
                PrimaryBridgePlanner.winsCrossing(verticalAddress, horizontalAddress),
                "one of the two real spans wins and the other loses");
    }
}

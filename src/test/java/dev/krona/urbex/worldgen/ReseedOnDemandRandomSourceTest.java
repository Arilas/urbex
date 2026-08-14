package dev.krona.urbex.worldgen;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The deferred reseed is invisible: the sequence matches an eagerly seeded
 * {@link XoroshiroRandomSource} exactly.
 * <p>
 * {@link ReseedOnDemandRandomSource} exists to stop {@code setSeed} allocating for the
 * overwhelming majority of shape updates that never draw from the stream, and the whole argument
 * for it being safe is that it changes <em>when</em> the delegate is seeded and nothing else. That
 * argument is worth a test rather than a comment: a shape update feeds generated output, and
 * getting it wrong would move every fence, wall and stair in the world.
 * <p>
 * The digest goldens cover this too, but only for the streams those windows happen to draw. These
 * pin every method the delegate overrides, including {@code consumeCount} - which
 * {@code XoroshiroRandomSource} overrides with a cheaper skip than the interface default, so a
 * wrapper that inherited the default instead would silently desynchronise the stream.
 */
class ReseedOnDemandRandomSourceTest {

    private static final long[] SEEDS = {0L, 1L, -1L, 1337L, 135132278163449878L, Long.MIN_VALUE, Long.MAX_VALUE};

    @Test
    void matchesAnEagerlySeededSourceAcrossEveryDrawingMethod() {
        for (long seed : SEEDS) {
            XoroshiroRandomSource expected = new XoroshiroRandomSource(0L);
            expected.setSeed(seed);
            ReseedOnDemandRandomSource actual = new ReseedOnDemandRandomSource();
            actual.setSeed(seed);

            for (int i = 0; i < 64; i++) {
                assertEquals(expected.nextInt(), actual.nextInt(), "nextInt at seed " + seed);
                assertEquals(expected.nextInt(97), actual.nextInt(97), "nextInt(bound) at seed " + seed);
                assertEquals(expected.nextLong(), actual.nextLong(), "nextLong at seed " + seed);
                assertEquals(expected.nextBoolean(), actual.nextBoolean(), "nextBoolean at seed " + seed);
                assertEquals(expected.nextFloat(), actual.nextFloat(), "nextFloat at seed " + seed);
                assertEquals(expected.nextDouble(), actual.nextDouble(), "nextDouble at seed " + seed);
                assertEquals(expected.nextGaussian(), actual.nextGaussian(), "nextGaussian at seed " + seed);
            }
        }
    }

    /** The one method whose interface default differs from what the delegate does. */
    @Test
    void consumeCountSkipsTheSameDistanceAsTheDelegate() {
        XoroshiroRandomSource expected = new XoroshiroRandomSource(0L);
        expected.setSeed(1337L);
        ReseedOnDemandRandomSource actual = new ReseedOnDemandRandomSource();
        actual.setSeed(1337L);

        expected.consumeCount(11);
        actual.consumeCount(11);
        assertEquals(expected.nextLong(), actual.nextLong(), "the streams are still aligned after a skip");
    }

    /**
     * Reseeding without drawing in between must not advance anything - this is the case the
     * shaper is in for almost every position it corrects.
     */
    @Test
    void aReseedThatNobodyDrawsFromCostsTheNextStreamNothing() {
        ReseedOnDemandRandomSource actual = new ReseedOnDemandRandomSource();
        actual.setSeed(1L);
        actual.setSeed(2L);
        actual.setSeed(1337L);

        XoroshiroRandomSource expected = new XoroshiroRandomSource(0L);
        expected.setSeed(1337L);
        assertEquals(expected.nextLong(), actual.nextLong(),
                "only the last seed applied should be observable");
    }

    /** Each new seed restarts the stream, rather than continuing the previous one. */
    @Test
    void everySeedRestartsTheStream() {
        ReseedOnDemandRandomSource actual = new ReseedOnDemandRandomSource();
        for (long seed : SEEDS) {
            XoroshiroRandomSource expected = new XoroshiroRandomSource(0L);
            expected.setSeed(seed);
            actual.setSeed(seed);
            actual.nextInt();
            expected.nextInt();
            assertEquals(expected.nextLong(), actual.nextLong(), "restart at seed " + seed);
        }
    }
}

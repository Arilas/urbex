package dev.krona.urbex.worldgen;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Counts cross-chunk terrain access - reads and writes alike - made by Urbex during world
 * generation.
 *
 * <p>City generation runs at the tail of {@code applyCarvers}, where Minecraft gives a chunk a
 * write radius of 0 - it may touch only itself. Reading or writing a neighbour there is a contract
 * violation: what it sees (or whether a write even lands) depends on worker scheduling, so it is a
 * latent source of order-dependent output. Two such reads survived for months because nothing
 * watched for them; this is what watches, and {@code UnsafeReadGateMixin} feeds it from both sides.
 *
 * <p>Only access whose stack carries an Urbex frame is counted. Vanilla makes cross-chunk reads of
 * its own that are none of our business and that we could not fix.
 */
public final class UnsafeReadCounter {

    private static final AtomicLong COUNT = new AtomicLong();
    private static final AtomicReference<String> FIRST_SAMPLE = new AtomicReference<>();

    private UnsafeReadCounter() {
    }

    /** Records one violation. {@code frame} is the innermost Urbex frame, for the failure message. */
    public static void record(String frame) {
        COUNT.incrementAndGet();
        FIRST_SAMPLE.compareAndSet(null, frame);
    }

    public static long count() {
        return COUNT.get();
    }

    /** The innermost Urbex frame of the first violation seen, or null if there were none. */
    public static String firstSample() {
        return FIRST_SAMPLE.get();
    }

    public static void reset() {
        COUNT.set(0);
        FIRST_SAMPLE.set(null);
    }
}

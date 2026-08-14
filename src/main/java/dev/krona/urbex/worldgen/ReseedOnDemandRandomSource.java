package dev.krona.urbex.worldgen;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * A {@link XoroshiroRandomSource} whose {@code setSeed} costs nothing until something actually
 * draws from it.
 *
 * <p>{@link BlockShaper} addresses one random stream per position and then hands the source to
 * {@code updateShape}, which for almost every block never touches it - a fence, wall, pane or
 * stair resolves its connections from neighbour states alone. The seed was still being applied on
 * every call, and {@code XoroshiroRandomSource.setSeed} is not free: it allocates a fresh
 * {@code Xoroshiro128PlusPlus} and resets the gaussian source. Against a 625-chunk
 * {@code onlycities} window that was the single largest allocation site in generation, 1.5 GiB of
 * {@code Xoroshiro128PlusPlus} for streams that were overwhelmingly never read.</p>
 *
 * <p>So the seed is remembered and applied on first use. Every draw goes through {@link #source()},
 * which reseeds if a {@link #setSeed} has landed since the last one. The observable sequence is
 * unchanged - the only difference is <em>when</em> the underlying source is seeded, and nothing
 * else holds a reference to it - so this is invisible to generated output. The digest goldens
 * cover that claim.</p>
 *
 * <p>Every method {@link XoroshiroRandomSource} overrides is delegated, {@code consumeCount}
 * included: it overrides the interface default with a cheaper skip, and inheriting the default
 * here instead would quietly change the stream. The three methods it does <em>not</em> override
 * ({@code nextIntBetweenInclusive}, {@code triangle}, {@code nextInt(int, int)}) are left to the
 * interface defaults, which reach the delegate through the methods below.</p>
 *
 * <p>Thread-confined, like the shaper that owns it: one per driver, per chunk, per thread.</p>
 */
final class ReseedOnDemandRandomSource implements RandomSource {

    private final XoroshiroRandomSource delegate = new XoroshiroRandomSource(0L);
    private long pendingSeed;
    private boolean needsReseed = true;

    @Override
    public void setSeed(long seed) {
        pendingSeed = seed;
        needsReseed = true;
    }

    private XoroshiroRandomSource source() {
        if (needsReseed) {
            delegate.setSeed(pendingSeed);
            needsReseed = false;
        }
        return delegate;
    }

    @Override
    public RandomSource fork() {
        return source().fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return source().forkPositional();
    }

    @Override
    public int nextInt() {
        return source().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return source().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return source().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return source().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return source().nextFloat();
    }

    @Override
    public double nextDouble() {
        return source().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return source().nextGaussian();
    }

    @Override
    public void consumeCount(int count) {
        source().consumeCount(count);
    }
}

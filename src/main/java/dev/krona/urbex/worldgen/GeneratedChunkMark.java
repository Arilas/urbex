package dev.krona.urbex.worldgen;

import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Whether Urbex has already generated a chunk, carried by the chunk itself.
 *
 * <p>Two entry points can reach city generation: the carver-tail hook, which is how every supported
 * world generates, and the registered {@code urbex:city} feature, which a datapack can place
 * explicitly. Nothing coordinated them, so a pack that named the feature in a biome's {@code features}
 * generated the chunk twice - the second pass planning against terrain the first pass had already
 * rewritten (issue #131).</p>
 *
 * <p>The mark lives on the {@link ChunkAccess} through a mixin-added field rather than in a set
 * somewhere, because that gives it exactly the right lifetime for free: it is created with the chunk
 * and collected with it. A {@code Set<ChunkPos>} would need a policy for when to forget a chunk, and
 * getting that wrong in either direction is either a leak or a chunk generated twice after all.</p>
 *
 * <p>One method rather than a getter and a setter, so that claiming is not a check followed by an
 * act. The two callers cannot in fact race - the chunk pipeline orders {@code CARVERS} strictly
 * before {@code FEATURES} for a given chunk - but "this is safe if you know how the pipeline
 * schedules" is a worse thing to write down than an operation that is safe either way.</p>
 */
public interface GeneratedChunkMark {

    /**
     * Claims this chunk for generation, if nobody has.
     *
     * <p>Claiming happens <em>before</em> generating rather than after: a generation that fails
     * partway has still written whatever it wrote, and a second attempt through the other entry point
     * would write over it rather than repair it.</p>
     *
     * @return {@code true} for the first caller only
     */
    boolean urbex$claimGeneration();

    /**
     * Claims {@code chunk}, or answers {@code true} if it cannot carry a mark.
     *
     * @return {@code true} if this caller is the one that may generate the chunk
     */
    static boolean claim(ChunkAccess chunk) {
        return claimMark(chunk);
    }

    /**
     * @see #claim(ChunkAccess)
     *
     * <p>Takes an {@link Object} only so the rule can be exercised without one: {@code ChunkAccess}
     * is an abstract class that can be neither constructed nor proxied in a unit test, and a rule
     * deciding which of two callers may write a chunk is worth testing directly rather than only
     * through a dedicated-server run.</p>
     */
    static boolean claimMark(Object chunk) {
        if (!(chunk instanceof GeneratedChunkMark mark)) {
            // Every ChunkAccess is mixed into, so this is unreachable in a running game. Refusing to
            // generate for something that cannot carry the mark would be the worse failure of the
            // two: it looks exactly like "this dimension has no preset".
            return true;
        }
        return mark.urbex$claimGeneration();
    }
}

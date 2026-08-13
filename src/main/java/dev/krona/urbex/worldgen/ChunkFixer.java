package dev.krona.urbex.worldgen;

public class ChunkFixer {

    private ChunkFixer() {
    }

    /**
     * Runs the post-generation todos this context queued, once.
     *
     * <p>The context, not a re-fetched {@code ChunkPlan}: the todos belong to this generation of
     * this chunk and nothing else may see them. Draining through the cache was how an evicted entry
     * lost them and how a second generation of the same chunk inherited the first one's (issue
     * #127); {@link PostTodoQueue} now refuses a second drain outright.</p>
     *
     * <p>They are applied to {@code ctx.region} rather than {@code provider.getWorld()}: the todos
     * read and write blocks, and only the region generating this chunk is guaranteed to have the
     * chunks they touch.</p>
     */
    public static void fix(ChunkGenContext ctx) {
        ctx.drainPostTodo().forEach((pos, todo) -> todo.accept(ctx.region));
    }
}
